package com.cxh09.scanpenapp.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 收藏项（对话 / 消息）。
 *
 * @param id 自增 id（构造时由 [BookmarkStore] 分配，保证单调）
 * @param type 收藏类型：[TYPE_CONVERSATION] 或 [TYPE_MESSAGE]
 * @param refId 引用 id 字符串（对话 = `Long.toString(convId)`，消息 = 内容前 32 字符 sha-摘要）
 * @param contentSnippet 摘要文本（截断到 200 字内，用于收藏列表预览）
 * @param title 标题（对话 = history.title，消息 = 当前对话标题 / fallback "AI 回答"）
 * @param createdAt 创建时间戳（毫秒）
 */
data class Bookmark(
    val id: Long,
    val type: String,
    val refId: String,
    val contentSnippet: String,
    val title: String,
    val createdAt: Long,
) {
    companion object {
        const val TYPE_CONVERSATION = "conversation"
        const val TYPE_MESSAGE = "message"
    }
}

/**
 * 收藏夹持久化存储。
 *
 * 每个收藏以独立 JSON 文件存储在 `filesDir/bookmarks/favorites.json` 中。
 * 使用平台自带 [org.json] 零依赖序列化，与 [ConversationStore] 一致。
 *
 * 所有方法均为同步 IO，调用方须在 [kotlinx.coroutines.Dispatchers.IO] 中执行。
 *
 * 异常（文件损坏 / IO 失败）一律 `try/catch` 后降级返回空 list / `false`，
 * 不向用户抛错，保证「收藏」入口不会因底层故障崩溃。
 */
class BookmarkStore(context: Context) {

    private val dir: File = File(context.filesDir, "bookmarks").also {
        if (!it.exists()) it.mkdirs()
    }
    private val file: File = File(dir, FILE_NAME)

    /**
     * 添加一条收藏。
     * - 同一 [Bookmark.type] + [Bookmark.refId] 已存在则视为重复，**不**新增，返回 `false`。
     * - 写入失败 / 解析失败 → 返回 `false`，不抛错。
     */
    fun addBookmark(item: Bookmark): Boolean {
        return try {
            val list = readAll().toMutableList()
            val exists = list.any { it.type == item.type && it.refId == item.refId }
            if (exists) return false
            // id 单调：max(existing.id, now) + 1，保证多次快速调用也不会撞 id
            val now = System.currentTimeMillis()
            val nextId = (list.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(now) + 1L
            val finalItem = item.copy(id = nextId, createdAt = now)
            list.add(0, finalItem)  // 最新在前
            writeAll(list)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 列出所有收藏，按 [Bookmark.createdAt] 倒序。
     * - 文件不存在 / 解析失败 → 返回 `emptyList()`。
     */
    fun listBookmarks(): List<Bookmark> = readAll().sortedByDescending { it.createdAt }

    /**
     * 判定指定 type + refId 是否已收藏。
     */
    fun isBookmarked(type: String, refId: String): Boolean {
        return try {
            readAll().any { it.type == type && it.refId == refId }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 按 id 删除一条收藏。
     * - 找不到匹配 id → 返回 `false`，文件不变。
     * - 删除成功 → 返回 `true`。
     * - 写入失败 / 解析失败 → 返回 `false`，不抛错。
     */
    fun deleteBookmark(id: Long): Boolean {
        return try {
            val list = readAll()
            val target = list.firstOrNull { it.id == id } ?: return false
            val filtered = list.filterNot { it.id == target.id }
            writeAll(filtered)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 内部读全量；损坏文件返回 `emptyList()`。
     * 注意：调用方应假定返回值可能为空，**不要**据此判定「无任何收藏」与「文件 IO 失败」，
     * 本期 UI 仅在「点收藏」时写，不在 UI 上展示列表（spec 明确范围外），无需严格区分。
     */
    private fun readAll(): List<Bookmark> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            val json = JSONObject(text)
            val arr = json.optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    Bookmark(
                        id = obj.optLong("id", 0L),
                        type = obj.optString("type", ""),
                        refId = obj.optString("refId", ""),
                        contentSnippet = obj.optString("contentSnippet", ""),
                        title = obj.optString("title", ""),
                        createdAt = obj.optLong("createdAt", 0L),
                    ).takeIf { it.type.isNotEmpty() && it.refId.isNotEmpty() }
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 内部写全量：构造 version=1 + items 数组，写回文件。 */
    private fun writeAll(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("type", b.type)
                put("refId", b.refId)
                put("contentSnippet", b.contentSnippet)
                put("title", b.title)
                put("createdAt", b.createdAt)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("items", arr)
        }
        file.writeText(root.toString(), Charsets.UTF_8)
    }

    companion object {
        private const val FILE_NAME = "favorites.json"
    }
}
