package com.cxh09.scanpenapp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivityMusicPlaylistDetailBinding

/**
 * 歌单详情页（占位壳）。
 *
 * - 顶栏左返回 + 中间歌单名（intent extra [EXTRA_PLAYLIST_NAME]）+ 右侧空占位
 * - 主体居中显示「歌单详情暂未对接 / 正在播日推」+ 「播日推」按钮
 * - 「播日推」点击 → 调 [MusicActivity.playSongFlow] 拿日推第一首起播，`finish()` 回到主页
 *
 * 真实接入 `[/playlist/detail]` 后，这里改为显示歌单内歌曲 RecyclerView，逻辑同
 * [com.cxh09.scanpenapp.MusicActivity] 的日推列表。
 */
class MusicPlaylistDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicPlaylistDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicPlaylistDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        binding.tvPlaylistDetailName.text =
            intent.getStringExtra(EXTRA_PLAYLIST_NAME)?.takeIf { it.isNotBlank() }
                ?: getString(R.string.music_playlist_detail_title)

        binding.btnPlaylistDetailBack.setOnClickListener { finish() }
        binding.btnPlaylistDetailPlayDaily.setOnClickListener {
            // 直接调 MusicActivity 的播放流程；MusicActivity 未启动时先 startActivity
            val intent = Intent().apply { setClass(this@MusicPlaylistDetailActivity, MusicActivity::class.java) }
            startActivity(intent)
            Toast.makeText(
                this,
                R.string.music_playlist_detail_placeholder,
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        const val EXTRA_PLAYLIST_NAME = "extra_playlist_name"
    }
}
