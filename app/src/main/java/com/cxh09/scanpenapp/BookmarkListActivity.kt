package com.cxh09.scanpenapp

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cxh09.scanpenapp.ai.Bookmark
import com.cxh09.scanpenapp.ai.BookmarkStore
import com.cxh09.scanpenapp.ai.ConversationStore
import com.cxh09.scanpenapp.databinding.ActivityBookmarkListBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 收藏列表查看页。
 *
 * 布局：
 * - 顶部工具栏 48dp：返回 + 标题 + 数量
 * - 主体：左侧 ListView 收藏条目 + 右侧 ScrollView 详情（Markdown 渲染）
 *
 * 渲染：
 * - 与 [AiMessageFullscreenActivity] / [AskAiActivity] 共用同一份 Markwon 配置
 *   （Strikethrough / Table 插件），保证视觉一致
 *
 * 数据：
 * - 启动后 IO 线程读 [BookmarkStore.listBookmarks]，渲染时切回主线程
 * - 选中条目时根据 [Bookmark.type] 加载内容：
 *   - [Bookmark.TYPE_CONVERSATION] 从 [ConversationStore] 读会话并拼成
 *     "user: ...\n\n---\n\nassistant: ..." 多轮格式
 *   - [Bookmark.TYPE_MESSAGE] 直接用 [Bookmark.contentSnippet]
 *
 * 删除：
 * - 长按条目弹 [AlertDialog] 确认；确认后 IO 线程调 [BookmarkStore.deleteBookmark]，
 *   完成后回到主线程刷新列表 / 数量
 */
class BookmarkListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBookmarkListBinding

    private var adapter: BookmarkAdapter? = null

    /** 当前右侧内容区展示的收藏项，用于删除后判定是否需要清空右侧。 */
    private var currentBookmark: Bookmark? = null

    /**
     * 与 [AskAiActivity] / [AiMessageFullscreenActivity] 共享的 Markdown 渲染器配置。
     */
    private val markwon: Markwon by lazy {
        Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBookmarkListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.setText(R.string.ai_bookmark_view_title)

        loadBookmarks()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /**
     * 加载收藏列表（IO 线程），完成后切回主线程渲染 + 选中第一项。
     */
    private fun loadBookmarks() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                BookmarkStore(this@BookmarkListActivity).listBookmarks()
            }
            binding.tvCount.text = getString(R.string.ai_bookmark_view_entry_count, items.size)
            if (items.isEmpty()) {
                showEmptyState()
                return@launch
            }
            val a = BookmarkAdapter(this@BookmarkListActivity)
            a.addAll(items)
            a.notifyDataSetChanged()
            adapter = a
            binding.lvBookmarks.adapter = a
            binding.lvBookmarks.setOnItemClickListener { _, _, position, _ ->
                val item = a.getItem(position) ?: return@setOnItemClickListener
                currentBookmark = item
                a.setSelected(item.id)
                loadContentForBookmark(item)
            }
            binding.lvBookmarks.setOnItemLongClickListener { _, _, position, _ ->
                val item = a.getItem(position) ?: return@setOnItemLongClickListener false
                showDeleteConfirm(item)
                true
            }
        }
    }

    /**
     * 加载并渲染指定收藏的右侧内容。
     */
    private fun loadContentForBookmark(item: Bookmark) {
        binding.tvContent.text = ""
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                buildContentText(item)
            } ?: getString(R.string.ai_bookmark_view_conv_deleted)
            markwon.setMarkdown(binding.tvContent, text)
        }
    }

    /**
     * 在 IO 线程中根据 [Bookmark.type] 拼装 Markdown 文本。
     * - [Bookmark.TYPE_CONVERSATION]：从 [ConversationStore] 加载多轮对话；
     *   对话不存在（已删除）时返回 `null`，由调用方显示「原对话已不存在」。
     * - [Bookmark.TYPE_MESSAGE]：直接用 [Bookmark.contentSnippet]。
     */
    private fun buildContentText(item: Bookmark): String? {
        return when (item.type) {
            Bookmark.TYPE_CONVERSATION -> {
                val convId = item.refId.toLongOrNull() ?: return null
                val conv = ConversationStore(this).loadConversation(convId) ?: return null
                if (conv.messages.isEmpty()) return ""
                val youLabel = getString(R.string.ai_msg_you)
                val aiLabel = getString(R.string.ai_msg_ai)
                val sep = "\n\n---\n\n"
                conv.messages.joinToString(sep) { msg ->
                    val label = if (msg.role == "user") youLabel else aiLabel
                    "$label: ${msg.content}"
                }
            }
            Bookmark.TYPE_MESSAGE -> item.contentSnippet
            else -> item.contentSnippet
        }
    }

    /**
     * 弹出删除确认对话框。确认后调 [confirmDelete]。
     */
    private fun showDeleteConfirm(item: Bookmark) {
        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_AlertDialog_Dark)
            .setTitle(R.string.ai_bookmark_view_delete_title)
            .setMessage(R.string.ai_bookmark_view_delete_message)
            .setPositiveButton(R.string.ai_bookmark_view_delete_confirm) { _, _ -> confirmDelete(item) }
            .setNegativeButton(R.string.ai_bookmark_view_delete_cancel, null)
            .show()
    }

    /**
     * 删除一条收藏：IO 线程写盘 → 主线程刷新列表 / 数量 / 右侧内容。
     */
    private fun confirmDelete(item: Bookmark) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                BookmarkStore(this@BookmarkListActivity).deleteBookmark(item.id)
            }
            // 重新拉取并刷新 UI
            val items = withContext(Dispatchers.IO) {
                BookmarkStore(this@BookmarkListActivity).listBookmarks()
            }
            binding.tvCount.text = getString(R.string.ai_bookmark_view_entry_count, items.size)
            adapter?.let { a ->
                a.clear()
                a.addAll(items)
                a.notifyDataSetChanged()
            }
            if (items.isEmpty()) {
                adapter = null
                binding.lvBookmarks.adapter = null
                showEmptyState()
            } else if (currentBookmark?.id == item.id) {
                currentBookmark = null
                showEmptyState()
            }
        }
    }

    /**
     * 空态：标题加粗 + 引导文案。
     * 不走 Markwon（避免 H1 渲染占更多垂直空间）。
     */
    private fun showEmptyState() {
        binding.tvContent.text = ""
        val sb = SpannableStringBuilder()
        sb.append(
            getString(R.string.ai_bookmark_view_empty),
            StyleSpan(Typeface.BOLD),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        sb.append("\n\n")
        sb.append(getString(R.string.ai_bookmark_view_empty_hint))
        binding.tvContent.text = sb
    }

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 收藏列表 Adapter。
     * - 复用 ViewHolder，避免在 [getView] 中分配对象（除首次 inflate 与创建 ViewHolder 外）
     * - 选中态 / 未选中态都使用圆角背景 Drawable，仅靠颜色深浅区分；
     *   选中态更深一档（圆角灰底），与历史对话列表项同款风格。
     */
    private class BookmarkAdapter(
        context: android.content.Context,
    ) : ArrayAdapter<Bookmark>(context, 0) {

        private var selectedId: Long? = null
        // 缓存两个圆角背景 Drawable，避免在 getView 中重复解析资源
        private val normalBackground by lazy {
            ContextCompat.getDrawable(context, R.drawable.bg_bookmark_normal)
        }
        private val selectedBackground by lazy {
            ContextCompat.getDrawable(context, R.drawable.bg_bookmark_selected)
        }

        fun setSelected(id: Long?) {
            selectedId = id
            notifyDataSetChanged()
        }

        private class ViewHolder(
            val itemView: View,
            val title: TextView,
            val snippet: TextView,
        )

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: ViewHolder
            val view: View
            if (convertView == null) {
                view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_bookmark_row, parent, false)
                holder = ViewHolder(
                    itemView = view,
                    title = view.findViewById(R.id.tvBookmarkTitle),
                    snippet = view.findViewById(R.id.tvBookmarkSnippet),
                )
                view.tag = holder
            } else {
                view = convertView
                holder = convertView.tag as ViewHolder
            }

            val item = getItem(position) ?: return view
            holder.title.text = item.title
            holder.snippet.text = item.contentSnippet.ifEmpty { item.refId }
            // 选中态 / 未选中态使用同款形状的圆角 Drawable，仅颜色不同
            holder.itemView.background = if (item.id == selectedId) {
                selectedBackground
            } else {
                normalBackground
            }
            return view
        }
    }
}
