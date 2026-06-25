package com.cxh09.scanpenapp

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.util.DisplayMetrics
import androidx.appcompat.app.AppCompatActivity
import com.cxh09.scanpenapp.databinding.ActivityAboutDeviceBinding
import com.cxh09.scanpenapp.databinding.ItemAboutRowBinding
import java.util.Locale

/**
 * 关于本机页面。
 *
 * 仅展示核心设备信息：厂商、型号、Android 版本、内存、存储、屏幕分辨率、应用版本。
 * 数据全部来自平台 API（[Build] / [PackageManager] / [ActivityManager] / [StatFs] / [DisplayMetrics]），
 * 不申请额外权限，不发起网络请求。
 */
class AboutDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutDeviceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        fillAll()
    }

    private fun fillAll() {
        // 设备
        bind(
            binding.rowManufacturer,
            R.string.about_label_manufacturer,
            Build.MANUFACTURER.uppercase(Locale.ROOT)
        )
        bind(binding.rowModel, R.string.about_label_model, Build.MODEL.orUnknown())

        // 系统
        bind(
            binding.rowAndroidVersion,
            R.string.about_label_android_version,
            Build.VERSION.RELEASE.orUnknown()
        )

        // 内存
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        bind(binding.rowTotalMem, R.string.about_label_total_mem, formatSize(memInfo.totalMem))
        bind(binding.rowAvailMem, R.string.about_label_avail_mem, formatSize(memInfo.availMem))

        // 存储
        val (totalBytes, availBytes) = readInternalStorage()
        bind(binding.rowTotalStorage, R.string.about_label_total_storage, formatSize(totalBytes))
        bind(binding.rowAvailStorage, R.string.about_label_avail_storage, formatSize(availBytes))

        // 屏幕
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        bind(
            binding.rowResolution,
            R.string.about_label_resolution,
            "${metrics.widthPixels} × ${metrics.heightPixels}"
        )

        // 应用
        val versionName = readAppVersionName()
        bind(binding.rowAppVersion, R.string.about_label_app_version, versionName)
    }

    private fun readInternalStorage(): Pair<Long, Long> {
        val path = filesDir.absolutePath
        val statFs = runCatching { StatFs(path) }.getOrNull() ?: return 0L to 0L
        val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
        val availBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        return totalBytes to availBytes
    }

    private fun readAppVersionName(): String {
        val info: PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
        return info?.versionName.orUnknown()
    }

    private fun bind(row: ItemAboutRowBinding, labelRes: Int, value: String) {
        row.tvAboutLabel.setText(labelRes)
        row.tvAboutValue.text = value
    }

    private fun String?.orUnknown(): String {
        if (this.isNullOrBlank()) return getString(R.string.about_value_unknown)
        return this
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return getString(R.string.about_value_unknown)
        val units = arrayOf(
            getString(R.string.about_size_bytes, "%.0f"),
            getString(R.string.about_size_kb, "%.1f"),
            getString(R.string.about_size_mb, "%.1f"),
            getString(R.string.about_size_gb, "%.2f"),
            getString(R.string.about_size_tb, "%.2f"),
        )
        val digitGroup = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        val safeGroup = digitGroup.coerceIn(0, units.size - 1)
        val value = bytes / Math.pow(1024.0, safeGroup.toDouble())
        return String.format(Locale.ROOT, units[safeGroup], value)
    }
}
