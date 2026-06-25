package com.cxh09.scanpenapp

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.cxh09.scanpenapp.databinding.ActivityAboutDeviceBinding
import com.cxh09.scanpenapp.databinding.ItemAboutRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 关于本机页面。
 *
 * 收集并展示设备基本信息（厂商、型号、系统版本、内存、存储、屏幕、应用版本等）。
 *
 * - 全部数据来自 [Build] / [PackageManager] / [ActivityManager] / [StatFs] 等平台 API，
 *   不申请额外权限，不发起网络请求，不读取 IMEI 等敏感字段。
 * - 采集在主线程完成（仅读取内存对象与一次 `StatFs`），渲染前一次性赋值，避免滚动期间反复绑定。
 * - 列表项通过 [ItemAboutRowBinding] 复用同一行布局，减少布局层级。
 */
class AboutDeviceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutDeviceBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutDeviceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        fillDeviceSection()
        fillSystemSection()
        fillMemorySection()
        fillDisplaySection()
        fillAppSection()
    }

    // region 设备

    private fun fillDeviceSection() {
        bind(
            binding.rowManufacturer,
            R.string.about_label_manufacturer,
            Build.MANUFACTURER.uppercase(Locale.ROOT)
        )
        bind(binding.rowBrand, R.string.about_label_brand, Build.BRAND.uppercase(Locale.ROOT))
        bind(binding.rowModel, R.string.about_label_model, Build.MODEL.orUnknown())
        bind(binding.rowProduct, R.string.about_label_product, Build.PRODUCT.orUnknown())
        bind(binding.rowDevice, R.string.about_label_device, Build.DEVICE.orUnknown())
        bind(binding.rowHardware, R.string.about_label_hardware, Build.HARDWARE.orUnknown())
        bind(binding.rowBoard, R.string.about_label_board, Build.BOARD.orUnknown())

        val abis = Build.SUPPORTED_ABIS?.joinToString(separator = " / ").orUnknown()
        bind(binding.rowAbis, R.string.about_label_abis, abis)
    }

    // endregion

    // region 系统

    private fun fillSystemSection() {
        bind(
            binding.rowAndroidVersion,
            R.string.about_label_android_version,
            Build.VERSION.RELEASE.orUnknown()
        )
        bind(binding.rowSdkInt, R.string.about_label_sdk_int, Build.VERSION.SDK_INT.toString())
        bind(binding.rowCodename, R.string.about_label_codename, Build.VERSION.CODENAME.orUnknown())
        bind(
            binding.rowIncremental,
            R.string.about_label_incremental,
            Build.VERSION.INCREMENTAL.orUnknown()
        )
        bind(binding.rowBuildId, R.string.about_label_build_id, Build.ID.orUnknown())
        bind(
            binding.rowFingerprint,
            R.string.about_label_fingerprint,
            Build.FINGERPRINT.orUnknown()
        )

        bind(binding.rowLocale, R.string.about_label_locale, currentLocale())
        bind(binding.rowTimezone, R.string.about_label_timezone, currentTimezone())
    }

    private fun currentLocale(): String {
        val locale: Locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
        return locale.toString()
    }

    private fun currentTimezone(): String {
        val id = TimeZone.getDefault().id
        return id.ifBlank { getString(R.string.about_value_unknown) }
    }

    // endregion

    // region 内存与存储

    private fun fillMemorySection() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        bind(binding.rowTotalMem, R.string.about_label_total_mem, formatSize(info.totalMem))
        bind(binding.rowAvailMem, R.string.about_label_avail_mem, formatSize(info.availMem))
        bind(
            binding.rowLowMem,
            R.string.about_label_low_mem,
            if (info.lowMemory) "✓" else getString(R.string.about_value_unknown)
        )

        val (totalBytes, availBytes) = readInternalStorage()
        bind(binding.rowTotalStorage, R.string.about_label_total_storage, formatSize(totalBytes))
        bind(binding.rowAvailStorage, R.string.about_label_avail_storage, formatSize(availBytes))
    }

    private fun readInternalStorage(): Pair<Long, Long> {
        val path = filesDir.absolutePath
        val statFs = runCatching { StatFs(path) }.getOrNull() ?: return 0L to 0L
        val totalBytes = statFs.blockCountLong * statFs.blockSizeLong
        val availBytes = statFs.availableBlocksLong * statFs.blockSizeLong
        return totalBytes to availBytes
    }

    // endregion

    // region 屏幕

    private fun fillDisplaySection() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val widthPx = metrics.widthPixels
        val heightPx = metrics.heightPixels
        bind(
            binding.rowResolution,
            R.string.about_label_resolution,
            "${widthPx} × ${heightPx}"
        )

        val widthDp = pxToDp(widthPx, metrics.density)
        val heightDp = pxToDp(heightPx, metrics.density)
        bind(
            binding.rowScreenSize,
            R.string.about_label_screen_size,
            String.format(Locale.ROOT, "%.1f × %.1f dp", widthDp, heightDp)
        )

        bind(
            binding.rowScreenDensity,
            R.string.about_label_screen_density,
            densityBucket(metrics.densityDpi)
        )

        val refreshHz = currentDisplayRefreshRate()
        bind(
            binding.rowRefreshRate,
            R.string.about_label_refresh_rate,
            String.format(Locale.ROOT, "%.1f Hz", refreshHz)
        )
    }

    private fun pxToDp(px: Int, density: Float): Float = px / density

    private fun densityBucket(dpi: Int): String = when (dpi) {
        in 0..119 -> getString(R.string.about_density_ldpi)
        in 120..159 -> getString(R.string.about_density_mdpi)
        in 160..213 -> getString(R.string.about_density_hdpi)
        in 214..239 -> getString(R.string.about_density_tvdpi)
        in 240..319 -> getString(R.string.about_density_xhdpi)
        in 320..479 -> getString(R.string.about_density_xxhdpi)
        in 480..639 -> getString(R.string.about_density_xxxhdpi)
        else -> getString(R.string.about_density_custom, dpi)
    }

    private fun currentDisplayRefreshRate(): Float {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return wm.defaultDisplay.refreshRate
    }

    // endregion

    // region 应用

    private fun fillAppSection() {
        val pkg = packageName
        val info: PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
            }
        }.getOrNull()

        val versionName = info?.versionName.orUnknown()
        val versionCode: String = if (info == null) {
            getString(R.string.about_value_unknown)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toString()
        }

        bind(binding.rowAppVersion, R.string.about_label_app_version, versionName)
        bind(binding.rowVersionCode, R.string.about_label_version_code, versionCode)
        bind(
            binding.rowTargetSdk,
            R.string.about_label_target_sdk,
            "Android ${Build.VERSION.SDK_INT}"
        )
        bind(binding.rowMinSdk, R.string.about_label_min_sdk, "24")
        bind(binding.rowPackage, R.string.about_label_package, pkg)

        val firstInstall = info?.firstInstallTime
        val lastUpdate = info?.lastUpdateTime
        bind(
            binding.rowFirstInstall,
            R.string.about_label_first_install,
            if (firstInstall != null) formatTimestamp(firstInstall) else getString(R.string.about_value_unknown)
        )
        bind(
            binding.rowLastUpdate,
            R.string.about_label_last_update,
            if (lastUpdate != null) formatTimestamp(lastUpdate) else getString(R.string.about_value_unknown)
        )
    }

    // endregion

    // region 工具

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

    private fun formatTimestamp(timestamp: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(timestamp))
    }

    // endregion
}
