package com.cxh09.scanpenapp

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cxh09.scanpenapp.databinding.ActivityNoteListBinding
import com.cxh09.scanpenapp.notes.Note
import com.cxh09.scanpenapp.notes.NoteStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * 笔记主页（横屏 1:4）。
 *
 * 布局：
 * - 顶栏 32dp：左上「＋ 新笔记」+ 中部标题 + 右侧占位 + 1dp 分隔线
 * - 左侧 ListView（笔记列表，weight=2）
 * - 右侧编辑区（weight=3）：标题 EditText + 正文 EditText + 工具栏 3 按钮（加粗 / 斜体 / 插图）
 *
 * 数据：
 * - [NoteStore] 单文件 JSON `filesDir/notes/notes.json` 持久化，图片物理文件 `filesDir/notes/images/<id>_<ts>.jpg`
 * - 启动后 IO 线程读全量，主线程渲染 + 选中第一项
 *
 * 编辑：
 * - 标题 / 正文接入 [TextWatcher]，600ms 防抖后 `Dispatchers.IO` 调 [NoteStore.updateNote]，
 *   回主线程刷新列表项摘要
 * - 加粗 / 斜体：选区上 toggle [StyleSpan]（无选区时插入零宽字符 + StyleSpan 让后续输入自带样式）
 * - 插图：调 [ActivityResultContracts.PickVisualMedia] 系统选图 → IO 线程解码 + 缩放 + 写盘 → 主线程插入
 *   [ImageSpan]（`\uFFFC` 字符位置 + ImageSpan 同款，Html 序列化时输出 `<img src="file://..."/>`）
 *
 * 删除：
 * - 长按列表项弹 [AlertDialog] 二次确认；确认后 IO 线程调 [NoteStore.deleteNote]，
 *   完成回主线程刷新列表 / 右侧
 *
 * 性能（按 `rule.md` §2.2 / §2.3）：
 * - 主线程无 IO（`NoteStore` / 图片解码 / 缩放 / 写盘 全部 `Dispatchers.IO`）
 * - 图片解码带 `inSampleSize` 控制内存，缩放后最长边 ≤ 1024px
 * - ListView 走 `ViewHolder` 复用，`getView` 中无对象分配（除首次 inflate 与 ViewHolder）
 * - 切换笔记前先 force-save 当前笔记，避免内容丢失
 */
class NoteListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNoteListBinding
    private lateinit var store: NoteStore
    private var adapter: NoteAdapter? = null

    /** 当前右侧内容区展示的笔记。 */
    private var currentNote: Note? = null

    /** 防抖保存的 Handler。 */
    private val saveHandler = Handler(Looper.getMainLooper())
    private var saveRunnable: Runnable? = null

    /** 标题 / 正文内容正在切换时屏蔽 TextWatcher，避免外部 setText 触发回写。 */
    private var suppressTextWatcher = false

    /** 选图回调：用户挑中后从 [Uri] 读图落盘并插入 ImageSpan。 */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) handleImagePicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNoteListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        store = NoteStore(this)

        binding.btnNewNote.setOnClickListener { onNewNoteClicked() }
        binding.btnBold.setOnClickListener { toggleStyleSpan(Typeface.BOLD) }
        binding.btnItalic.setOnClickListener { toggleStyleSpan(Typeface.ITALIC) }
        binding.btnImage.setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        attachTextWatchers()

        loadNotes()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 离开页面时同步触发最后一次保存（避免 Handler 队列里的 saveRunnable 随 Activity 销毁丢失）
        saveHandler.removeCallbacksAndMessages(null)
        saveRunnable?.let { runnable ->
            saveHandler.post {
                val note = currentNote ?: return@post
                val title = binding.etTitle.text?.toString().orEmpty()
                val contentSpanned: Spanned = binding.etContent.text ?: SpannableString("")
                val contentHtml = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    Html.toHtml(contentSpanned, Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL)
                } else {
                    @Suppress("DEPRECATION")
                    Html.toHtml(contentSpanned)
                }
                val updated = note.copy(
                    title = title,
                    contentHtml = contentHtml,
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    store.updateNote(updated)
                }
            }
        }
    }

    // region 加载与渲染

    /**
     * 加载所有笔记（IO 线程）→ 主线程渲染 ListView + 选中第一项。
     */
    private fun loadNotes() {
        lifecycleScope.launch {
            val notes = withContext(Dispatchers.IO) { store.listNotes() }
            renderNotes(notes)
            if (notes.isEmpty()) {
                showEmptyState()
            } else {
                hideEmptyState()
                // 默认选中第一项
                binding.lvNotes.performItemClick(
                    binding.lvNotes,
                    0,
                    binding.lvNotes.adapter.getItemId(0)
                )
            }
        }
    }

    private fun renderNotes(notes: List<Note>) {
        val a = NoteAdapter(this, notes)
        binding.lvNotes.adapter = a
        a.setSelected(currentNote?.id)
        adapter = a

        binding.lvNotes.setOnItemClickListener { _, _, position, _ ->
            val item = a.getItem(position) ?: return@setOnItemClickListener
            if (currentNote?.id == item.id) return@setOnItemClickListener
            // 切换前先 force-save
            flushPendingSave()
            currentNote = item
            a.setSelected(item.id)
            loadNoteIntoEditor(item)
        }

        binding.lvNotes.setOnItemLongClickListener { _, _, position, _ ->
            val item = a.getItem(position) ?: return@setOnItemLongClickListener false
            showDeleteConfirm(item)
            true
        }
    }

    private fun loadNoteIntoEditor(note: Note) {
        suppressTextWatcher = true
        binding.etTitle.setText(note.title)
        binding.etContent.setText(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Html.fromHtml(
                    note.contentHtml,
                    Html.FROM_HTML_MODE_COMPACT,
                    imageGetter,
                    null
                )
            } else {
                @Suppress("DEPRECATION")
                Html.fromHtml(note.contentHtml, imageGetter, null)
            }
        )
        suppressTextWatcher = false
        binding.etContent.clearFocus()
        binding.etTitle.clearFocus()
    }

    // endregion

    // region 新建 / 删除

    private fun onNewNoteClicked() {
        // 切换前先 force-save
        flushPendingSave()
        val defaultTitle = getString(R.string.note_default_title)
        lifecycleScope.launch {
            val newId = withContext(Dispatchers.IO) {
                store.addNote(
                    Note(
                        id = 0L,
                        title = defaultTitle,
                        contentHtml = "",
                        createdAt = 0L,
                        updatedAt = 0L,
                    )
                )
            }
            if (newId == null) {
                Toast.makeText(this@NoteListActivity, R.string.note_save_fail, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val notes = withContext(Dispatchers.IO) { store.listNotes() }
            renderNotes(notes)
            hideEmptyState()
            // 找到新笔记的 position 并选中
            val a = adapter ?: return@launch
            val pos = (0 until a.count).firstOrNull { a.getItem(it)?.id == newId } ?: 0
            binding.lvNotes.setSelection(pos)
            currentNote = a.getItem(pos)
            a.setSelected(newId)
            loadNoteIntoEditor(currentNote!!)
            // 标题 EditText 等待用户输入
            suppressTextWatcher = true
            binding.etTitle.setText(defaultTitle)
            binding.etContent.setText("")
            suppressTextWatcher = false
            binding.etTitle.requestFocus()
            binding.etTitle.setSelection(binding.etTitle.text?.length ?: 0)
        }
    }

    private fun showDeleteConfirm(note: Note) {
        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_AlertDialog_Dark)
            .setTitle(R.string.note_delete_title)
            .setMessage(getString(R.string.note_delete_message, note.title))
            .setPositiveButton(R.string.note_delete_confirm) { _, _ -> confirmDelete(note) }
            .setNegativeButton(R.string.note_delete_cancel, null)
            .show()
    }

    private fun confirmDelete(note: Note) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { store.deleteNote(note.id) }
            if (!ok) {
                Toast.makeText(this@NoteListActivity, R.string.note_save_fail, Toast.LENGTH_SHORT).show()
            }
            // 重新拉取
            val notes = withContext(Dispatchers.IO) { store.listNotes() }
            if (notes.isEmpty()) {
                currentNote = null
                renderNotes(notes)
                showEmptyState()
            } else {
                renderNotes(notes)
                // 如果删的是当前笔记，切换到第一项
                if (currentNote?.id == note.id) {
                    currentNote = null
                    binding.lvNotes.performItemClick(
                        binding.lvNotes,
                        0,
                        binding.lvNotes.adapter.getItemId(0)
                    )
                } else {
                    hideEmptyState()
                }
            }
        }
    }

    // endregion

    // region 编辑器（加粗 / 斜体 / 插图）

    /**
     * 在选区上 toggle [StyleSpan]；无选区时在光标处插入零宽字符 + StyleSpan，
     * 后续输入会自然带该样式。
     */
    private fun toggleStyleSpan(style: Int) {
        val editable: Editable = binding.etContent.editableText
        val start = binding.etContent.selectionStart.coerceAtLeast(0)
        val end = binding.etContent.selectionEnd.coerceAtLeast(0)
        if (start == end) {
            // 无选区：插入一个 ZWSP（窄空格）+ StyleSpan，让后续输入自带样式
            val marker = "\u200B"
            editable.insert(start, marker)
            val insertPos = start + marker.length
            editable.setSpan(
                StyleSpan(style),
                start,
                insertPos,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            binding.etContent.setSelection(insertPos)
        } else {
            val from = minOf(start, end)
            val to = maxOf(start, end)
            val existing = editable.getSpans(from, to, StyleSpan::class.java)
            val hasStyle = existing.any { it.style == style }
            if (hasStyle) {
                existing.filter { it.style == style }.forEach { editable.removeSpan(it) }
            } else {
                editable.setSpan(
                    StyleSpan(style),
                    from,
                    to,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        // 触发一次保存（TextWatcher 也已挂上，但这里直接触发一次更稳）
        scheduleSave()
    }

    /**
     * 处理系统选图回调：IO 线程读图 + 缩放 + 写盘 → 主线程插入 ImageSpan。
     */
    private fun handleImagePicked(uri: Uri) {
        val note = currentNote
        if (note == null) {
            Toast.makeText(this, R.string.note_image_insert_fail, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val savedFile = withContext(Dispatchers.IO) { savePickedImage(uri, note.id) }
            if (savedFile == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@NoteListActivity,
                        R.string.note_image_insert_fail,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return@launch
            }
            withContext(Dispatchers.Main) { insertImageSpan(savedFile) }
        }
    }

    /**
     * IO 线程：从 [uri] 读图，缩放到最长边 ≤ 1024px，写入
     * `filesDir/notes/images/<noteId>_<ts>.jpg`，返回写出的 [File]。
     * 失败返回 `null`。
     */
    private fun savePickedImage(uri: Uri, noteId: Long): File? {
        return try {
            val input: InputStream = contentResolver.openInputStream(uri) ?: return null
            // 1. 先 decode bounds 算出 sample
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            input.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val sample = calculateInSampleSize(boundsOpts, MAX_IMAGE_EDGE, MAX_IMAGE_EDGE)

            // 2. 重新 decode 缩放后的 bitmap
            val input2 = contentResolver.openInputStream(uri) ?: return null
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val raw: Bitmap = input2.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                ?: return null

            // 3. 二次精确缩放：保证最长边 ≤ MAX_IMAGE_EDGE
            val scaled = scaleBitmapToMaxEdge(raw, MAX_IMAGE_EDGE)
            if (scaled != raw) raw.recycle()

            // 4. 写盘
            val outFile = File(store.imagesDirectory, "${noteId}_${System.currentTimeMillis()}.jpg")
            outFile.outputStream().use { os ->
                scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, os)
            }
            scaled.recycle()
            outFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 主线程：把 [file] 解码为 Bitmap，按 [etContent] 内容宽度等比缩放，包成
     * [ImageSpan] 插入到光标位置。
     */
    private fun insertImageSpan(file: File) {
        try {
            val uri = Uri.fromFile(file)
            // decode bounds
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
            // 真正 decode
            val sample = calculateInSampleSize(boundsOpts, MAX_IMAGE_EDGE, MAX_IMAGE_EDGE)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: run {
                    Toast.makeText(this, R.string.note_image_insert_fail, Toast.LENGTH_SHORT).show()
                    return
                }

            val targetWidth = (binding.etContent.width.takeIf { it > 0 } ?: MAX_IMAGE_EDGE)
                .coerceAtMost(MAX_IMAGE_EDGE)
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val w: Int
            val h: Int
            if (bitmap.width >= bitmap.height) {
                w = targetWidth
                h = (targetWidth / ratio).toInt().coerceAtLeast(1)
            } else {
                h = targetWidth
                w = (targetWidth * ratio).toInt().coerceAtLeast(1)
            }
            val drawable = BitmapDrawable(resources, bitmap)
            drawable.setBounds(0, 0, w, h)
            val span = ImageSpan(drawable, uri.toString())

            val editable: Editable = binding.etContent.editableText
            val cursor = binding.etContent.selectionStart.coerceIn(0, editable.length)
            // 插入对象替换字符 + ImageSpan
            editable.insert(cursor, "\uFFFC")
            editable.setSpan(span, cursor, cursor + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            binding.etContent.setSelection(cursor + 1)

            scheduleSave()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.note_image_insert_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * [Html.ImageGetter]：从 `file://` URI 同步读图（本地文件、性能可接受），
     * 等比缩放到 [etContent] 内容宽度（最长边 ≤ 1024px）。
     */
    private val imageGetter = Html.ImageGetter { source ->
        try {
            val uri = Uri.parse(source)
            val path = uri.path ?: return@ImageGetter transparentPlaceholder()
            val file = File(path)
            if (!file.exists()) return@ImageGetter transparentPlaceholder()

            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, boundsOpts)
            val sample = calculateInSampleSize(boundsOpts, MAX_IMAGE_EDGE, MAX_IMAGE_EDGE)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = BitmapFactory.decodeFile(path, decodeOpts)
                ?: return@ImageGetter transparentPlaceholder()

            val targetWidth = (binding.etContent.width.takeIf { it > 0 } ?: MAX_IMAGE_EDGE)
                .coerceAtMost(MAX_IMAGE_EDGE)
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val w: Int
            val h: Int
            if (bitmap.width >= bitmap.height) {
                w = targetWidth
                h = (targetWidth / ratio).toInt().coerceAtLeast(1)
            } else {
                h = targetWidth
                w = (targetWidth * ratio).toInt().coerceAtLeast(1)
            }
            val drawable = BitmapDrawable(resources, bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable
        } catch (_: Exception) {
            transparentPlaceholder()
        }
    }

    private fun transparentPlaceholder(): Drawable = ColorDrawable(Color.TRANSPARENT).apply {
        setBounds(0, 0, 1, 1)
    }

    // endregion

    // region TextWatcher + 防抖保存

    private fun attachTextWatchers() {
        val titleWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!suppressTextWatcher) scheduleSave() }
        }
        val contentWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!suppressTextWatcher) scheduleSave() }
        }
        binding.etTitle.addTextChangedListener(titleWatcher)
        binding.etContent.addTextChangedListener(contentWatcher)
    }

    private fun scheduleSave() {
        val note = currentNote ?: return
        saveHandler.removeCallbacksAndMessages(null)
        val r = Runnable { persistNote(note) }
        saveRunnable = r
        saveHandler.postDelayed(r, DEBOUNCE_MS)
    }

    /**
     * 立刻触发一次保存（用于切换笔记、删除、Activity 销毁等关键节点）。
     */
    private fun flushPendingSave() {
        saveHandler.removeCallbacksAndMessages(null)
        val note = currentNote ?: return
        persistNote(note)
    }

    /**
     * 把当前右侧编辑器的内容写回 [NoteStore]。主线程触发，IO 线程落盘，回主线程刷新列表项摘要。
     */
    private fun persistNote(note: Note) {
        val title = binding.etTitle.text?.toString().orEmpty().ifEmpty {
            getString(R.string.note_default_title)
        }
        val contentSpanned: Spanned = binding.etContent.text ?: SpannableString("")
        val contentHtml = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.toHtml(
                contentSpanned,
                Html.TO_HTML_PARAGRAPH_LINES_INDIVIDUAL
            )
        } else {
            @Suppress("DEPRECATION")
            Html.toHtml(contentSpanned)
        }
        val updated = note.copy(title = title, contentHtml = contentHtml)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { store.updateNote(updated) }
            if (!ok) {
                Toast.makeText(this@NoteListActivity, R.string.note_save_fail, Toast.LENGTH_SHORT).show()
                return@launch
            }
            currentNote = updated
            // 重新拉取列表（按 updatedAt 倒序）
            val notes = withContext(Dispatchers.IO) { store.listNotes() }
            renderNotes(notes)
        }
    }

    // endregion

    // region 空态

    private fun showEmptyState() {
        binding.tvEmptyHint.visibility = View.VISIBLE
        binding.etTitle.visibility = View.GONE
        binding.svContent.visibility = View.GONE
        binding.toolbar.visibility = View.GONE
    }

    private fun hideEmptyState() {
        binding.tvEmptyHint.visibility = View.GONE
        binding.etTitle.visibility = View.VISIBLE
        binding.svContent.visibility = View.VISIBLE
        binding.toolbar.visibility = View.VISIBLE
    }

    // endregion

    // region 工具方法

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    /**
     * 根据 [BitmapFactory.Options] 的 outWidth/outHeight 与目标宽高，计算
     * [BitmapFactory.Options.inSampleSize]（2 的幂次）。
     */
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val (h, w) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (h > reqHeight || w > reqWidth) {
            val halfH = h / 2
            val halfW = w / 2
            while (halfH / inSampleSize >= reqHeight && halfW / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 用 Canvas 精确缩放 [src] 到最长边 ≤ [maxEdge]。
     * 若 [src] 已经满足条件，直接返回原图。
     */
    private fun scaleBitmapToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge) return src
        val ratio = maxEdge.toFloat() / longest.toFloat()
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    // endregion

    /**
     * 笔记列表 Adapter。
     * - 复用 ViewHolder，避免在 [getView] 中分配对象
     * - 选中态 / 未选中态用 `bg_note_normal.xml` / `bg_note_selected.xml` 区分
     */
    private class NoteAdapter(
        context: android.content.Context,
        items: List<Note>,
    ) : ArrayAdapter<Note>(context, 0, items.toMutableList()) {

        private var selectedId: Long? = null
        private val normalBackground by lazy {
            ContextCompat.getDrawable(context, R.drawable.bg_note_normal)
        }
        private val selectedBackground by lazy {
            ContextCompat.getDrawable(context, R.drawable.bg_note_selected)
        }
        private val iconTint by lazy {
            ColorStateList.valueOf(ContextCompat.getColor(context, R.color.note_text_primary))
        }

        fun setSelected(id: Long?) {
            selectedId = id
            notifyDataSetChanged()
        }

        private class ViewHolder(
            val itemView: View,
            val icon: android.widget.ImageView,
            val title: android.widget.TextView,
            val snippet: android.widget.TextView,
        )

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: ViewHolder
            val view: View
            if (convertView == null) {
                view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_note_row, parent, false)
                holder = ViewHolder(
                    itemView = view,
                    icon = view.findViewById(R.id.ivNoteIcon),
                    title = view.findViewById(R.id.tvNoteTitle),
                    snippet = view.findViewById(R.id.tvNoteSnippet),
                )
                view.tag = holder
            } else {
                view = convertView
                holder = convertView.tag as ViewHolder
            }

            val item = getItem(position) ?: return view
            holder.title.text = item.title.ifEmpty { "新笔记" }
            val snippet = item.contentSnippet
            if (snippet.isEmpty()) {
                holder.snippet.text = ""
                holder.snippet.visibility = View.GONE
            } else {
                holder.snippet.text = snippet
                holder.snippet.visibility = View.VISIBLE
            }
            holder.icon.imageTintList = iconTint
            holder.itemView.background = if (item.id == selectedId) {
                selectedBackground
            } else {
                normalBackground
            }
            return view
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 600L
        private const val MAX_IMAGE_EDGE = 1024
        private const val IMAGE_QUALITY = 85
    }
}
