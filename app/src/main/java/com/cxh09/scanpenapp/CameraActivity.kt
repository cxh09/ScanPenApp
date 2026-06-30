package com.cxh09.scanpenapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.cxh09.scanpenapp.databinding.ActivityCameraBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 相机页面。
 *
 * - 使用 CameraX 1.4 (Preview + ImageCapture) 实现取景与拍照。
 * - 完整处理 CAMERA 运行时权限：未授权时通过 `requestCameraPermission` 拉起系统弹窗，
 *   拒绝后展示 `permissionPanel` 引导跳系统设置。
 * - 拍照结果通过 MediaStore 写入 DCIM/ScanAppCamera 目录（API ≥ Q 自动 IS_PENDING 流程），
 *   不需要 WRITE_EXTERNAL_STORAGE 权限。
 * - 左侧 80dp 边栏 4 按钮：退出 / 拍照 / 切换前后摄 / 相册（跳 CameraAlbumActivity）
 */
class CameraActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCameraBinding
    private lateinit var cameraExecutor: ExecutorService

    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            showPermissionPanel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.btnExit.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnSwitchCamera.setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                CameraSelector.LENS_FACING_FRONT
            else
                CameraSelector.LENS_FACING_BACK
            bindCamera()
        }
        binding.btnAlbum.setOnClickListener {
            startActivity(Intent(this, CameraAlbumActivity::class.java))
        }
        binding.btnPermissionGoto.setOnClickListener { openAppSettings() }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStop() {
        super.onStop()
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        cameraExecutor.shutdown()
    }

    private fun startCamera() {
        binding.permissionPanel.visibility = View.GONE
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            cameraProvider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Log.e(TAG, "Provider init failed", e)
                return@addListener
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val capture = imageCapture ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, capture)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val name = "IMG_${System.currentTimeMillis()}.jpg"
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ScanAppCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .build()

        binding.btnShutter.isEnabled = false
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    binding.btnShutter.isEnabled = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val uri = output.savedUri ?: return
                        val update = android.content.ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        contentResolver.update(uri, update, null, null)
                    }
                    Toast.makeText(this@CameraActivity, R.string.camera_saved_to_album, Toast.LENGTH_SHORT).show()
                }

                override fun onError(exc: ImageCaptureException) {
                    binding.btnShutter.isEnabled = true
                    Log.e(TAG, "takePicture failed", exc)
                    Toast.makeText(this@CameraActivity, R.string.camera_save_fail, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun showPermissionPanel() {
        binding.permissionPanel.visibility = View.VISIBLE
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CameraActivity"
    }
}
