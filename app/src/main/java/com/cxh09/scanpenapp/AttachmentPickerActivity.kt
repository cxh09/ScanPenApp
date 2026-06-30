package com.cxh09.scanpenapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cxh09.scanpenapp.databinding.ActivityAttachmentPickerBinding
import com.cxh09.scanpenapp.databinding.ItemAttachmentPickerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自建附件选择器（替代系统 SAF）。
 *
 * 布局：
 * - 顶栏 48dp：「← 返回」+ 面包屑（只显示当前目录名，根目录 = 「内部存储」）
 * - 主体：RecyclerView 4 列网格（`GridLayoutManager(this, 4)`），每格 80×80dp
 * - 空态 / 无权限态：居中两行文案（与 RecyclerView 互斥）
 *
 * 数据：
 * - IO 线程 `File.listFiles()` → 主线程 `adapter.submit`
 * - 文件夹排在前面，文件排在后面；按名称字典序（`CASE_INSENSITIVE_ORDER`）
 * - 隐藏文件过滤（`.` 开头）
 *
 * 交互：
 * - 点击文件夹：推入 `pathStack` + 重新列举
 * - 点击文件：`setResult + finish`，`Uri.fromFile(file)` 通过 `EXTRA_FILE_URI` 回传
 * - 顶栏返回：`pathStack` 非空时弹一层 / 空时 `finish()`
 *
 * 性能：
 * - 文件列举全部在 `Dispatchers.IO` 协程
 * - `onBindViewHolder` 中无对象分配 / 无 IO / 无图片解码
 * - 不引入 Glide / 自实现 LruCache（卡片只渲染图标 + 名称，不需要缩略图）
 */
class AttachmentPickerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAttachmentPickerBinding
    private var adapter: AttachmentPickerAdapter? = null

    /** 当前目录，初始为外部存储根目录（若可访问）。 */
    private var currentPath: File = Environment.getExternalStorageDirectory()

    /** 进入子目录时压入栈，根目录返回时弹栈。 */
    private val pathStack: ArrayDeque<File> = ArrayDeque()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAttachmentPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        // 初始目录：优先取 Intent extra，否则走外部存储根目录
        intent?.getStringExtra(EXTRA_START_PATH)?.let { p ->
            val file = File(p)
            if (file.exists() && file.isDirectory) currentPath = file
        }

        binding.btnBack.setOnClickListener { onBackClicked() }

        // 列表初始化
        binding.rvFiles.setHasFixedSize(true)
        binding.rvFiles.layoutManager = GridLayoutManager(this, GRID_COLUMNS)

        loadCurrentDirectory()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /**
     * 顶栏返回按钮：
     * - `pathStack` 非空：弹一层 + 重新列举
     * - `pathStack` 为空：`finish()` 回到调用方
     */
    private fun onBackClicked() {
        if (pathStack.isNotEmpty()) {
            val popped = pathStack.removeLast()
            currentPath = popped
            loadCurrentDirectory()
        } else {
            finish()
        }
    }

    /**
     * 异步加载当前目录：IO 线程列举 + 主线程渲染。
     */
    private fun loadCurrentDirectory() {
        updateBreadcrumb()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                listDirectory(currentPath)
            }
            renderDirectory(result)
        }
    }

    /**
     * 在 IO 线程列举目录，返回 [ListResult] 表示列举结果：
     * - [ListResult.Empty]：目录为空
     * - [ListResult.NoAccess]：无权限 / IO 异常
     * - [ListResult.Items]：正常列表（文件夹在前，文件在后，按名称字典序）
     */
    private fun listDirectory(dir: File): ListResult {
        val children = try {
            dir.listFiles() ?: return ListResult.NoAccess
        } catch (_: SecurityException) {
            return ListResult.NoAccess
        }
        if (children.isEmpty()) return ListResult.Empty
        val visible = children.filter { !it.name.startsWith(".") }
        if (visible.isEmpty()) return ListResult.Empty
        val sorted = visible.sortedWith(
            compareBy({ if (it.isDirectory) 0 else 1 }, { it.name.lowercase() }),
        )
        return ListResult.Items(sorted)
    }

    /**
     * 主线程根据 [result] 决定显示列表 / 空态 / 无权限态。
     */
    private fun renderDirectory(result: ListResult) {
        when (result) {
            is ListResult.Empty -> {
                binding.emptyContainer.visibility = View.VISIBLE
                binding.tvEmpty.setText(R.string.attach_picker_empty)
                binding.tvEmptyHint.visibility = View.GONE
                binding.rvFiles.visibility = View.GONE
            }
            is ListResult.NoAccess -> {
                binding.emptyContainer.visibility = View.VISIBLE
                binding.tvEmpty.setText(R.string.attach_picker_no_access)
                binding.tvEmptyHint.visibility = View.VISIBLE
                binding.tvEmptyHint.setText(R.string.attach_picker_need_permission)
                binding.rvFiles.visibility = View.GONE
            }
            is ListResult.Items -> {
                binding.emptyContainer.visibility = View.GONE
                binding.rvFiles.visibility = View.VISIBLE
                if (adapter == null) {
                    val a = AttachmentPickerAdapter(
                        onFolderClick = { folder -> enterFolder(folder) },
                        onFileClick = { file -> pickFile(file) },
                    )
                    adapter = a
                    binding.rvFiles.adapter = a
                }
                adapter?.submit(result.list)
                binding.rvFiles.scrollToPosition(0)
            }
        }
    }

    /**
     * 进入子目录：压栈 + 切路径 + 重新列举。
     */
    private fun enterFolder(folder: File) {
        pathStack.addLast(currentPath)
        currentPath = folder
        loadCurrentDirectory()
    }

    /**
     * 选择文件：`setResult + finish`，把 `Uri.fromFile(file)` 通过 `EXTRA_FILE_URI` 回传。
     *
     * 注意：`Uri.fromFile` 只能在 `MANAGE_EXTERNAL_STORAGE` 已授予的前提下被调用方读取。
     * 本应用已经在 AndroidManifest 声明了此权限，调用方 `AskAiActivity.loadTextAttachment`
     * 走 `ContentResolver.openInputStream(uri)`，对 `file://` Uri 同样可读。
     */
    private fun pickFile(file: File) {
        val uri = Uri.fromFile(file)
        val data = Intent().putExtra(EXTRA_FILE_URI, uri.toString())
        setResult(RESULT_OK, data)
        finish()
    }

    /**
     * 顶栏面包屑渲染：
     * - 根目录 → 「内部存储」
     * - 子目录 → 「内部存储 / 子目录名」（仅显示最后一层，符合词典笔窄屏）
     */
    private fun updateBreadcrumb() {
        val text = if (pathStack.isEmpty()) {
            getString(R.string.attach_picker_root)
        } else {
            getString(R.string.attach_picker_path_template, currentPath.name)
        }
        binding.tvBreadcrumb.text = text
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
     * 列举结果封装。
     */
    private sealed class ListResult {
        data class Items(val list: List<File>) : ListResult()
        object Empty : ListResult()
        object NoAccess : ListResult()
    }

    companion object {
        private const val GRID_COLUMNS = 4

        /** Intent extra：起始目录（绝对路径），可选。 */
        const val EXTRA_START_PATH: String = "extra_start_path"

        /** Result extra：选中的文件 Uri（String 形式），setResult(RESULT_OK) 时附带。 */
        const val EXTRA_FILE_URI: String = "extra_file_uri"
    }
}

