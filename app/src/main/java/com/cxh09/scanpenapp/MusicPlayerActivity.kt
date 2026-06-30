package com.cxh09.scanpenapp

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cxh09.scanpenapp.databinding.ActivityMusicPlayerBinding
import com.cxh09.scanpenapp.music.lyric.LrcLine
import com.cxh09.scanpenapp.music.lyric.LrcParser
import com.cxh09.scanpenapp.music.player.MusicPlayer
import com.cxh09.scanpenapp.music.player.PlayQueue
import com.cxh09.scanpenapp.music.player.PlayableSong
import com.cxh09.scanpenapp.music.api.MusicApi
import com.cxh09.scanpenapp.music.api.MusicSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 全屏播放页（云音乐）。
 *
 * - 顶栏 32dp：返回 + 「正在播放」
 * - 主体：coverArea（默认 visible）/ lyricArea（默认 gone）切换
 * - 底部 96dp 控制栏：SeekBar + 时间 + 5 个按钮
 * - 状态：实现 [MusicPlayer.Listener]，在 [onDestroy] 时反注册
 * - 歌词：第一次进入 `PLAYING` 状态时拉取 `MusicApi.lyric`，解析为 [LrcLine] 后 inflate 到 [lyricContainer]
 * - 自动续播：`onCompletion` 触发时若 mode != REPEAT_ONE 则调下一首
 *
 * 退出时不调 [MusicPlayer.stop] / [MusicPlayer.pause]，音乐继续播。
 */
class MusicPlayerActivity : AppCompatActivity(), MusicPlayer.Listener {

    private lateinit var binding: ActivityMusicPlayerBinding
    private lateinit var prefs: android.content.SharedPreferences

