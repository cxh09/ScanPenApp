package com.cxh09.scanpenapp.ai

/**
 * 单条消息记录，用于持久化存储。
 *
 * @param role 消息角色：「user」或「assistant」
 * @param content 消息文本内容
 */
data class MessageRecord(
    val role: String,
    val content: String,
)

/**
 * 完整对话会话，用于本地 JSON 持久化。
 *
 * @param id 唯一标识（使用 [System.currentTimeMillis] 生成）
 * @param title 会话标题（创建时的格式化时间）
 * @param createdAt 创建时间戳（毫秒）
 * @param messages 消息列表
 */
data class Conversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val messages: List<MessageRecord> = emptyList(),
)
