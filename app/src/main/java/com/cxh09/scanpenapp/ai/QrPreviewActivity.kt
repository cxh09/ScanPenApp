package com.cxh09.scanpenapp.ai

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivityQrPreviewBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * 二维码全屏预览页。
 *
 * - 通过 Intent 接收原始 payload 字符串（避免 Bitmap 走 Binder 超过 1MB 上限）。
 * - 点击任意位置关闭。
 * - 沉浸式全屏:隐藏状态栏,让二维码占满屏幕,提升识别成功率。
 * - 以更大尺寸重新渲染 QR 码（[PREVIEW_QR_SIZE_PX]），保证全屏清晰。
 */
class QrPreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrPreviewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemBars()

        val payload = intent.getStringExtra(EXTRA_PAYLOAD).orEmpty()
        if (payload.isBlank()) {
            finish()
            return
        }

        val bitmap = generateQrBitmap(payload, PREVIEW_QR_SIZE_PX)
        if (bitmap != null) {
            binding.ivQrCode.setImageBitmap(bitmap)
        } else {
            Log.e(TAG, "Failed to render preview QR")
            finish()
        }

        binding.root.setOnClickListener { finish() }
    }

    /**
     * 沉浸式:隐藏状态栏,内容延伸到屏幕顶部。
     * 词典笔横屏窄长,状态栏会挤掉二维码的上下空间,全屏显示更易扫描。
     */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun generateQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, size, size, hints
            )
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "QR preview generate failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "QrPreviewActivity"
        const val EXTRA_PAYLOAD = "extra_payload"
        private const val PREVIEW_QR_SIZE_PX = 1080
    }
}
