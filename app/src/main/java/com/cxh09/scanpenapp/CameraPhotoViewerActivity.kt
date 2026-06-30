package com.cxh09.scanpenapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cxh09.scanpenapp.databinding.ActivityCameraPhotoViewerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 相机照片全屏查看页。
 *
 * - 启动入口：[CameraAlbumActivity] 缩略图点击事件，
 *   通过 [EXTRA_PHOTO_URI] 传入照片 [Uri] 字符串。
 * - 加载：IO 线程用 [BitmapFactory.decodeStream] 按屏宽计算
 *   `inSampleSize` 缩放解码，避免 OOM。
 * - 关闭：返回按钮 / 主体任意位置点击都 `finish()`；不内置删除按钮
 *   （删除统一在 [CameraAlbumActivity] 长按入口完成）。
 * - 释放：[onDestroy] 中将 ImageView 引用置空，便于 Bitmap GC 回收。
 */
class CameraPhotoViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraPhotoViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraPhotoViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.setText(R.string.camera_viewer_title)
        binding.contentRoot.setOnClickListener { finish() }

        val uriString = intent.getStringExtra(EXTRA_PHOTO_URI)
        if (uriString.isNullOrEmpty()) {
            finish()
            return
        }
        loadPhoto(uriString)
    }

    /**
     * IO 线程按屏宽计算 `inSampleSize` 后解码原图，主线程 setImageBitmap。
     * 解码失败时 Toast `camera_save_fail` 并关闭页面。
     */
    private fun loadPhoto(uriString: String) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                decodeBitmap(uriString)
            }
            if (bmp != null) {
                binding.ivPhoto.setImageBitmap(bmp)
            } else {
                Toast.makeText(
                    this@CameraPhotoViewerActivity,
                    R.string.camera_save_fail,
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }
    }

    /**
     * 按屏宽 [resources.displayMetrics.widthPixels] 缩放解码：
     * - 第一遍 `inJustDecodeBounds = true` 拿原图宽高
     * - 计算 `inSampleSize`（最小为 1，向下取整）
     * - 第二遍用 [BitmapFactory.Options] 真解码
     * 返回 `null` 表示解码失败（Uri 失效 / 权限缺失 / 资源损坏）。
     */
    private fun decodeBitmap(uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        val displayWidth = resources.displayMetrics.widthPixels

        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            if (input != null) {
                BitmapFactory.decodeStream(input, null, boundsOpts)
            }
        }

        var inSampleSize = 1
        val origWidth = boundsOpts.outWidth
        if (origWidth > 0 && displayWidth > 0) {
            var half = origWidth / 2
            while (half / inSampleSize >= displayWidth) {
                inSampleSize *= 2
                half /= 2
            }
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return contentResolver.openInputStream(uri).use { input ->
            if (input != null) {
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } else {
                null
            }
        }
    }

    override fun onDestroy() {
        binding.ivPhoto.setImageBitmap(null)
        super.onDestroy()
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
        /** Intent extra key：照片 Uri 字符串（必传）。 */
        const val EXTRA_PHOTO_URI = "extra_photo_uri"
    }
}
