package com.cxh09.scanpenapp.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.cxh09.scanpenapp.R
import com.cxh09.scanpenapp.databinding.ActivityQrScanBinding
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 二维码扫描页。
 *
 * - 使用 CameraX + ML Kit 端侧识别，纯本地、无网络。
 * - 完整处理 CAMERA 运行时权限：
 *   1. 已授权 → 直接进入相机。
 *   2. 未授权 → 系统弹窗；用户拒绝后用 rationale 解释；
 *      永久拒绝（"不再询问"）后引导跳系统应用设置页。
 *   3. 全部拒绝路径下显示权限引导面板，而非直接 finish。
 */
class QrScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScanBinding
    private lateinit var cameraExecutor: ExecutorService
    private var hasReturned = false

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                showScanUi()
                startCamera()
            } else {
                handlePermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBack2.setOnClickListener { finish() }
        binding.btnGrant.setOnClickListener { requestCameraPermissionOrGuide() }

        ensureCameraPermission()
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置页返回时再确认一次（用户在设置里手动开启相机权限）
        if (hasCameraPermission() && binding.permissionPanel.visibility == View.VISIBLE) {
            showScanUi()
            startCamera()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // ============ 权限流程 ============

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) {
            showScanUi()
            startCamera()
            return
        }
        // 第一次 / 之前拒绝过但未勾"不再询问"：先看是否需要 rationale
        if (shouldShowRationale()) {
            showRationaleThenRequest()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** 引导面板里的"授权"按钮：每次点击都重新判断该走哪条路。 */
    private fun requestCameraPermissionOrGuide() {
        if (hasCameraPermission()) {
            showScanUi()
            startCamera()
            return
        }
        if (shouldShowRationale()) {
            // 系统仍允许弹窗 → 直接请求
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        } else {
            // 用户已勾"不再询问"：只能去设置页
            openAppSettings()
        }
    }

    private fun handlePermissionDenied() {
        if (shouldShowRationale()) {
            // 还能再请求一次，但先给个解释
            showRationaleThenRequest()
        } else {
            // "不再询问"或首次拒绝就走引导面板
            showPermissionPanel(permanentlyDenied = true)
        }
    }

    private fun showRationaleThenRequest() {
        AlertDialog.Builder(this)
            .setTitle(R.string.qr_scan_permission_title)
            .setMessage(R.string.qr_scan_permission_rationale)
            .setPositiveButton(R.string.qr_scan_permission_grant) { _, _ ->
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
            .setNegativeButton(R.string.qr_scan_cancel) { _, _ ->
                showPermissionPanel(permanentlyDenied = false)
            }
            .setCancelable(false)
            .show()
    }

    private fun showPermissionPanel(permanentlyDenied: Boolean) {
        binding.scanOverlay.visibility = View.GONE
        binding.permissionPanel.visibility = View.VISIBLE
        binding.tvPermissionDesc.setText(
            if (permanentlyDenied) R.string.qr_scan_permission_blocked_desc
            else R.string.qr_scan_permission_denied_desc
        )
        binding.btnGrant.setText(
            if (permanentlyDenied) R.string.qr_scan_permission_open_settings
            else R.string.qr_scan_permission_grant
        )
    }

    private fun showScanUi() {
        binding.permissionPanel.visibility = View.GONE
        binding.scanOverlay.visibility = View.VISIBLE
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun shouldShowRationale(): Boolean =
        androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
            this, Manifest.permission.CAMERA
        )

    // ============ 相机 + 扫码 ============

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "CameraProvider init failed", e)
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, BarcodeAnalyzer(::onBarcodeDetected)) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodeDetected(value: String) {
        if (hasReturned || isFinishing || isDestroyed) return
        hasReturned = true
        runOnUiThread {
            val data = Intent().putExtra(EXTRA_SCAN_RESULT, value)
            setResult(RESULT_OK, data)
            finish()
        }
    }

    /**
     * ML Kit 图像分析器：把每一帧送入 BarcodeScanner，仅识别 QR_CODE。
     */
    private class BarcodeAnalyzer(
        private val onResult: (String) -> Unit
    ) : ImageAnalysis.Analyzer {

        private val scanner: BarcodeScanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
        )

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }
            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(input)
                .addOnSuccessListener { barcodes ->
                    barcodes.firstOrNull()?.rawValue?.takeIf { it.isNotBlank() }?.let(onResult)
                }
                .addOnCompleteListener { imageProxy.close() }
        }
    }

    companion object {
        private const val TAG = "QrScanActivity"
        const val EXTRA_SCAN_RESULT = "scan_result"
    }
}
