package com.cxh09.scanpenapp.ai

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.cxh09.scanpenapp.R
import com.cxh09.scanpenapp.databinding.ActivityQrShareBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

/**
 * 配置分享页：把当前 [ApiConfig] 序列化为 JSON 并渲染成 QR 码。
 *
 * - 内容格式与 [QrScanActivity] / [AiSettingsActivity.applyScannedJson] 保持一致：
 *   `{"key","url","model"}`。
 * - 二维码生成在主线程（一次性，~240x240，约 5–20ms），不放后台，避免页面切换闪烁。
 */
class QrShareActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrShareBinding
    private lateinit var store: ApiConfigStore
    private var currentPayload: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        store = ApiConfigStore(this)
        binding.btnBack.setOnClickListener { finish() }
        binding.ivQrCode.setOnClickListener {
            if (currentPayload.isNotBlank()) {
                startActivity(
                    Intent(this, QrPreviewActivity::class.java)
                        .putExtra(QrPreviewActivity.EXTRA_PAYLOAD, currentPayload)
                )
            }
        }

        renderCurrentConfig()
    }

    private fun renderCurrentConfig() {
        val config = store.load()
        val key = config.apiKey.trim()
        val url = config.baseUrl.trim()
        val model = config.model.trim()

        // 摘要信息
        binding.tvKey.text = getString(R.string.qr_share_summary_key, maskKey(key))
        binding.tvUrl.text = getString(R.string.qr_share_summary_url, url.ifBlank { "—" })
        binding.tvModel.text = getString(R.string.qr_share_summary_model, model.ifBlank { "—" })

        if (key.isBlank() && url.isBlank() && model.isBlank()) {
            binding.ivQrCode.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
            currentPayload = ""
            return
        }

        currentPayload = JSONObject().apply {
            put("key", key)
            put("url", url)
            put("model", model)
        }.toString()

        val bitmap = generateQrBitmap(currentPayload, QR_SIZE_PX)
        if (bitmap != null) {
            binding.ivQrCode.setImageBitmap(bitmap)
            binding.ivQrCode.visibility = View.VISIBLE
            binding.tvEmpty.visibility = View.GONE
        } else {
            binding.ivQrCode.visibility = View.GONE
            binding.tvEmpty.visibility = View.VISIBLE
        }
    }

    private fun maskKey(key: String): String {
        if (key.length <= 8) return key.ifBlank { "—" }
        return key.take(4) + "…" + key.takeLast(4)
    }

    /**
     * 使用 ZXing 把字符串渲染成正方形黑白 [Bitmap]。
     * 失败时返回 null（参数非法 / 内容过长）。
     */
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
            Log.e(TAG, "QR generate failed", e)
            null
        }
    }

    companion object {
        private const val TAG = "QrShareActivity"
        private const val QR_SIZE_PX = 720
    }
}
