package com.cxh09.scanpenapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 录音文件元数据（从 MediaStore 查出来的行）。 */
data class Recording(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    val dataPath: String?
)

/**
 * 录音文件的存储 + MediaStore 注册封装。
 *
 * - 物理文件：app-internal filesDir/recordings/rec_<ts>.m4a（API ≥ 29）/ 公共 Music/ScanPenApp（API ≤ 28）
 * - 系统注册：MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
 * - 不申请 WRITE_EXTERNAL_STORAGE（API ≤ 28 走 manifest maxSdkVersion=28，录音文件用 filesDir 无需权限）
 *
 * 注意：minSdk=24，需要分支处理 RELATIVE_PATH。
 */
object RecordingStore {

    private const val RELATIVE_PATH = "Music/ScanPenApp"
    private const val MIME_TYPE = "audio/mp4"
    private const val TAG = "RecordingStore"

    /** 构造一个待录音的输出文件（filesDir 内部，无需运行时权限）。 */
    fun createOutputFile(context: Context): File {
        val dir = File(context.filesDir, "recordings")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "rec_${System.currentTimeMillis()}.m4a")
    }

    /** 查询所有属于本应用的录音，按 date_added desc。 */
    fun queryAll(context: Context): List<Recording> {
        val list = mutableListOf<Recording>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATA
        )
        val (selection, selArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?" to arrayOf("$RELATIVE_PATH%")
        } else {
            // API ≤ 28：物理文件复制到公共 Music/ScanPenApp 后通过 DATA 匹配
            "${MediaStore.Audio.Media.DATA} LIKE ?" to arrayOf("%/Music/ScanPenApp/rec_%")
        }
        val order = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selArgs,
                order
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dateIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val dataIdx = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString())
                    val dataPath = if (c.isNull(dataIdx)) null else c.getString(dataIdx)
                    list.add(
                        Recording(
                            uri = uri,
                            displayName = c.getString(nameIdx) ?: "rec_${id}.m4a",
                            durationMs = c.getLong(durIdx),
                            dateAddedSec = c.getLong(dateIdx),
                            dataPath = dataPath
                        )
                    )
                }
            }
            list
        } catch (e: SecurityException) {
            // 未授予 READ_EXTERNAL_STORAGE / READ_MEDIA_AUDIO 时静默返回空列表
            // （UI 侧会在 onResume 前通过权限检查 + 提示面板引导用户授权）
            android.util.Log.w(TAG, "queryAll: missing audio read permission", e)
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "queryAll failed", e)
            emptyList()
        }
    }

    /** 把已录音的文件注册到 MediaStore.Audio.Media；返回 Uri。 */
    fun commitToMediaStore(context: Context, file: File, durationMs: Long): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Audio.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Audio.Media.DURATION, durationMs)
            put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            } else {
                // API ≤ 28：把 filesDir 下的文件复制到公共 Music/ScanPenApp（manifest 已 maxSdkVersion=28）
                val publicDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                    "ScanPenApp"
                )
                if (!publicDir.exists()) publicDir.mkdirs()
                val target = File(publicDir, file.name)
                try {
                    file.inputStream().use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    file.delete()
                    put(MediaStore.Audio.Media.DATA, target.absolutePath)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Copy to public failed", e)
                    // 回退：仍用原 filesDir 路径注册
                    put(MediaStore.Audio.Media.DATA, file.absolutePath)
                }
            }
        }
        return try {
            context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Insert failed", e)
            null
        }
    }

    /** 删除 MediaStore 行 + 物理文件。 */
    fun delete(context: Context, recording: Recording): Boolean {
        var ok = true
        try {
            val n = context.contentResolver.delete(recording.uri, null, null)
            if (n <= 0) ok = false
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Delete uri failed", e)
            ok = false
        }
        recording.dataPath?.let { p ->
            try {
                val f = File(p)
                if (f.exists()) f.delete()
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Delete file failed", e)
            }
        }
        return ok
    }

    /** 格式化 mm:ss，毫秒截断到秒。 */
    fun formatDuration(ms: Long): String {
        if (ms < 0) return "--:--"
        val totalSec = ms / 1000
        val mm = totalSec / 60
        val ss = totalSec % 60
        return String.format(Locale.US, "%d:%02d", mm, ss)
    }

    /** 格式化 HH:mm（24h 制），用于列表项的录制时间显示。 */
    fun formatTime(dateAddedSec: Long): String {
        val df = SimpleDateFormat("HH:mm", Locale.getDefault())
        return df.format(Date(dateAddedSec * 1000))
    }
}
