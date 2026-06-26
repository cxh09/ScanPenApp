package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.cxh09.scanpenapp.ai.AiSettingsActivity
import com.cxh09.scanpenapp.databinding.ActivitySettingsBinding

/**
 * 设置页：
 * - 「亮度」 / 「声音」点击后跳转到 [SettingsSliderActivity]（带 type 区分）。
 * - 「关于本机」跳转到 [AboutDeviceActivity]。
 * - 其他项暂为占位 Toast。
 *
 * 本 Activity 自身仍会在 [onResume] 恢复保存的窗口亮度，确保从亮度页返回后能保持一致。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
                R.drawable.ic_key,
                getString(R.string.ai_settings_models_title),
                SettingType.AI_SERVICE
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
                SettingType.BRIGHTNESS -> startActivity(
                    Intent(this, SettingsSliderActivity::class.java)
                        .putExtra(
                            SettingsSliderActivity.EXTRA_TYPE,
                            SettingsSliderActivity.TYPE_BRIGHTNESS
                        )
                )
                SettingType.VOLUME -> startActivity(
                    Intent(this, SettingsSliderActivity::class.java)
                        .putExtra(
                            SettingsSliderActivity.EXTRA_TYPE,
                            SettingsSliderActivity.TYPE_VOLUME
                        )
                )
                SettingType.ABOUT -> startActivity(
                    Intent(this, AboutDeviceActivity::class.java)
                )
                SettingType.AI_SERVICE -> startActivity(
                    Intent(this, AiSettingsActivity::class.java)
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
        // 进入页面时恢复上次的窗口亮度，覆盖系统设置变化。
        val brightnessPct = prefs.getInt(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS_PCT)
        applyBrightness(brightnessPct)
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
