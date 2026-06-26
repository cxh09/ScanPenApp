package com.cxh09.scanpenapp

import androidx.annotation.DrawableRes

/**
 * 设置项的业务类型。点击后由 [SettingsActivity] 根据 [SettingItem.type] 分发到不同处理。
 *
 * - [BRIGHTNESS] / [VOLUME]: 弹出带 SeekBar 的弹窗，可拖动调节。
 * - 其他: 暂为占位。
 */
enum class SettingType {
    WIFI,
    BLUETOOTH,
    BRIGHTNESS,
    VOLUME,
    LANGUAGE,
    AI_SERVICE,
    ABOUT
}

data class SettingItem(
    @DrawableRes val iconRes: Int,
    val title: String,
    val type: SettingType
)
