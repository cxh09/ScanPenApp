package com.cxh09.scanpenapp

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Bundle
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import com.cxh09.scanpenapp.databinding.ActivitySettingsSliderBinding

/**
 * 设置 → 亮度 / 声音 的独立调节页面。
 *
 * - 通过 [EXTRA_TYPE] 区分 `brightness` / `volume`：
 *     - brightness：调整本窗口 [android.view.WindowManager.LayoutParams.screenBrightness]，
 *       并写入 [PREFS_NAME]，避免申请 `WRITE_SETTINGS` 权限。
 *     - volume：调整 [AudioManager.STREAM_MUSIC]（系统档位）。
 * - 拖动 SeekBar 实时生效；数值通过 [R.string.settings_value_percent] 渲染为百分比。
 */
class SettingsSliderActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsSliderBinding
    private lateinit var prefs: SharedPreferences
    private lateinit var audioManager: AudioManager

    /**
     * 媒体流最大档位。-1 表示当前 type 不需要音量档位（亮度模式）。
     */
    private var streamMaxVolume: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsSliderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        binding.btnBack.setOnClickListener { finish() }

        when (val type = intent.getStringExtra(EXTRA_TYPE)) {
            TYPE_BRIGHTNESS -> setupBrightness()
            TYPE_VOLUME -> setupVolume()
            else -> {
                // 未识别的 type 直接关闭，避免出现空白页。
                finish()
                return
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 进入亮度页时把保存的亮度应用到本窗口，覆盖系统值。
        if (intent.getStringExtra(EXTRA_TYPE) == TYPE_BRIGHTNESS) {
            val pct = prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS_PCT)
            applyBrightness(pct)
        }
    }

    private fun setupBrightness() {
        binding.tvSliderTitle.setText(R.string.settings_slider_brightness_title)
        val currentPct = prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS_PCT)
        renderValue(currentPct)
        binding.slider.progress = currentPct
        binding.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderValue(progress)
                if (fromUser) {
                    prefs.edit().putInt(KEY_BRIGHTNESS, progress).apply()
                    applyBrightness(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupVolume() {
        binding.tvSliderTitle.setText(R.string.settings_slider_volume_title)
        streamMaxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val currentPct = if (streamMaxVolume <= 0) 0 else (currentVolume * 100) / streamMaxVolume
        renderValue(currentPct)
        binding.slider.progress = currentPct
        binding.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderValue(progress)
                if (fromUser && streamMaxVolume > 0) {
                    val target = (progress * streamMaxVolume) / 100
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun renderValue(progress: Int) {
        binding.tvSliderValue.text =
            getString(R.string.settings_value_percent, progress)
    }

    private fun applyBrightness(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        val lp = window.attributes
        // 0f = 全暗，1f = 最亮；不写 BRIGHTNESS_OVERRIDE_NONE，避免与系统设置耦合。
        lp.screenBrightness = clamped / 100f
        window.attributes = lp
    }

    companion object {
        const val EXTRA_TYPE = "type"
        const val TYPE_BRIGHTNESS = "brightness"
        const val TYPE_VOLUME = "volume"

        private const val PREFS_NAME = "settings"
        private const val KEY_BRIGHTNESS = "brightness_percent"
        private const val DEFAULT_BRIGHTNESS_PCT = 80
    }
}