/**
 * 文件浏览器网格 Adapter。
 *
 * - ViewHolder 复用 `ItemAttachmentPickerBinding`（`viewBinding=true`）
 * - `onBindViewHolder` 中不分配对象 / 不做 IO，只 `setText` + `setImageResource`
 * - 点击走 ViewHolder 闭包内 `onFolderClick` / `onFileClick`
 */
class AttachmentPickerAdapter(
    private val onFolderClick: (File) -> Unit,
    private val onFileClick: (File) -> Unit,
) : RecyclerView.Adapter<AttachmentPickerAdapter.FileVH>() {

    private val items = mutableListOf<File>()

    fun submit(list: List<File>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileVH {
        val binding = ItemAttachmentPickerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return FileVH(binding)
    }

    override fun onBindViewHolder(holder: FileVH, position: Int) {
        val file = items[position]
        holder.bind(file)
    }

    override fun getItemCount(): Int = items.size

    inner class FileVH(
        val binding: ItemAttachmentPickerBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(file: File) {
            binding.tvName.text = file.name
            if (file.isDirectory) {
                binding.ivIcon.setImageResource(R.drawable.ic_folder)
                binding.root.setOnClickListener { onFolderClick(file) }
            } else {
                binding.ivIcon.setImageResource(iconResFor(file.name))
                binding.root.setOnClickListener { onFileClick(file) }
            }
        }

        /**
         * 根据文件后缀返回对应图标资源：
         * - jpg/jpeg/png/webp/gif/bmp → `ic_file_image`
         * - txt/md/json/csv/log → `ic_file_doc`
         * - 其它 → `ic_file_generic`
         */
        private fun iconResFor(name: String): Int {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "jpg", "jpeg", "png", "webp", "gif", "bmp" -> R.drawable.ic_file_image
                "txt", "md", "json", "csv", "log" -> R.drawable.ic_file_doc
                else -> R.drawable.ic_file_generic
            }
        }
    }
}
