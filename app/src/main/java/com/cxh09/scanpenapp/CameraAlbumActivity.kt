package com.cxh09.scanpenapp

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cxh09.scanpenapp.databinding.ActivityCameraAlbumBinding
import com.cxh09.scanpenapp.databinding.ItemCameraPhotoBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用内相册（ScanAppCamera 目录）页面。
 *
 * 布局：
 * - 顶部 48dp 工具栏：返回 + 标题 + 数量 + 1dp 分隔线（与 [BookmarkListActivity] 同款）
 * - 主体：RecyclerView 2 列网格（`GridLayoutManager(this, 2)`）
 * - 空态：「还没有照片」+ 引导文案居中
 *
 * 数据：
 * - `MediaStore.Images` 查询 `RELATIVE_PATH LIKE "DCIM/ScanAppCamera%"`，按 `DATE_ADDED DESC`
 * - IO 线程查询 → 主线程渲染（`lifecycleScope.launch` + `Dispatchers.IO`）
 *
 * 缩略图：
 * - `LruCache<String, Bitmap>(2MB)` 类字段缓存，key = `uri.toString()`
 * - 未命中走 `ContentResolver.loadThumbnail(Size(256, 256))`（API 29+），
 *   API < 29 兜底 `BitmapFactory.decodeFile` + `inSampleSize = 4`
 * - 缩略图加载全部在 IO 协程内，主线程只做 `setImageBitmap`
 *
 * 删除：
 * - 长按条目弹 [AlertDialog]（`Theme_ScanPenApp_AlertDialog_Dark`）二次确认
 * - 确认后 IO 协程 `contentResolver.delete(uri, null, null)`，主线程重新 `loadPhotos()`
 *
 * 注意：
 * - `CameraPhotoViewerActivity` / `EXTRA_PHOTO_URI` 由后续任务提供，**仅引用不编译**。
 */
class CameraAlbumActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraAlbumBinding

    private var photoAdapter: CameraPhotoAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraAlbumBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.setText(R.string.camera_album_title)
        binding.tvCount.text = getString(R.string.ai_bookmark_view_entry_count, 0)

        // 列表初始化（空态由数据驱动）
        binding.rvPhotos.setHasFixedSize(true)
        binding.rvPhotos.setItemViewCacheSize(ITEM_CACHE_SIZE)
        binding.rvPhotos.layoutManager = GridLayoutManager(this, GRID_COLUMNS)
    }

    override fun onResume() {
        super.onResume()
        // 用户从相机返回时可能带了新照片，重新拉一次
        loadPhotos()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    /**
     * 加载相册列表（IO 线程），完成后切回主线程渲染。
     */
    private fun loadPhotos() {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                queryScanAppCameraPhotos(contentResolver)
            }
            renderPhotos(items)
        }
    }

    /**
     * 在主线程根据 [items] 决定显示列表还是空态。
     */
    private fun renderPhotos(items: List<CameraPhoto>) {
        binding.tvCount.text = getString(R.string.ai_bookmark_view_entry_count, items.size)
        if (items.isEmpty()) {
            binding.emptyContainer.visibility = View.VISIBLE
            binding.rvPhotos.visibility = View.GONE
            return
        }
        binding.emptyContainer.visibility = View.GONE
        binding.rvPhotos.visibility = View.VISIBLE
        if (photoAdapter == null) {
            val adapter = CameraPhotoAdapter(
                onClick = { photo -> openPhotoViewer(photo) },
                onLongClick = { photo -> showDeleteConfirm(photo) },
            )
            photoAdapter = adapter
            binding.rvPhotos.adapter = adapter
        }
        photoAdapter?.submit(items)
    }

    /**
     * 跳全屏照片查看。
     */
    private fun openPhotoViewer(photo: CameraPhoto) {
        val intent = Intent(this, CameraPhotoViewerActivity::class.java)
            .putExtra(CameraPhotoViewerActivity.EXTRA_PHOTO_URI, photo.uri.toString())
        startActivity(intent)
    }

    /**
     * 弹出删除确认对话框。确认后调 [confirmDelete]。
     */
    private fun showDeleteConfirm(photo: CameraPhoto) {
        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_AlertDialog_Dark)
            .setTitle(R.string.camera_album_delete_title)
            .setMessage(R.string.camera_album_delete_message)
            .setPositiveButton(R.string.camera_album_delete_confirm) { _, _ -> confirmDelete(photo) }
            .setNegativeButton(R.string.camera_album_delete_cancel, null)
            .show()
    }

    /**
     * 删除一张照片：IO 线程 `contentResolver.delete` → 主线程重新加载。
     */
    private fun confirmDelete(photo: CameraPhoto) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { contentResolver.delete(photo.uri, null, null) }
            }
            loadPhotos()
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
        private const val GRID_COLUMNS = 2
        private const val ITEM_CACHE_SIZE = 8
        private const val THUMB_CACHE_SIZE_BYTES = 4 * 1024 * 1024 / 2 // 2MB
        private const val THUMB_TARGET_SIZE = 256
        private const val LEGACY_IN_SAMPLE_SIZE = 4

        /**
         * 查询 MediaStore.Images 中 `DCIM/ScanAppCamera*` 下的所有照片，按 `DATE_ADDED DESC` 排序。
         */
        fun queryScanAppCameraPhotos(resolver: ContentResolver): List<CameraPhoto> {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH,
            )
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("DCIM/ScanAppCamera%")
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
            val items = mutableListOf<CameraPhoto>()
            var cursor: Cursor? = null
            try {
                cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    args,
                    sortOrder,
                ) ?: return emptyList()
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: continue
                    val dateAddedSec = cursor.getLong(dateCol)
                    val uri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString(),
                    )
                    items.add(
                        CameraPhoto(
                            id = id,
                            uri = uri,
                            displayName = name,
                            dateAddedMs = dateAddedSec * 1000L,
                        ),
                    )
                }
            } catch (_: SecurityException) {
                // 没有 READ_MEDIA_IMAGES 权限 → 当作空列表
                return emptyList()
            } finally {
                cursor?.close()
            }
            return items
        }

        /**
         * 类级缩略图 LruCache，进程内复用；key = `uri.toString()`。
         */
        private val thumbCache: LruCache<String, Bitmap> =
            object : LruCache<String, Bitmap>(THUMB_CACHE_SIZE_BYTES) {
                override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
            }

        /**
         * 加载缩略图：先查缓存，未命中走平台 API / 兼容路径。
         */
        fun loadThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
            val key = uri.toString()
            thumbCache.get(key)?.let { cached ->
                if (!cached.isRecycled) return cached
                thumbCache.remove(key)
            }
            val bmp = decodeThumbnail(resolver, uri) ?: return null
            thumbCache.put(key, bmp)
            return bmp
        }

        private fun decodeThumbnail(resolver: ContentResolver, uri: Uri): Bitmap? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    resolver.loadThumbnail(uri, Size(THUMB_TARGET_SIZE, THUMB_TARGET_SIZE), null)
                }.getOrNull()
            } else {
                val path = getRealPathFromUri(resolver, uri) ?: return null
                val opts = BitmapFactory.Options().apply { inSampleSize = LEGACY_IN_SAMPLE_SIZE }
                BitmapFactory.decodeFile(path, opts)
            }
        }

        /**
         * API < 29 时通过 `_data` 列拿真实文件路径，给 `BitmapFactory.decodeFile` 兜底。
         */
        private fun getRealPathFromUri(resolver: ContentResolver, uri: Uri): String? {
            val dataCol = resolver.getCursorColumnIndex(uri, "_data") ?: return null
            return resolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(dataCol) else null
            }
        }

        private fun ContentResolver.getCursorColumnIndex(uri: Uri, column: String): Int? {
            return query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(column)
                if (idx < 0) null else idx
            }
        }
    }
}

