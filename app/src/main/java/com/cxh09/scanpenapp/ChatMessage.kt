package com.cxh09.scanpenapp

/**
 * 微聊右侧聊天记录条目。
 *
 * [isMine] 控制气泡的左右朝向，[sender] 用于在他人消息上方显示昵称，
 * [content] 是文本内容；如需扩展图片/文件/语音，再引入 [type] 字段。
 */
data class ChatMessage(
    val id: Long,
    val sender: String,
    val content: String,
    val isMine: Boolean,
    val time: String? = null,
    val avatarColor: Int = 0xFF7DB9E8.toInt(),
)
