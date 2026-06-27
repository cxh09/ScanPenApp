package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cxh09.scanpenapp.ai.AiSettingsActivity
import com.cxh09.scanpenapp.databinding.ActivitySettingsBinding

/**
 * 设置页：
 * - 3 列网格布局，每行展示 3 个设置项（适配词典笔横屏窄长比例）。
 * - 「亮度」 / 「音量」点击后跳转到 [SettingsSliderActivity]（带 type 区分）。
 * - 「API 设置」跳转到 [AiSettingsActivity]。
 * - 「关于本机」跳转到 [AboutDeviceActivity]。
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
                R.drawable.ic_brightness,
                getString(R.string.settings_item_brightness),
                SettingType.BRIGHTNESS
            ),
            SettingItem(
                R.drawable.ic_volume,
                getString(R.string.settings_item_volume),
                SettingType.VOLUME
            ),
            SettingItem(
                R.drawable.ic_key,
                getString(R.string.settings_item_api),
                SettingType.API_SETTINGS
            ),
            SettingItem(
                R.drawable.ic_info,
                getString(R.string.settings_item_about),
                SettingType.ABOUT
            )
        )

        binding.rvSettings.layoutManager = GridLayoutManager(this, GRID_SPAN_COUNT)
        binding.rvSettings.addItemDecoration(GridSpacingDecoration(GRID_GAP_DP))
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
                SettingType.API_SETTINGS -> startActivity(
                    Intent(this, AiSettingsActivity::class.java)
                )
                SettingType.ABOUT -> startActivity(
                    Intent(this, AboutDeviceActivity::class.java)
                )
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
        const val GRID_SPAN_COUNT = 3
        const val GRID_GAP_DP = 8
    }
}

/**
 * 网格项之间等距分布的间距装饰器。
 * 通过把间距按列数 / 行数等分后下放到每个 item 的 left/top/right/bottom，
 * 避免首列与最末列贴边、首行与最末行贴边。
 */
private class GridSpacingDecoration(
    private val gapDp: Int,
) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State,
    ) {
        val gap = (gapDp * parent.resources.displayMetrics.density).toInt()
        val spanCount = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 1
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return

        val column = position % spanCount
        val row = position / spanCount
        outRect.left = gap - column * gap / spanCount
        outRect.right = (column + 1) * gap / spanCount
        outRect.top = if (row == 0) 0 else gap
        outRect.bottom = 0
    }
}
