package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cxh09.scanpenapp.databinding.ActivityMusicBinding
import com.cxh09.scanpenapp.music.MusicDailyAdapter
import com.cxh09.scanpenapp.music.MusicPlaylist
import com.cxh09.scanpenapp.music.MusicPlaylistAdapter
import com.cxh09.scanpenapp.music.api.MusicApi
import com.cxh09.scanpenapp.music.api.MusicSession
import com.cxh09.scanpenapp.music.player.MusicPlayer
import com.cxh09.scanpenapp.music.player.PlayQueue
import com.cxh09.scanpenapp.music.player.PlayableSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云音乐页
 *
 * 布局（仿照录音机页面的左右分栏）：
 * - 左侧 220dp 侧栏：返回 + 账号登录 / 登出 + 设置 + 我的歌单列表
 * - 右侧主区：搜索栏 + 日推列表（每日推荐）+ 底部 56dp mini player（播放时常驻）
 *
 * 数据来源：
 * - 搜索：点击搜索按钮打开 [MusicSearchActivity] 做实际搜索（2 列网格结果）
 * - 日推：已登录时调 [MusicApi.recommendSongs]；未登录时调 [MusicApi.personalized]
 * - 歌单：已登录时调 [MusicApi.userPlaylist]；未登录时为空
 * - 登录：[MusicLoginActivity] 完成扫码后写入 [MusicSession]，本页面据此更新顶栏
 *
 * 播放：
 * - 点击日推 / 搜索结果 / 歌单内歌曲 → 走 [playSongFlow]（预检 → 拿 URL → 播放）
 * - mini player 常驻底部，[MusicPlayer.addListener] 同步状态
 * - 点 mini 中间区域 → 启动 [MusicPlayerActivity] 全屏播放页
 */
class MusicActivity : AppCompatActivity(), MusicPlayer.Listener {

    private lateinit var binding: ActivityMusicBinding
    private lateinit var playlistAdapter: MusicPlaylistAdapter
    private lateinit var dailyAdapter: MusicDailyAdapter
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        prefs = getSharedPreferences(
            MusicServerSettingsActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )

        setupList()
        showLoading()

