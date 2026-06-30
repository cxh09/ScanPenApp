package com.cxh09.scanpenapp.notes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 笔记持久化存储。
 *
 * - 单文件 JSON：`filesDir/notes/notes.json`（UTF-8，version=1 + items 数组）。
 * - 图片物理文件：`filesDir/notes/images/<noteId>_<ts>.jpg`，
 *   删除笔记时同步清理该前缀下的所有文件。
 * - 与 [com.cxh09.scanpenapp.ai.BookmarkStore] / [com.cxh09.scanpenapp.ai.ConversationStore]
 *   同款风格：平台自带 [org.json] 零依赖，调用方必须在
 *   [kotlinx.coroutines.Dispatchers.IO] 中执行。
 * - 异常（文件损坏 / IO 失败）一律 `try/catch` 降级返回空 list / `false`，
 *   不向用户抛错，保证「笔记」入口不会因底层故障崩溃。
 */
class NoteStore(context: Context) {

    private val dir: File = File(context.filesDir, "notes").also {
        if (!it.exists()) it.mkdirs()
    }
    private val imagesDir: File = File(dir, "images").also {
        if (!it.exists()) it.mkdirs()
    }
    private val file: File = File(dir, FILE_NAME)

    /**
     * 添加一条笔记。id 由调用方传 0；内部单调分配
     * `max(existing.id, now) + 1`（与 [com.cxh09.scanpenapp.ai.BookmarkStore] 同步）。
     * 新笔记插入头部。写入成功返回新分配的 id；异常返回 `null`。
     */
    fun addNote(note: Note): Long? {
        return try {
            val list = readAll().toMutableList()
            val now = System.currentTimeMillis()
            val nextId = (list.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(now) + 1L
            val finalItem = note.copy(
                id = nextId,
                createdAt = if (note.createdAt <= 0L) now else note.createdAt,
                updatedAt = now,
            )
            list.add(0, finalItem)
            writeAll(list)
            nextId
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 列出所有笔记，按 [Note.updatedAt] 倒序。文件不存在 / 解析失败 → 空 list。
     */
    fun listNotes(): List<Note> = readAll().sortedByDescending { it.updatedAt }

    /**
     * 按 id 取笔记；找不到返回 `null`。
     */
    fun getNote(id: Long): Note? = readAll().firstOrNull { it.id == id }

    /**
     * 更新一条笔记：替换 id 相同项并刷新 [Note.updatedAt]；找不到 id 返回 `false`。
     * 调用方应保证传入的 [Note.updatedAt] 是新值（由调用方决定或由本方法刷新）。
     */
    fun updateNote(note: Note): Boolean {
        return try {
            val list = readAll().toMutableList()
            val idx = list.indexOfFirst { it.id == note.id }
            if (idx < 0) return false
            val now = System.currentTimeMillis()
            list[idx] = note.copy(updatedAt = now)
            writeAll(list)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 删除一条笔记：
     * 1. 删除 `filesDir/notes/images/<noteId>_*.jpg` 全部文件
     * 2. 从 `notes.json` 移除该条
     * 3. 写回文件
     *
     * 找不到 id 返回 `false`；异常返回 `false`。
     */
    fun deleteNote(id: Long): Boolean {
        return try {
            val list = readAll()
            val target = list.firstOrNull { it.id == id } ?: return false
            // 1. 删除图片物理文件
            val prefix = "${id}_"
            imagesDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(prefix)) {
                    runCatching { f.delete() }
                }
            }
            // 2. 移除条目
            val filtered = list.filterNot { it.id == target.id }
            writeAll(filtered)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 内部读全量。文件不存在 / 解析失败 → 空 list。
     * 注意：调用方应假定返回值可能为空，不要据此判定「无任何笔记」与「文件 IO 失败」。
     */
    private fun readAll(): List<Note> {
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText(Charsets.UTF_8)
            if (text.isBlank()) return emptyList()
            val json = JSONObject(text)
            val arr = json.optJSONArray("items") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                try {
                    val obj = arr.getJSONObject(i)
                    Note(
                        id = obj.optLong("id", 0L),
                        title = obj.optString("title", ""),
                        contentHtml = obj.optString("contentHtml", ""),
                        createdAt = obj.optLong("createdAt", 0L),
                        updatedAt = obj.optLong("updatedAt", 0L),
                    ).takeIf { it.id > 0L }
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 内部写全量：version=1 + items 数组，UTF-8 写入。 */
    private fun writeAll(list: List<Note>) {
        val arr = JSONArray()
        list.forEach { n ->
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("title", n.title)
                put("contentHtml", n.contentHtml)
                put("createdAt", n.createdAt)
                put("updatedAt", n.updatedAt)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("items", arr)
        }
        file.writeText(root.toString(), Charsets.UTF_8)
    }

    /**
     * 当前笔记图片物理文件的目标目录（供 [NoteListActivity] 写入新图时引用）。
     */
    val imagesDirectory: File get() = imagesDir

    companion object {
        private const val FILE_NAME = "notes.json"
    }
}