    private var lrcLines: List<LrcLine> = emptyList()
    private var lastHighlightedIndex: Int = -1
    private var lastScrollTs: Long = 0L
    private var lyricJob: Job? = null
    private var loadedLrcForSongId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        prefs = getSharedPreferences(
            MusicServerSettingsActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        // 初始渲染当前曲目
        renderCurrentSong()
        renderModeButton()

        binding.btnPlayerBack.setOnClickListener { finish() }

        binding.btnPlayerPlayPause.setOnClickListener {
            when (MusicPlayer.state()) {
                MusicPlayer.State.PLAYING -> MusicPlayer.pause()
                MusicPlayer.State.PAUSED -> MusicPlayer.resume()
                else -> { /* IDLE / PREPARING no-op */ }
            }
        }
        binding.btnPlayerPrev.setOnClickListener {
            val newSong = PlayQueue.prev() ?: return@setOnClickListener
            val all = PlayQueue.snapshot()
            val newIndex = all.indexOf(newSong).coerceAtLeast(0)
            playSongFlow(newSong, all, newIndex)
        }
        binding.btnPlayerNext.setOnClickListener {
            val newSong = PlayQueue.next() ?: return@setOnClickListener
            val all = PlayQueue.snapshot()
            val newIndex = all.indexOf(newSong).coerceAtLeast(0)
            playSongFlow(newSong, all, newIndex)
        }
        binding.btnPlayerMode.setOnClickListener {
            PlayQueue.cycleMode()
            renderModeButton()
        }
        binding.btnPlayerLyric.setOnClickListener {
            toggleLyric()
        }
        binding.coverArea.setOnClickListener { showLyric() }
        binding.coverPlaceholder.setOnClickListener { showLyric() }
        binding.lyricArea.setOnClickListener { showCover() }

        binding.sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    MusicPlayer.seekTo(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        MusicPlayer.addListener(this)
    }

    override fun onDestroy() {
        MusicPlayer.removeListener(this)
        lyricJob?.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    // ---------------- Listener ----------------

    override fun onStateChanged(state: MusicPlayer.State) {
        // 更新主控按钮图标
        val iconRes = if (state == MusicPlayer.State.PLAYING) {
            R.drawable.ic_player_pause
        } else {
            R.drawable.ic_player_play
        }
        binding.btnPlayerPlayPause.setImageResource(iconRes)
        binding.btnPlayerPlayPause.contentDescription = getString(
            if (state == MusicPlayer.State.PLAYING) R.string.music_player_pause
            else R.string.music_player_play
        )
        // PREPARING 时把歌名替换为「加载中...」
        if (state == MusicPlayer.State.PREPARING) {
            binding.tvSongName.text = getString(R.string.music_player_loading)
        } else {
            renderCurrentSong()
        }
        // 第一次进入 PLAYING 时加载歌词
        if (state == MusicPlayer.State.PLAYING) {
            loadLyricIfNeeded()
        }
    }

    override fun onPosition(positionMs: Long, durationMs: Long) {
        if (durationMs > 0) {
            binding.sbProgress.max = durationMs.toInt().coerceAtLeast(1)
        }
        binding.sbProgress.progress = positionMs.toInt().coerceAtLeast(0)
        binding.tvPosition.text = formatMs(positionMs)
        binding.tvDuration.text = formatMs(durationMs)
        // 歌词高亮
        if (lrcLines.isNotEmpty() && binding.lyricArea.visibility == View.VISIBLE) {
            val idx = LrcParser.findCurrentLineIndex(lrcLines, positionMs)
            if (idx >= 0 && idx != lastHighlightedIndex) {
                highlightLyricLine(idx)
                lastHighlightedIndex = idx
            }
            // 节流 250ms 滚动
            val now = System.currentTimeMillis()
            if (idx >= 0 && now - lastScrollTs >= 250L) {
                scrollToHighlightedLine(idx)
                lastScrollTs = now
            }
        }
    }

    override fun onCompletion() {
        // 自动续播：除 REPEAT_ONE 外自动调下一首
        if (PlayQueue.mode() == com.cxh09.scanpenapp.music.player.PlayQueue.Mode.REPEAT_ONE) {
            // 单曲循环：用户手动切歌时已经走 playSongFlow；这里不主动切。
            // 实际上 MediaPlayer 的 setOnCompletion 触发时 REPEAT_ONE 也无法自动重播，
            // 简化处理：UI 层不切歌，回到 IDLE；用户点下一首会再触发 play。
            return
        }
        val newSong = PlayQueue.next() ?: return
        val all = PlayQueue.snapshot()
        val newIndex = all.indexOf(newSong).coerceAtLeast(0)
        playSongFlow(newSong, all, newIndex)
    }

    override fun onError(code: Int, message: String) {
        Toast.makeText(this, "播放出错：$message", Toast.LENGTH_SHORT).show()
        // 错误时回到 IDLE，不 finish（让用户看到当前页面并主动返回）
        renderCurrentSong()
    }

    // ---------------- 内部 ----------------

    private fun renderCurrentSong() {
        val title = MusicPlayer.currentTitle()
        val artist = MusicPlayer.currentArtist()
        binding.tvSongName.text = if (title.isNotBlank()) title else getString(R.string.music_player_loading)
        binding.tvArtistName.text = artist
        binding.tvCoverInitial.text = title.firstOrNull()?.toString() ?: "♪"
    }

    private fun renderModeButton() {
        val (iconRes, labelRes) = when (PlayQueue.mode()) {
            PlayQueue.Mode.NORMAL -> R.drawable.ic_player_mode_normal to R.string.music_player_mode_normal
            PlayQueue.Mode.LOOP -> R.drawable.ic_player_mode_loop to R.string.music_player_mode_loop
            PlayQueue.Mode.REPEAT_ONE -> R.drawable.ic_player_mode_repeat_one to R.string.music_player_mode_repeat_one
            PlayQueue.Mode.SHUFFLE -> R.drawable.ic_player_mode_shuffle to R.string.music_player_mode_shuffle
        }
        binding.btnPlayerMode.setImageResource(iconRes)
        binding.btnPlayerMode.contentDescription = getString(labelRes)
        // tint 切换：激活 = active，非激活 = inactive
        val tint = ContextCompat.getColor(this, R.color.player_mode_active_tint)
        binding.btnPlayerMode.imageTintList = android.content.res.ColorStateList.valueOf(tint)
    }

    private fun toggleLyric() {
        if (binding.lyricArea.visibility == View.VISIBLE) showCover() else showLyric()
    }

    private fun showCover() {
        binding.coverArea.visibility = View.VISIBLE
        binding.lyricArea.visibility = View.GONE
        binding.btnPlayerLyric.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.player_mode_inactive_tint)
        )
    }