/**
 * 一张照片的元数据。
 */
data class CameraPhoto(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val dateAddedMs: Long,
)

/**
 * 相册网格 Adapter。
 *
 * - ViewHolder 复用 `ItemCameraPhotoBinding`（`viewBinding=true`）
 * - `onBindViewHolder` 中**不分配** `SimpleDateFormat` / 不解码图片，只做 setText + 调协程
 * - 缩略图加载走 [bindThumbAsync] 协程，主线程只 `setImageBitmap`
 * - 时间格式化器在 ViewHolder 内 `lazy` 一次（同一 ViewHolder 复用不重分配）
 */
class CameraPhotoAdapter(
    private val onClick: (CameraPhoto) -> Unit,
    private val onLongClick: (CameraPhoto) -> Unit,
) : RecyclerView.Adapter<CameraPhotoAdapter.PhotoVH>() {

    private val items = mutableListOf<CameraPhoto>()

    fun submit(list: List<CameraPhoto>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
        val binding = ItemCameraPhotoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false,
        )
        return PhotoVH(binding)
    }

    override fun onBindViewHolder(holder: PhotoVH, position: Int) {
        val item = items[position]
        val binding = holder.binding
        binding.tvTime.text = holder.timeFormat.format(Date(item.dateAddedMs))
        binding.tvName.text = item.displayName
        // 清掉上一张的缓存图，避免闪烁
        binding.ivThumb.setImageResource(android.R.color.transparent)
        // 点击 / 长按走卡片根（FrameLayout 已设 clickable+foreground ripple）
        binding.root.setOnClickListener { onClick(item) }
        binding.root.setOnLongClickListener {
            onLongClick(item)
            true
        }
        // 缩略图实际加载放协程里
        bindThumbAsync(
            context = binding.root.context,
            uri = item.uri,
            imageView = binding.ivThumb,
        )
    }

    override fun getItemCount(): Int = items.size

    /**
     * ViewHolder：缓存 `SimpleDateFormat` 引用（lazy 一次）。
     */
    class PhotoVH(
        val binding: ItemCameraPhotoBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        val timeFormat: SimpleDateFormat by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        }
    }

    companion object {
        /**
         * 异步加载缩略图，IO 线程解码 + 主线程 setImageBitmap。
         *
         * 关联到 Activity / View lifecycle：
         * - `context` 是 View 的 context，对 Activity 而言持有 `lifecycleScope`；
         * - `ImageView` 可能在协程返回前被复用，所以 setImageBitmap 前再确认一下
         *   `imageView.tag == uri` 一致再赋值，避免错位
         */
        fun bindThumbAsync(context: Context, uri: Uri, imageView: ImageView) {
            val lifecycleOwner = context as? androidx.lifecycle.LifecycleOwner ?: return
            imageView.tag = uri
            lifecycleOwner.lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    CameraAlbumActivity.loadThumbnail(context.contentResolver, uri)
                }
                if (bmp == null) return@launch
                if (imageView.tag == uri) {
                    imageView.setImageBitmap(bmp)
                }
            }
        }
    }
}
