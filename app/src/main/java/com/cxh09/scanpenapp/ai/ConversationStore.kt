package com.cxh09.scanpenapp.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 对话持久化存储。
 *
 * 每个会话以独立 JSON 文件存储在 `filesDir/conversations/` 目录下，
 * 文件名为 `{conversationId}.json`。使用平台自带 [org.json] 零依赖序列化。
 *
 * 所有方法均为同步 IO，调用方须在 [kotlinx.coroutines.Dispatchers.IO] 中执行。
 */
class ConversationStore(context: Context) {

    private val dir: File = File(context.filesDir, "conversations").also {
        if (!it.exists()) it.mkdirs()
    }

    /** 保存（新增或覆盖）一个会话。 */
    fun saveConversation(conv: Conversation) {
        val json = JSONObject().apply {
            put("id", conv.id)
            put("title", conv.title)
            put("createdAt", conv.createdAt)
            put("messages", JSONArray().apply {
                conv.messages.forEach { msg ->
                    put(JSONObject().apply {
                        put("role", msg.role)
                        put("content", msg.content)
                        // reasoning 为空时不写入字段，文件体积更小；parse 端走 optString 兼容
                        msg.reasoning?.takeIf { it.isNotEmpty() }?.let { put("reasoning", it) }
                    })
                }
            })
        }
        File(dir, "${conv.id}.json").writeText(json.toString(), Charsets.UTF_8)
    }

    /** 加载单个会话的完整数据；文件不存在时返回 null。 */
    fun loadConversation(id: Long): Conversation? {
        val file = File(dir, "$id.json")
        if (!file.exists()) return null
        return parseConversation(file.readText(Charsets.UTF_8))
    }

    /**
     * 列出所有会话（不含 messages 详情，仅元数据），按 [Conversation.createdAt] 倒序。
     * 用于填充侧边栏历史列表。
     */
    fun listConversations(): List<Conversation> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                val json = JSONObject(file.readText(Charsets.UTF_8))
                Conversation(
                    id = json.getLong("id"),
                    title = json.getString("title"),
                    createdAt = json.getLong("createdAt"),
                    messages = emptyList(),
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.createdAt }
    }

    /** 删除指定会话文件。 */
    fun deleteConversation(id: Long) {
        File(dir, "$id.json").delete()
    }

    private fun parseConversation(text: String): Conversation {
        val json = JSONObject(text)
        val msgsArray = json.getJSONArray("messages")
        val messages = (0 until msgsArray.length()).map { i ->
            val obj = msgsArray.getJSONObject(i)
            // reasoning 与 saveConversation 端用 optString 兼容：缺失 / 空串都视作 null
            MessageRecord(
                role = obj.getString("role"),
                content = obj.getString("content"),
                reasoning = obj.optString("reasoning").ifEmpty { null },
            )
        }
        return Conversation(
            id = json.getLong("id"),
            title = json.getString("title"),
            createdAt = json.getLong("createdAt"),
            messages = messages,
        )
    }
}
