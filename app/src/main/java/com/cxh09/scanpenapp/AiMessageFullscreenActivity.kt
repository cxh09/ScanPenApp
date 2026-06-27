package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivityAiMessageFullscreenBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

/**
 * AI 回答全屏查看页。
 *
 * - 入口：在 [AskAiActivity] 双击 AI 回答气泡触发 [start]，把当前 TextView 的纯文本 + 当前对话标题带过来。
 * - 顶部工具栏标题：显示当前对话的标题（`history.title`，如"06/27 14:32"），fallback 为 "AI 回答"。
 * - 渲染：用项目里已有的 Markwon（Strikethrough / Table 插件）与 [AskAiActivity] 保持一致。
 * - 关闭：左上"← 返回"按钮，或系统返回手势。
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiMessageFullscreenBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        binding.btnBack.setOnClickListener { finish() }

        // 顶部标题：优先用调用方传入的当前对话标题，缺失时回退到通用 "AI 回答"。
        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ai_fullscreen_title)

        // 取 Intent 里的纯文本，Markwon 重新渲染。
        // - 即使在 AskAiActivity 流式过程中触发，跳转时 [content] 已是那一刻的累积值；
        //   用户主动选择"全屏查看"语义就是看当前快照。
        // - 用 toString() 而非 Spanned，Intent 不便传 Spanned（Parcelable 序列化复杂），
        //   反正重新走 Markwon 后视觉等价。
        val content = intent.getStringExtra(EXTRA_CONTENT).orEmpty()
        if (content.isNotEmpty()) {
            markwon.setMarkdown(binding.tvContent, content)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
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

        /**
         * 跳转到全屏查看页。
         * @param content 触发时 AI 回答气泡的当前文本（可为流式中段或最终内容）
         * @param title 当前对话的标题（来自 [com.cxh09.scanpenapp.AskAiActivity] 的 history.title），
         *              传 null / 空时 toolbar 回退显示 "AI 回答"
         */
        fun start(context: Context, content: CharSequence, title: String?) {
            val intent = Intent(context, AiMessageFullscreenActivity::class.java)
            intent.putExtra(EXTRA_CONTENT, content.toString())
            if (!title.isNullOrBlank()) {
                intent.putExtra(EXTRA_TITLE, title)
            }
            context.startActivity(intent)
        }
    }
}