    private fun showLyric() {
        binding.coverArea.visibility = View.GONE
        binding.lyricArea.visibility = View.VISIBLE
        binding.btnPlayerLyric.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.player_mode_active_tint)
        )
        // 切到歌词区时立即拉一次歌词（如果还没拉）
        loadLyricIfNeeded()
    }

    private fun loadLyricIfNeeded() {
        val songId = PlayQueue.current()?.id ?: return
        if (loadedLrcForSongId == songId) return
        loadedLrcForSongId = songId
        val baseUrl = currentBaseUrl() ?: return
        val cookie = MusicSession.cookie(this).ifBlank { null }
        lyricJob?.cancel()
        lyricJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                MusicApi.lyric(baseUrl, cookie, songId)
            }
            val lrcText = result.lrc
            lrcLines = LrcParser.parse(lrcText)
            renderLyricLines()
        }
    }

    private fun renderLyricLines() {
        binding.lyricContainer.removeAllViews()
        lastHighlightedIndex = -1
        if (lrcLines.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.music_player_lyric_empty)
                setTextColor(ContextCompat.getColor(this@MusicPlayerActivity, R.color.player_text_tertiary))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 32, 0, 32)
            }
            binding.lyricContainer.addView(tv)
            return
        }
        val inflater = LayoutInflater.from(this)
        for ((i, line) in lrcLines.withIndex()) {
            val tv = inflater.inflate(R.layout.item_lyric_line, binding.lyricContainer, false) as TextView
            tv.text = line.text
            tv.tag = i
            binding.lyricContainer.addView(tv)
        }
    }

    private fun highlightLyricLine(index: Int) {
        val container = binding.lyricContainer
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is TextView) {
                val isCurrent = (child.tag as? Int) == index
                child.setTextColor(
                    ContextCompat.getColor(
                        this,
                        if (isCurrent) R.color.player_lyric_highlight
                        else R.color.player_text_tertiary
                    )
                )
                child.textSize = if (isCurrent) 15f else 13f
                child.setTypeface(null, if (isCurrent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun scrollToHighlightedLine(index: Int) {
        val container = binding.lyricContainer
        val target = container.getChildAt(index) ?: return
        val scroll = binding.lyricArea
        val y = target.top - scroll.height / 2 + target.height / 2
        scroll.smoothScrollTo(0, y.coerceAtLeast(0))
    }

    private fun currentBaseUrl(): String? {
        val address = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_ADDRESS, "")
        val port = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_PORT, "")
        if (address.isNullOrBlank() || port.isNullOrBlank()) return null
        return "http://$address:$port"
    }

    /**
     * 走完整播放流程（预检 + 拿 URL + 播放）。
     * 与 [com.cxh09.scanpenapp.MusicActivity.playSongFlow] 行为一致；这里简化为只调内部方法。
     */
    private fun playSongFlow(song: PlayableSong, allSongs: List<PlayableSong>, startIndex: Int) {
        val baseUrl = currentBaseUrl() ?: run {
            Toast.makeText(this, R.string.music_settings_no_server, Toast.LENGTH_SHORT).show()
            return
        }
        val cookie = MusicSession.cookie(this).ifBlank { null }
        PlayQueue.setQueue(allSongs, startIndex)
        loadedLrcForSongId = null
        lrcLines = emptyList()
        renderLyricLines()
        // 渲染目标歌曲信息
        binding.tvSongName.text = song.name
        binding.tvArtistName.text = song.artist
        binding.tvCoverInitial.text = song.name.firstOrNull()?.toString() ?: "♪"
        lifecycleScope.launch {
            val check = try {
                withContext(Dispatchers.IO) { MusicApi.checkMusic(baseUrl, cookie, song.id, br = 320000) }
            } catch (e: Exception) {
                CheckMusicResult(success = false, message = e.message ?: "")
            }
            if (!check.success) {
                Toast.makeText(
                    this@MusicPlayerActivity,
                    check.message.ifBlank { getString(R.string.music_play_song_unavailable) },
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val songUrl = try {
                withContext(Dispatchers.IO) { MusicApi.songUrl(baseUrl, cookie, song.id) }
            } catch (e: Exception) {
                null
            }
            val url = songUrl?.url
            if (url.isNullOrBlank()) {
                Toast.makeText(
                    this@MusicPlayerActivity,
                    R.string.music_play_song_unavailable,
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            val dur = (if (song.durationMs > 0) song.durationMs else songUrl.timeMs)
            MusicPlayer.play(url, song.name, song.artist, dur)
        }
    }

    private fun formatMs(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%d:%02d".format(m, s)
    }

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/** 简化的 [MusicApi.CheckMusicResult] 引用别名（避免重复 import 整个类）。 */
private typealias CheckMusicResult = com.cxh09.scanpenapp.music.api.MusicApi.CheckMusicResult
