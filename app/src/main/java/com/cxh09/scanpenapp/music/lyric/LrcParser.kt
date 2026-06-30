package com.cxh09.scanpenapp.music.lyric

/**
 * LRC 歌词解析器。
 *
 * ## 支持的格式
 * - 标准时间戳：`[00:00.000] 歌词文本`
 * - 多时间戳一行：`[00:01.00][00:30.00]副歌` → 输出 2 个 [LrcLine]，text 相同
 * - 元数据行：`[ti:歌名] / [ar:歌手] / [al:专辑] / [by:xxx] / [offset:0]` 全部跳过
 *
 * ## 输出
 * - 按 `timeMs` 升序的 [List]<[LrcLine]>
 * - 空 / 非法输入返回 [emptyList]，不抛
 *
 * ## 线程
 * 纯字符串 / 正则计算，无 IO；调用方任意线程可用。
 */
object LrcParser {

    /**
     * 单个时间戳正则：匹配 `[mm:ss.fff]` 或 `[m:ss.ff]` 或 `[mm:ss]` 等
     * - m / s: 1~2 位
     * - f: 1~3 位（毫秒 / 厘秒）
     * - 分隔符：`.` 或 `:`
     */
    private val lineRegex = Regex("""\[(\d{1,2}):(\d{1,2})[.:](\d{1,3})]""")

    /** 元数据行正则：`[ti:xxx]` / `[ar:xxx]` 等。 */
    private val metaRegex = Regex("""^\[(ti|ar|al|by|offset|length):.*]""", RegexOption.IGNORE_CASE)

    /**
     * 解析 LRC 字符串为 [LrcLine] 列表。
     * @param text 原始 LRC（可能含 `\n` 或 `\r\n`）
     * @return 按 `timeMs` 升序的列表；空 / 非法输入返回 `emptyList()`
     */
    fun parse(text: String?): List<LrcLine> {
        if (text.isNullOrBlank()) return emptyList()
        val out = ArrayList<LrcLine>()
        // 按 \r\n 或 \n 切行
        val lines = text.split('\n')
        for (raw in lines) {
            val line = raw.trimEnd('\r').trim()
            if (line.isEmpty()) continue
            // 元数据行整行跳过
            if (metaRegex.matches(line)) continue
            // 收集所有时间戳
            val matches = lineRegex.findAll(line).toList()
            if (matches.isEmpty()) continue
            // 提取首个时间戳之后的内容作为文本
            val lastEnd = matches.last().range.last + 1
            val textPart = if (lastEnd < line.length) line.substring(lastEnd).trim() else ""
            if (textPart.isEmpty()) continue
            for (m in matches) {
                val (mm, ss, ff) = m.destructured
                val min = mm.toLongOrNull() ?: continue
                val sec = ss.toLongOrNull() ?: continue
                val frac = ff.toLongOrNull() ?: continue
                // 1~3 位小数：1 位 = 0.1s（100ms），2 位 = 0.01s（10ms），3 位 = 0.001s（1ms）
                val fracMs = when (ff.length) {
                    1 -> frac * 100L
                    2 -> frac * 10L
                    else -> frac
                }
                val totalMs = min * 60_000L + sec * 1_000L + fracMs
                out.add(LrcLine(timeMs = totalMs, text = textPart))
            }
        }
        if (out.isEmpty()) return emptyList()
        out.sortBy { it.timeMs }
        return out
    }

    /**
     * 在已排序的 [lines] 中二分查找 `timeMs` 对应的当前行。
     * 返回满足 `lines[i].timeMs <= timeMs` 的最大 i（若全部 > timeMs，返回 -1）。
     */
    fun findCurrentLineIndex(lines: List<LrcLine>, timeMs: Long): Int {
        if (lines.isEmpty()) return -1
        if (timeMs < lines[0].timeMs) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= timeMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
