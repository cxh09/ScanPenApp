package com.cxh09.scanpenapp

/**
 * 侧边栏历史对话项。
 *
 * 仅承载渲染所需的最少字段，避免在低端设备上持有不必要的数据。
 *
 * @param createdAt 创建时间戳（毫秒），用于排序和关联持久化会话
 */
data class ChatHistoryItem(
    val id: Long,
    val title: String,
    val createdAt: Long = 0L,
    val isStarred: Boolean = false,
)
