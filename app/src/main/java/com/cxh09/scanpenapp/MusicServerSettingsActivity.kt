package com.cxh09.scanpenapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivityMusicServerSettingsBinding

/**
 * 云音乐 → 服务器设置页。
 *
 * - 填写并保存「服务器地址」与「端口」到 [PREFS_NAME]（SharedPreferences）。
 * - 后续接入真实音乐接口时，[MusicActivity] / 数据层从 [PREFS_NAME] 读取
 *   [KEY_SERVER_ADDRESS] / [KEY_SERVER_PORT] 即可。
 */
class MusicServerSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMusicServerSettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMusicServerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // 进入页面时回显已保存的值，便于修改
        binding.etMusicServerAddress.setText(prefs.getString(KEY_SERVER_ADDRESS, ""))
        binding.etMusicServerPort.setText(prefs.getString(KEY_SERVER_PORT, ""))

        binding.btnMusicServerBack.setOnClickListener { finish() }
        binding.btnMusicServerSave.setOnClickListener { onSaveClicked() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    private fun onSaveClicked() {
        val address = binding.etMusicServerAddress.text?.toString()?.trim().orEmpty()
        val port = binding.etMusicServerPort.text?.toString()?.trim().orEmpty()

        if (address.isEmpty()) {
            binding.etMusicServerAddress.error = getString(R.string.music_settings_address_required)
            binding.etMusicServerAddress.requestFocus()
            return
        }
        if (port.isEmpty()) {
            binding.etMusicServerPort.error = getString(R.string.music_settings_port_required)
            binding.etMusicServerPort.requestFocus()
            return
        }
        val portInt = port.toIntOrNull()
        if (portInt == null || portInt !in 1..65535) {
            binding.etMusicServerPort.error = getString(R.string.music_settings_port_invalid)
            binding.etMusicServerPort.requestFocus()
            return
        }

        prefs.edit()
            .putString(KEY_SERVER_ADDRESS, address)
            .putString(KEY_SERVER_PORT, port)
            .apply()

        Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show()
        finish()
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
        const val PREFS_NAME = "music_server"
        const val KEY_SERVER_ADDRESS = "server_address"
        const val KEY_SERVER_PORT = "server_port"
    }
}
