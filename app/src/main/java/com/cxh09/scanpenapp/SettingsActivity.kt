package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Bundle
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.cxh09.scanpenapp.databinding.ActivitySettingsBinding
import com.cxh09.scanpenapp.databinding.DialogSeekbarBinding

/**
 * 设置页：
 * - 「亮度」 / 「声音」点击后弹出带 SeekBar 的弹窗，拖动实时调节。
 *     - 亮度：写入本窗口 [android.view.WindowManager.LayoutParams.screenBrightness]，并落盘。
 *     - 声音：写入 [AudioManager.STREAM_MUSIC]。
 * - 「关于本机」跳转到 [AboutDeviceActivity]。
 * - 其他项暂为占位 Toast。
 *
 * 亮度仅作用于本 Activity 窗口，避免申请 `WRITE_SETTINGS` 权限。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        binding.btnBack.setOnClickListener { finish() }

        val items = listOf(
            SettingItem(
                R.drawable.ic_settings,
                getString(R.string.settings_item_wifi),
                SettingType.WIFI
            ),
            SettingItem(
                R.drawable.ic_settings,
                getString(R.string.settings_item_bluetooth),
                SettingType.BLUETOOTH
            ),
            SettingItem(
                R.drawable.ic_settings,
                getString(R.string.settings_item_brightness),
                SettingType.BRIGHTNESS
            ),
            SettingItem(
                R.drawable.ic_settings,
                getString(R.string.settings_item_volume),
                SettingType.VOLUME
            ),
            SettingItem(
                R.drawable.ic_settings,
                getString(R.string.settings_item_language),
                SettingType.LANGUAGE
            ),
            SettingItem(
                R.drawable.ic_settings,
                getString(R.string.settings_item_about),
                SettingType.ABOUT
            )
        )

        binding.rvSettings.layoutManager = LinearLayoutManager(this)
        binding.rvSettings.adapter = SettingsAdapter(items) { item ->
            when (item.type) {
                SettingType.BRIGHTNESS -> showBrightnessDialog()
                SettingType.VOLUME -> showVolumeDialog()
                SettingType.ABOUT -> startActivity(
                    Intent(this, AboutDeviceActivity::class.java)
                )
                else -> Toast.makeText(
                    this,
                    getString(R.string.settings_item_placeholder),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 进入页面时恢复上次的亮度值，确保覆盖系统设置变化。
        val brightnessPct = prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS_PCT)
        applyBrightness(brightnessPct)
    }

    private fun showBrightnessDialog() {
        val currentPct = prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS_PCT)
        showSeekbarDialog(
            titleRes = R.string.settings_dialog_brightness_title,
            initialProgress = currentPct
        ) { progress ->
            // 拖动过程中实时写盘 + 应用到窗口，关闭后无需二次保存。
            prefs.edit().putInt(KEY_BRIGHTNESS, progress).apply()
            applyBrightness(progress)
        }
    }

    private fun showVolumeDialog() {
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentPct = if (maxVolume <= 0) 0 else (currentVolume * 100) / maxVolume
        showSeekbarDialog(
            titleRes = R.string.settings_dialog_volume_title,
            initialProgress = currentPct
        ) { progress ->
            // 媒体流的 0..100% 映射到系统音量档位。
            val target = (progress * maxVolume) / 100
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    /**
     * 通用带 SeekBar 的弹窗：标题 + 百分比 + 进度条 + 完成按钮。
     * [onProgressChanged] 仅在用户拖动时回调，初始化阶段不回调，避免误写。
     */
    private fun showSeekbarDialog(
        titleRes: Int,
        initialProgress: Int,
        onProgressChanged: (Int) -> Unit
    ) {
        val dialogBinding = DialogSeekbarBinding.inflate(layoutInflater)
        dialogBinding.seekbar.progress = initialProgress.coerceIn(0, 100)
        renderValue(dialogBinding, dialogBinding.seekbar.progress)
        dialogBinding.seekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderValue(dialogBinding, progress)
                if (fromUser) onProgressChanged(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_Dialog)
            .setTitle(titleRes)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.settings_dialog_ok, null)
            .show()
    }

    private fun renderValue(dialogBinding: DialogSeekbarBinding, progress: Int) {
        dialogBinding.tvSeekbarValue.text =
            getString(R.string.settings_value_percent, progress)
    }

    private fun applyBrightness(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val lp = window.attributes
        // 0f = 全暗，1f = 最亮；不写 BRIGHTNESS_OVERRIDE_NONE，避免与系统设置耦合。
        lp.screenBrightness = clamped / 100f
        window.attributes = lp
    }

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_BRIGHTNESS = "brightness_percent"
        const val DEFAULT_BRIGHTNESS_PCT = 80
    }
}
