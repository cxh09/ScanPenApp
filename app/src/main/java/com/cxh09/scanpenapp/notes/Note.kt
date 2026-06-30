package com.cxh09.scanpenapp.notes

/**
 * 笔记数据模型。
 *
 * - 富文本存储：用 Android 自带 [android.text.Html.toHtml] 序列化结果，承载
 *   `<b>` / `<i>` / `<img>` 三种标签，由 [android.text.Html.fromHtml] 反序列化。
 * - 时间戳：`createdAt` / `updatedAt` 均为毫秒；`listNotes` 按 `updatedAt` 倒序展示。
 *
 * @property id 自增 id（构造时由 [NoteStore] 分配，保证单调）
 * @property title 标题（默认「新笔记」，用户后续修改）
 * @property contentHtml 富文本内容（Html.toHtml 序列化结果，可为空字符串）
 * @property createdAt 创建时间戳（毫秒）
 * @property updatedAt 最后更新时间戳（毫秒）
 */
data class Note(
    val id: Long,
    val title: String,
    val contentHtml: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /**
     * 用于列表项预览的纯文本摘要：去除 HTML tag 后按空白折行，最后截断到 200 字。
     * 不在 UI 主线程做长文本扫描（短摘要 200 字内可接受）。
     */
    val contentSnippet: String
        get() {
            if (contentHtml.isEmpty()) return ""
            val text = contentHtml
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (text.length > 200) text.substring(0, 200) else text
        }
}