        binding.btnMusicBack.setOnClickListener { finish() }
        binding.btnAccountLogin.setOnClickListener { onLoginClicked() }
        binding.btnMusicSettings.setOnClickListener {
            startActivity(Intent(this, MusicServerSettingsActivity::class.java))
        }
        // 顶栏搜索栏：点搜索按钮 / 输入回车 → 打开 [MusicSearchActivity] 搜索页
        binding.btnMusicSearch.setOnClickListener { openSearchActivity() }
        binding.etMusicSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                openSearchActivity()
                true
            } else {
                false
            }
        }

        // mini player 控件
        binding.miniPlayer.setOnClickListener { openPlayerActivity() }
        binding.ivMiniCover.setOnClickListener { openPlayerActivity() }
        binding.btnMiniPlayPause.setOnClickListener {
            when (MusicPlayer.state()) {
                MusicPlayer.State.PLAYING -> MusicPlayer.pause()
                MusicPlayer.State.PAUSED -> MusicPlayer.resume()
                else -> { /* IDLE / PREPARING no-op */ }
            }
        }
        binding.btnMiniNext.setOnClickListener { onMiniNextClicked() }

        // 处理「从 MusicSearchActivity 透传过来要求播放」的 intent
        handlePlayIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        MusicPlayer.addListener(this)
        // onStart 时同步一次 mini player 状态（避免从其他页面返回时状态错位）
        applyMiniPlayerState()
    }

    override fun onStop() {
        MusicPlayer.removeListener(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        applyLoginState()
        applySubcountTitle()
        // 每次进入页面都重新拉数据
        loadData()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePlayIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /**
     * 处理从 [MusicSearchActivity] 透传的播放请求：
     * - EXTRA_PLAY_SONG_ID：当前要播放的歌曲 ID
     * - EXTRA_PLAY_ALL_IDS：完整队列的所有歌曲 ID（按顺序）
     * 用 MusicApi.songDetail 拉详情构造 [PlayableSong] 列表后走 [playSongFlow]。
     */
    private fun handlePlayIntent(intent: android.content.Intent) {
        val songId = intent.getStringExtra(MusicSearchActivity.EXTRA_PLAY_SONG_ID) ?: return
        val allIds = intent.getStringArrayListExtra(MusicSearchActivity.EXTRA_PLAY_ALL_IDS) ?: arrayListOf(songId)
        if (!hasServerConfig()) {
            Toast.makeText(this, R.string.music_settings_no_server, Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = "http://${requireAddress()}:${requirePort()}"
        val cookie = MusicSession.cookie(this).ifBlank { null }
        lifecycleScope.launch {
            val details = try {
                withContext(Dispatchers.IO) { MusicApi.songDetail(baseUrl, cookie, allIds) }
            } catch (e: Exception) {
                emptyList()
            }
            if (details.isEmpty() || isFinishing || isDestroyed) {
                Toast.makeText(this@MusicActivity, R.string.music_play_song_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val ordered = allIds.mapNotNull { id -> details.firstOrNull { it.id == id } }
            val song = ordered.firstOrNull { it.id == songId } ?: return@launch
            val allSongs = ordered.map { d ->
                PlayableSong(
                    id = d.id,
                    name = d.name,
                    artist = d.artists,
                    album = d.album.ifEmpty { null },
                    durationMs = d.durationMs
                )
            }
            val newIndex = allSongs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
            val playableSong = PlayableSong(
                id = song.id,
                name = song.name,
                artist = song.artists,
                album = song.album.ifEmpty { null },
                durationMs = song.durationMs
            )
            playSongFlow(playableSong, allSongs, newIndex)
            // 清理 intent extra 避免重复触发
            intent.removeExtra(MusicSearchActivity.EXTRA_PLAY_SONG_ID)
            intent.removeExtra(MusicSearchActivity.EXTRA_PLAY_ALL_IDS)
        }
    }

    // ---------------- Listener ----------------

    override fun onStateChanged(state: MusicPlayer.State) {
        applyMiniPlayerState()
    }

    override fun onPosition(positionMs: Long, durationMs: Long) {
        // mini player 不需要显示进度，由全屏播放页负责
    }

    override fun onCompletion() {
        // mini player 跟随 MusicPlayer 状态自动隐藏
        applyMiniPlayerState()
    }

    override fun onError(code: Int, message: String) {
        applyMiniPlayerState()
    }

    // ---------------- 列表 ----------------

    private fun setupList() {
        playlistAdapter = MusicPlaylistAdapter { playlist ->
            openPlaylistDetail(playlist)
        }
        binding.rvPlaylists.apply {
            layoutManager = LinearLayoutManager(this@MusicActivity)
            adapter = playlistAdapter
            setHasFixedSize(true)
        }

        dailyAdapter = MusicDailyAdapter { song ->
            playSongFlow(song, dailyAdapter.currentList, startIndex = dailyAdapter.currentList.indexOf(song).coerceAtLeast(0))
        }
        binding.rvDaily.apply {
            layoutManager = LinearLayoutManager(this@MusicActivity)
            adapter = dailyAdapter
            setHasFixedSize(true)
        }
    }

    // ---------------- 数据加载 ----------------

    private fun loadData() {
        if (!hasServerConfig()) {
            showEmpty(getString(R.string.music_settings_no_server))
            return
        }
        val baseUrl = "http://${requireAddress()}:${requirePort()}"
        if (MusicSession.isLoggedIn(this)) {
            val cookie = MusicSession.cookie(this)
            val uid = MusicSession.userId(this)
            loadDailyRecommend(baseUrl, cookie)
            loadPlaylists(baseUrl, cookie, uid)
            refreshSubcount()
        } else {
            loadDailyRecommend(baseUrl, null)
            // 未登录：歌单为空
            playlistAdapter.submit(emptyList())
            binding.tvPlaylistEmpty.visibility = View.VISIBLE
        }
    }

    /**
     * 加载每日推荐。已登录 → /recommend/songs；未登录 → /personalized。
     * 失败时回退 8 首占位 + Toast。
     */
    private fun loadDailyRecommend(baseUrl: String, cookie: String?) {
        showLoading()
        lifecycleScope.launch {
            try {
                val songs: List<PlayableSong> = withContext(Dispatchers.IO) {
                    if (!cookie.isNullOrBlank()) {
                        MusicApi.recommendSongs(baseUrl, cookie)
                    } else {
                        // 未登录：用真实 API 拿推荐歌单，但 /personalized 返回的是「歌单」而非「歌曲」；
                        // 词典笔没有完善的「歌单内歌曲」接口，所以这里先直接回退占位。
                        // 真实环境用户登录后即可看到完整日推。
                        placeholderDaily()
                    }
                }
                if (!isFinishing && !isDestroyed) {
                    if (songs.isEmpty()) {
                        dailyAdapter.submit(placeholderDaily())
                    } else {
                        dailyAdapter.submit(songs)
                    }
                    showList(true)
                }
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    dailyAdapter.submit(placeholderDaily())
                    showList(true)
                    Toast.makeText(this@MusicActivity, R.string.music_daily_load_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 加载用户歌单。
     */
    private fun loadPlaylists(baseUrl: String, cookie: String, uid: Long) {
        if (uid <= 0L) {
            playlistAdapter.submit(emptyList())
            binding.tvPlaylistEmpty.visibility = View.VISIBLE
            return
        }
        lifecycleScope.launch {
            try {
                val lists = withContext(Dispatchers.IO) {
                    MusicApi.userPlaylist(baseUrl, cookie, uid)
                }
                if (!isFinishing && !isDestroyed) {
                    playlistAdapter.submit(lists)
                    binding.tvPlaylistEmpty.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: Exception) {
                if (!isFinishing && !isDestroyed) {
                    playlistAdapter.submit(emptyList())
                    binding.tvPlaylistEmpty.visibility = View.VISIBLE
                    Toast.makeText(this@MusicActivity, R.string.music_playlist_load_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 占位日推：未登录且 /personalized 拿不到时使用。
     * 实际拿真实 API；这里只在「未登录 + /personalized 失败」双重降级时使用。
     */
    private fun placeholderDaily(): List<PlayableSong> = listOf(
        PlayableSong("1", "晴天", "周杰伦", "叶惠美", 269_000),
        PlayableSong("2", "稻香", "周杰伦", "魔杰座", 223_000),
        PlayableSong("3", "夜曲", "周杰伦", "十一月的萧邦", 226_000),
        PlayableSong("4", "七里香", "周杰伦", "七里香", 299_000),
        PlayableSong("5", "青花瓷", "周杰伦", "我很忙", 238_000),
        PlayableSong("6", "听妈妈的话", "周杰伦", "依然范特西", 263_000),
        PlayableSong("7", "蒲公英的约定", "周杰伦", "我很忙", 248_000),
        PlayableSong("8", "彩虹", "周杰伦", "魔杰座", 254_000)
    )

    private fun showLoading() {
        binding.pbMusicLoading.visibility = View.VISIBLE
        binding.rvDaily.visibility = View.GONE
        binding.tvDailyEmpty.visibility = View.GONE
    }

    private fun showList(show: Boolean) {
        binding.pbMusicLoading.visibility = View.GONE
        if (show) {
            binding.rvDaily.visibility = View.VISIBLE
            binding.tvDailyEmpty.visibility = View.GONE
        } else {
            binding.rvDaily.visibility = View.GONE
            binding.tvDailyEmpty.text = getString(R.string.music_daily_empty)
            binding.tvDailyEmpty.visibility = View.VISIBLE
        }
    }

    private fun showEmpty(text: String) {
        binding.pbMusicLoading.visibility = View.GONE
        binding.rvDaily.visibility = View.GONE
        binding.tvDailyEmpty.text = text
        binding.tvDailyEmpty.visibility = View.VISIBLE
    }

    // ---------------- 搜索入口 ----------------

    private fun openSearchActivity() {
        hideIme()
        val keyword = binding.etMusicSearch.text?.toString()?.trim().orEmpty()
        val intent = Intent(this, MusicSearchActivity::class.java)
        if (keyword.isNotEmpty()) {
            intent.putExtra(MusicSearchActivity.EXTRA_KEYWORD, keyword)
        }
        startActivity(intent)
    }

    private fun openPlaylistDetail(playlist: MusicPlaylist) {
        val intent = Intent(this, MusicPlaylistDetailActivity::class.java)
        intent.putExtra(MusicPlaylistDetailActivity.EXTRA_PLAYLIST_NAME, playlist.name)
        startActivity(intent)
    }

    private fun openPlayerActivity() {
        if (MusicPlayer.state() == MusicPlayer.State.IDLE) return
        startActivity(Intent(this, MusicPlayerActivity::class.java))
    }

    private fun onMiniNextClicked() {
        val newSong = PlayQueue.next() ?: return
        val all = PlayQueue.snapshot()
        val newIndex = all.indexOf(newSong).coerceAtLeast(0)
        playSongFlow(newSong, all, newIndex)
    }

    private fun hideIme() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etMusicSearch.windowToken, 0)
    }

    // ---------------- 播放流程 ----------------

    /**
     * 统一播放入口。
     * 流程：checkMusic（可选预检）→ songUrl → PlayQueue.setQueue → MusicPlayer.play。
     * 任何一步失败都 Toast 提示，**不**进入播放。
     */
    fun playSongFlow(song: PlayableSong, allSongs: List<PlayableSong>, startIndex: Int = allSongs.indexOf(song).coerceAtLeast(0)) {
        if (!hasServerConfig()) {
            Toast.makeText(this, R.string.music_settings_no_server, Toast.LENGTH_SHORT).show()
            return
        }
        val baseUrl = "http://${requireAddress()}:${requirePort()}"
        val cookie = MusicSession.cookie(this).ifBlank { null }

        // 1. 先重置队列并显示新曲目（给用户即时反馈）
        PlayQueue.setQueue(allSongs, startIndex)
        applyMiniPlayerState(song.name, song.artist)

        lifecycleScope.launch {
            // step 1: 预检
            val check = try {
                withContext(Dispatchers.IO) { MusicApi.checkMusic(baseUrl, cookie, song.id, br = 320000) }
            } catch (e: Exception) {
                MusicApi.CheckMusicResult(success = false, message = e.message.orEmpty())
            }
            if (!check.success) {
                val msg = if (check.message.isNotBlank()) check.message else getString(R.string.music_play_song_unavailable)
                Toast.makeText(this@MusicActivity, msg, Toast.LENGTH_SHORT).show()
                return@launch
            }

            // step 2: 拿 URL
            val urlInfo = try {
                withContext(Dispatchers.IO) { MusicApi.songUrl(baseUrl, cookie, song.id) }
            } catch (e: Exception) {
                null
            }
            val playUrl = urlInfo?.url
            if (playUrl.isNullOrBlank()) {
                Toast.makeText(this@MusicActivity, R.string.music_play_song_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }

            // step 3: 播放
            val dur = (if (song.durationMs > 0) song.durationMs else urlInfo.timeMs)
            MusicPlayer.play(playUrl, song.name, song.artist, dur)
        }
    }

    // ---------------- 登录 ----------------

    private fun onLoginClicked() {
        if (MusicSession.isLoggedIn(this)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.music_logout_title)
                .setMessage(R.string.music_logout_message)
                .setPositiveButton(R.string.music_logout_confirm) { _, _ -> doLogout() }
                .setNegativeButton(R.string.music_logout_cancel, null)
                .show()
        } else {
            startActivity(Intent(this, MusicLoginActivity::class.java))
        }
    }

    private fun doLogout() {
        val cookie = MusicSession.cookie(this)
        val baseUrl = if (hasServerConfig()) {
            "http://${requireAddress()}:${requirePort()}"
        } else {
            ""
        }
        lifecycleScope.launch {
            var serverError: String? = null
            if (baseUrl.isNotBlank() && cookie.isNotBlank()) {
                try {
                    withContext(Dispatchers.IO) { MusicApi.logout(baseUrl, cookie) }
                } catch (e: Exception) {
                    serverError = e.message
                }
            }
            MusicSession.clear(this@MusicActivity)
            applyLoginState()
            applyMiniPlayerState()
            if (serverError != null) {
                Toast.makeText(
                    this@MusicActivity,
                    "已退出本地登录（服务端退出失败：$serverError）",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@MusicActivity, R.string.music_logout_success, Toast.LENGTH_SHORT).show()
            }
            // 退出登录后刷新数据
            loadData()
        }
    }

    // ---------------- 服务端地址 ----------------

    private fun hasServerConfig(): Boolean {
        val address = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_ADDRESS, "")
        val port = prefs.getString(MusicServerSettingsActivity.KEY_SERVER_PORT, "")
        return !address.isNullOrBlank() && !port.isNullOrBlank()
    }

    private fun requireAddress(): String =
        prefs.getString(MusicServerSettingsActivity.KEY_SERVER_ADDRESS, "").orEmpty()

    private fun requirePort(): String =
        prefs.getString(MusicServerSettingsActivity.KEY_SERVER_PORT, "").orEmpty()

    private fun applyLoginState() {
        val logged = MusicSession.isLoggedIn(this)
        if (logged) {
            val nick = MusicSession.nickname(this)
            binding.tvAccountLabel.text = if (nick.isNotBlank()) {
                nick
            } else {
                getString(R.string.music_logged_in)
            }
            val avatarUrl = MusicSession.avatarUrl(this)
            if (avatarUrl.isNotBlank()) {
                loadAvatarInto(avatarUrl, binding.ivAccount)
            } else {
                setDefaultAccountIcon()
            }
        } else {
            binding.tvAccountLabel.text = getString(R.string.music_account_login)
            setDefaultAccountIcon()
        }
    }

    private fun setDefaultAccountIcon() {
        binding.ivAccount.setImageResource(R.drawable.ic_account)
        binding.ivAccount.imageTintList =
            android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(this, R.color.recorder_text_primary)
            )
        binding.ivAccount.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        binding.ivAccount.background = null
    }

    private fun loadAvatarInto(url: String, target: android.widget.ImageView) {
        avatarCache[url]?.let { bmp ->
            applyCircularBitmap(target, bmp)
            return
        }
        target.setImageDrawable(null)
        target.imageTintList = null
        target.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { downloadBitmap(url) }
            if (bmp != null) {
                avatarCache.put(url, bmp)
                if (!isFinishing && !isDestroyed) {
                    applyCircularBitmap(target, bmp)
                }
            } else {
                if (!isFinishing && !isDestroyed) setDefaultAccountIcon()
            }
        }
    }

    private fun applyCircularBitmap(target: android.widget.ImageView, bmp: android.graphics.Bitmap) {
        val drawable = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(
            resources, bmp
        )
        drawable.isCircular = true
        target.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
        target.imageTintList = null
        target.background = null
        target.setImageDrawable(drawable)
    }

    private fun downloadBitmap(urlStr: String): android.graphics.Bitmap? {
        return try {
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "ScanPenApp/1.0 (Android)")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return null
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private val avatarCache = object : androidx.collection.LruCache<String, android.graphics.Bitmap>(4) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = 1
    }

    // ---------------- 用户统计 ----------------

    private fun applySubcountTitle() {
        if (MusicSession.isLoggedIn(this)) {
            val n = MusicSession.totalPlaylist(this)
            binding.tvPlaylistTitle.text = if (n > 0) {
                getString(R.string.music_playlist_count_fmt, n)
            } else {
                getString(R.string.music_playlist)
            }
        } else {
            binding.tvPlaylistTitle.text = getString(R.string.music_playlist)
        }
    }

    private fun refreshSubcount() {
        if (!hasServerConfig()) return
        val cookie = MusicSession.cookie(this)
        if (cookie.isBlank()) return
        val baseUrl = "http://${requireAddress()}:${requirePort()}"
        lifecycleScope.launch {
            try {
                val sub = withContext(Dispatchers.IO) {
                    MusicApi.userSubcount(baseUrl, cookie)
                }
                MusicSession.saveSubcount(this@MusicActivity, sub)
                if (!isFinishing && !isDestroyed) applySubcountTitle()
            } catch (_: Exception) {
            }
        }
    }

    // ---------------- mini player 状态同步 ----------------

    /**
     * 把 [MusicPlayer] 的当前状态投影到 mini player：
     * - IDLE → 隐藏
     * - 其他 → 显示当前歌名 / 歌手 + 正确图标
     */
    private fun applyMiniPlayerState(overrideTitle: String? = null, overrideArtist: String? = null) {
        val s = MusicPlayer.state()
        if (s == MusicPlayer.State.IDLE) {
            binding.miniPlayer.visibility = View.GONE
            return
        }
        binding.miniPlayer.visibility = View.VISIBLE
        binding.tvMiniTitle.text = overrideTitle ?: MusicPlayer.currentTitle()
        binding.tvMiniArtist.text = overrideArtist ?: MusicPlayer.currentArtist()
        val iconRes = if (s == MusicPlayer.State.PLAYING) R.drawable.ic_player_pause else R.drawable.ic_player_play
        binding.btnMiniPlayPause.setImageResource(iconRes)
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
}
