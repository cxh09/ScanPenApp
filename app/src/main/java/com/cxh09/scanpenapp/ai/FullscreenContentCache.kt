package com.cxh09.scanpenapp.ai

import android.content.Context
import java.io.File

/**
 * AI 回答全屏页的内容缓存：
 * - 调用方把可能很大的 Markdown 文本写入 [cacheDir]/ai-fullscreen/ 下的临时文件，
 *   把文件路径通过 Intent 传给 [com.cxh09.scanpenapp.AiMessageFullscreenActivity]。
 * - 接收方读完后立即删除。
 * - 启动期定时清理超过 [MAX_AGE_MS] 的孤儿文件，防止磁盘占满。
 *
 * 为什么不直接走 Intent extra：Binder 事务上限约 1MB（含 extras 总和），
 * 单条 String 超过 ~500KB 就会抛 `TransactionTooLargeException`，直接把 App 拉崩。
 */
object FullscreenContentCache {

    /** 单个 extra 字符串的安全阈值。低于此值仍走 Intent（省一次 IO），否则走文件。 */
    const val INLINE_MAX_BYTES: Int = 32 * 1024

    /** 孤儿文件过期时间（毫秒）。 */
    const val MAX_AGE_MS: Long = 60L * 60L * 1000L

    private const val DIR_NAME = "ai-fullscreen"
    private const val PREFIX = "msg_"
    private const val SUFFIX = ".md"

    private fun dir(ctx: Context): File =
        File(ctx.cacheDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * 写入全屏页内容，返回临时文件。失败返回 null。
     * 调用方在 catch 块里降级为截断显示即可。
     */
    fun write(ctx: Context, content: String): File? = try {
        val file = File(dir(ctx), "$PREFIX${System.currentTimeMillis()}$SUFFIX")
        file.writeText(content, Charsets.UTF_8)
        file
    } catch (_: Throwable) {
        null
    }

    /**
     * 删除指定文件。读完全屏页内容后调用，失败也吞掉（仅是临时文件）。
     */
    fun consume(file: File) {
        runCatching { if (file.exists()) file.delete() }
    }

    /**
     * 清理超过 [MAX_AGE_MS] 的孤儿临时文件。Application 启动时调用一次即可。
     * - 不删 1 小时内的文件，给可能的 Activity 重启 / 异常留余地。
     */
    fun cleanupOrphans(ctx: Context) {
        runCatching {
            val d = dir(ctx)
            val files = d.listFiles() ?: return@runCatching
            val now = System.currentTimeMillis()
            files.forEach { f ->
                if (now - f.lastModified() > MAX_AGE_MS) f.delete()
            }
        }
    }
}
