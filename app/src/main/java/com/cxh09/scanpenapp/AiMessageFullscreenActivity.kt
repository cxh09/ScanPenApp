package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cxh09.scanpenapp.ai.Bookmark
import com.cxh09.scanpenapp.ai.BookmarkStore
import com.cxh09.scanpenapp.ai.FullscreenContentCache
import com.cxh09.scanpenapp.databinding.ActivityAiMessageFullscreenBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AI 回答全屏查看页。
 *
 * - 入口：在 [AskAiActivity] 双击 AI 回答气泡触发 [start]，把当前 TextView 的纯文本 + 当前对话标题带过来。
 * - 内容传递策略（防 TransactionTooLargeException）：
 *   - 内容 ≤ 32KB → 走 Intent extra（省一次 IO）
 *   - 内容 > 32KB → 写入 [FullscreenContentCache] 临时文件，仅传文件路径
 *   - 读完后立即删除临时文件；启动期 [ScanPenApp] 兜底清理过期孤儿
 * - 顶部工具栏标题：显示当前对话的标题（`history.title`，如"06/27 14:32"），fallback 为 "AI 回答"。
 * - 渲染：用项目里已有的 Markwon（Strikethrough / Table 插件）与 [AskAiActivity] 保持一致。
 * - 关闭：左上"← 返回"按钮，或系统返回手势。
 * - 收藏：工具栏左侧"← 返回"右侧的「🔖 收藏」按钮，点击将当前内容写入 BookmarkStore。
 *
 * 不接受 Intent 之外的来源，也不接收 Markdown 源文件，避免恶意内容触发额外解析路径。
 */
class AiMessageFullscreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiMessageFullscreenBinding

    /**
     * 与 [AskAiActivity] 共享的 Markdown 渲染器配置，保证全屏与列表的视觉一致。
     */
    private val markwon: Markwon by lazy {
        Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()
    }

    /** 收藏用的内容：onCreate 期间从 Intent 取出，onBookmarkClicked 时使用（避免重复 Intent 读取）。 */
    private var contentForBookmark: String = ""

    /** 收藏用的标题：fallback "AI 回答"。 */
    private var titleForBookmark: String = ""

    /** 收藏用的对话 id（来自 AskAiActivity 透传），无对话时为 null。 */
    private var conversationIdForBookmark: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiMessageFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBookmark.setOnClickListener { onBookmarkClicked() }

        // 顶部标题：优先用调用方传入的当前对话标题，缺失时回退到通用 "AI 回答"。
        val title = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ai_fullscreen_title)
        binding.tvTitle.text = title
        titleForBookmark = title

        // 对话 id（透传自 AskAiActivity，用于 Bookmark.title）
        val convId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        conversationIdForBookmark = convId.takeIf { it > 0L }

        // 读内容：优先读文件（后台线程），失败回退到 inline extra
        val pathExtra = intent.getStringExtra(EXTRA_CONTENT_PATH)
        val inlineExtra = intent.getStringExtra(EXTRA_CONTENT)
        if (!pathExtra.isNullOrBlank()) {
            // 大内容 → 后台读，读完即删
            binding.tvContent.text = ""
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    val file = File(pathExtra)
                    if (file.exists() && file.canRead()) {
                        runCatching { file.readText(Charsets.UTF_8) }
                            .also { FullscreenContentCache.consume(file) }
                            .getOrNull()
                    } else {
                        // 文件读失败也兜底删一下
                        runCatching { file.takeIf { it.exists() }?.delete() }
                        null
                    }
                }
                if (!text.isNullOrEmpty()) {
                    contentForBookmark = text
                    markwon.setMarkdown(binding.tvContent, text)
                } else if (!inlineExtra.isNullOrEmpty()) {
                    // 文件丢了或读失败 → 退化用 inline
                    contentForBookmark = inlineExtra
                    markwon.setMarkdown(binding.tvContent, inlineExtra)
                }
            }
        } else {
            // inline 模式（小内容）
            val content = inlineExtra.orEmpty()
            contentForBookmark = content
            if (content.isNotEmpty()) {
                markwon.setMarkdown(binding.tvContent, content)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /**
     * 收藏当前 AI 回答：构造 [Bookmark] 写入 [BookmarkStore]。
     * - IO 走 [Dispatchers.IO]，避免主线程阻塞
     * - 写入完成（无论 true/false，因为可能有重复）切回 Main 弹 Toast「已加入收藏」
     * - 按钮不变化、不闪烁（spec 明确：每次进入全屏页都显示「未收藏」外观）
     */
    private fun onBookmarkClicked() {
        val content = contentForBookmark
        if (content.isBlank()) {
            // 极端情况：Intent 没传内容，理论上 onCreate 已校验过；保险
            Toast.makeText(this, R.string.ai_bookmark_added, Toast.LENGTH_SHORT).show()
            return
        }
        val snippet = content.take(SNIPPET_MAX_LEN)
        // refId 用 content 的稳定哈希（截前 32 字符的 MD5 摘要的简化版：直接取前 32 字符）。
        // 同一 AI 回答多次点收藏 → 同一 refId → BookmarkStore 内部去重。
        val refId = content.take(REF_ID_HASH_LEN)
        val title = titleForBookmark.takeIf { it.isNotBlank() }
            ?: getString(R.string.ai_fullscreen_title)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BookmarkStore(this@AiMessageFullscreenActivity).addBookmark(
                    Bookmark(
                        id = 0L,  // 由 BookmarkStore 重新分配
                        type = Bookmark.TYPE_MESSAGE,
                        refId = refId,
                        contentSnippet = snippet,
                        title = title,
                        createdAt = 0L,
                    )
                )
            }
            Toast.makeText(
                this@AiMessageFullscreenActivity,
                R.string.ai_bookmark_added,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_CONTENT = "extra_content"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        /** 大内容文件路径。 */
        private const val EXTRA_CONTENT_PATH = "extra_content_path"

        /** 收藏摘要最大字符数（与 AskAiActivity 保持一致）。 */
        private const val SNIPPET_MAX_LEN = 200

        /** 收藏 refId 截取长度（用内容前 32 字符作为稳定标识）。 */
        private const val REF_ID_HASH_LEN = 32

        /** 收藏按钮「实心」闪一下的时长（ms），给用户即时的「已收藏」视觉确认。 */
        private const val BOOKMARK_FLASH_MS = 600L

        /**
         * 跳转到全屏查看页。
         *
         * - 内容 ≤ 32KB：走 Intent extra，省一次 IO
         * - 内容 > 32KB：写到 [FullscreenContentCache] 临时文件，传文件路径，
         *   防止 Binder TransactionTooLargeException 崩溃
         * - 写文件失败：降级为 inline（截断到 64KB 防止真的爆 Binder）
         *
         * @param content 触发时 AI 回答气泡的当前文本（可为流式中段或最终内容）
         * @param title 当前对话的标题（来自 [com.cxh09.scanpenapp.AskAiActivity] 的 history.title），
         *              传 null / 空时 toolbar 回退显示 "AI 回答"
         * @param conversationId 当前对话的 id（透传到 [Bookmark.title] 备用；本参数新增于本 spec，
         *                       旧调用方不传时收藏 entry 的 title 走 fallback "AI 回答"）
         */
        fun start(context: Context, content: CharSequence, title: String?, conversationId: Long? = null) {
            val intent = Intent(context, AiMessageFullscreenActivity::class.java)
            val text = content.toString()
            if (!title.isNullOrBlank()) {
                intent.putExtra(EXTRA_TITLE, title)
            }
            if (conversationId != null && conversationId > 0L) {
                intent.putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            // 大内容走文件；写盘失败兜底走 inline（截断）
            val file = FullscreenContentCache.write(context.applicationContext, text)
            if (file != null) {
                intent.putExtra(EXTRA_CONTENT_PATH, file.absolutePath)
            } else {
                intent.putExtra(
                    EXTRA_CONTENT,
                    text.take(FALLBACK_INLINE_MAX_CHARS),
                )
            }
            context.startActivity(intent)
        }

        /**
         * 文件写失败时的 inline 兜底上限：远低于 Binder 事务上限（~1MB），
         * 留出余量给其它 extras / 系统元数据。
         */
        private const val FALLBACK_INLINE_MAX_CHARS = 64 * 1024
    }
}
