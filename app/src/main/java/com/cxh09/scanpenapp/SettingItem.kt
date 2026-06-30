package com.cxh09.scanpenapp

import androidx.annotation.DrawableRes

/**
 * 设置项的业务类型。点击后由 [SettingsActivity] 根据 [SettingItem.type] 分发到不同处理。
 *
 * - [BRIGHTNESS] / [VOLUME]: 跳转到 [SettingsSliderActivity]，带 type 区分。
 * - [API_SETTINGS]: 跳转到 [com.cxh09.scanpenapp.ai.AiSettingsActivity]。
 * - [ABOUT]: 跳转到 [AboutDeviceActivity]。
 */
enum class SettingType {
    BRIGHTNESS,
    VOLUME,
    API_SETTINGS,
    ABOUT
}

data class SettingItem(
    @field:DrawableRes val iconRes: Int,
    val title: String,
    val type: SettingType
)
