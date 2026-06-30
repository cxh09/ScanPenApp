package com.cxh09.scanpenapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        binding.btnAskAi.setOnClickListener {
            startActivity(Intent(this, AskAiActivity::class.java))
        }

        binding.btnMicroChat.setOnClickListener {
            startActivity(Intent(this, MicroChatActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnCamera.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        binding.btnRecorder.setOnClickListener {
            startActivity(Intent(this, RecorderActivity::class.java))
        }

        binding.btnYinyue.setOnClickListener {
            startActivity(Intent(this, MusicActivity::class.java))
        }

        binding.btnNote.setOnClickListener {
            startActivity(Intent(this, NoteListActivity::class.java))
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
}
