package com.cxh09.scanpenapp

/**
 * 微聊左侧联系人 / 会话列表项。
 *
 * 仅承载渲染所需的最小字段，避免在低端设备上持有不必要的数据。
 * 头像用 [avatarColor] 着色，不引入图片资源，降低 APK 体积。
 */
data class ChatContact(
    val id: Long,
    val name: String,
    val preview: String,
    val time: String,
    val isActive: Boolean = false,
    val avatarColor: Int = 0xFF7DB9E8.toInt(),
)
