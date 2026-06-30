package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.cxh09.scanpenapp.databinding.ActivityMusicSearchBinding
import com.cxh09.scanpenapp.music.MusicSearchAdapter
import com.cxh09.scanpenapp.music.api.MusicApi
import com.cxh09.scanpenapp.music.api.MusicApiException
import com.cxh09.scanpenapp.music.api.MusicSession
import com.cxh09.scanpenapp.music.player.PlayableSong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云音乐 → 搜索结果页（2 列网格）。
 *
 * 入口：MusicActivity 顶栏的搜索按钮（带 intent extra `EXTRA_KEYWORD` 可选）。
 *
 * 布局：
 *  - 顶部栏 48dp：返回 + 搜索输入 + 搜索按钮
 *  - 主体：2 列网格 RecyclerView
 *
 * 点击搜索结果 → 走 [MusicActivity.playSongFlow]（预检 → 拿 URL → 播放）。
 *
 * 全部网络调用走 [Dispatchers.IO]；UI 切换回 [Dispatchers.Main]。
 */
class MusicSearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicSearchBinding
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var adapter: MusicSearchAdapter

    private var searchJob: Job? = null
    private var stopped: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        prefs = getSharedPreferences(
            MusicServerSettingsActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        setupList()

        binding.btnMusicSearchBack.setOnClickListener { finish() }
        binding.btnMusicSearchGo.setOnClickListener { triggerSearch() }
        binding.etMusicSearchKeyword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch()
                true
            } else {
                false
            }
        }

        val prefill = intent.getStringExtra(EXTRA_KEYWORD)?.trim().orEmpty()
        if (prefill.isNotEmpty()) {
            binding.etMusicSearchKeyword.setText(prefill)
            triggerSearch()
        } else {
            setEmptyText(getString(R.string.music_search_hint))
        }
    }

    override fun onDestroy() {
        stopped = true
        searchJob?.cancel()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    private fun setupList() {
        adapter = MusicSearchAdapter { song ->
            // 走统一播放流程（MusicActivity 实例必须存在；这里通过 ApplicationContext 启动
            // MusicActivity 并把数据塞到 MusicActivity 的 playSongFlow）
            launchMusicActivityPlay(song, adapter.currentList)
        }
        binding.rvMusicSearchResults.apply {
            layoutManager = GridLayoutManager(this@MusicSearchActivity, 2)
            adapter = this@MusicSearchActivity.adapter
            setHasFixedSize(false)
        }
    }

    /**
     * 启动 [MusicActivity] 走 [MusicActivity.playSongFlow] 流程。
     * 由于当前 Activity 是 [MusicSearchActivity]，需要先 startActivity 回到 [MusicActivity]
     * 并通过 intent extra 告知要播放的歌曲。
     */
    private fun launchMusicActivityPlay(song: PlayableSong, all: List<PlayableSong>) {
        // 简化方案：把 first song id 透传过去，由 MusicActivity 自行决定如何走流程
        // （本期实现：先 finish() 自己 + 走 [MusicPlayer] 直接播放，不依赖 MusicActivity 实例）
        // 因为 MusicActivity 是单实例 / 由用户主动返回的场景，直接调 MusicPlayer.play
        // 会丢失「构造完整队列」的能力，因此这里用 intent 把第一首 ID 传给 MusicActivity。
        val intent = Intent().apply {
            setClass(this@MusicSearchActivity, MusicActivity::class.java)
            putExtra(EXTRA_PLAY_SONG_ID, song.id)
            putExtra(EXTRA_PLAY_ALL_IDS, ArrayList(all.map { it.id }))
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
        finish()
    }

    // ---------------- 搜索 ----------------

    private fun triggerSearch() {
        val keyword = binding.etMusicSearchKeyword.text?.toString()?.trim().orEmpty()
        if (keyword.isEmpty()) {
            Toast.makeText(this, R.string.music_search_empty, Toast.LENGTH_SHORT).show()
            binding.etMusicSearchKeyword.requestFocus()
            return
        }
        val address = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_ADDRESS, "")
        val port = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_PORT, "")
        if (address.isNullOrBlank() || port.isNullOrBlank()) {
            Toast.makeText(this, R.string.music_settings_no_server, Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = "http://$address:$port"
        val cookie = MusicSession.cookie(this).ifBlank { null }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etMusicSearchKeyword.windowToken, 0)

        searchJob?.cancel()
        adapter.submit(emptyList())
        showProgress()

        searchJob = lifecycleScope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    MusicApi.search(baseUrl, keyword, cookie = cookie)
                }
                if (stopped) return@launch
                adapter.submit(results)
                if (results.isEmpty()) {
                    showEmpty(getString(R.string.music_search_no_results))
                } else {
                    showList()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: MusicApiException) {
                showEmpty(e.message ?: getString(R.string.music_search_fail))
            } catch (_: Exception) {
                showEmpty(getString(R.string.music_search_fail))
            }
        }
    }

    private fun showProgress() {
        binding.rvMusicSearchResults.visibility = View.GONE
        binding.tvMusicSearchEmpty.visibility = View.GONE
        binding.pbMusicSearch.visibility = View.VISIBLE
    }

    private fun showList() {
        binding.pbMusicSearch.visibility = View.GONE
        binding.tvMusicSearchEmpty.visibility = View.GONE
        binding.rvMusicSearchResults.visibility = View.VISIBLE
    }

    private fun showEmpty(text: String) {
        binding.pbMusicSearch.visibility = View.GONE
        binding.rvMusicSearchResults.visibility = View.GONE
        binding.tvMusicSearchEmpty.text = text
        binding.tvMusicSearchEmpty.visibility = View.VISIBLE
    }

    private fun setEmptyText(text: String) {
        binding.tvMusicSearchEmpty.text = text
    }

    // ---------------- 全屏 ----------------

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_KEYWORD = "extra_keyword"
        const val EXTRA_PLAY_SONG_ID = "extra_play_song_id"
        const val EXTRA_PLAY_ALL_IDS = "extra_play_all_ids"
    }
}
