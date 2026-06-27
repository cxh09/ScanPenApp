package com.cxh09.scanpenapp

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.cxh09.scanpenapp.databinding.ActivityRecorderBinding
import java.io.File

/**
 * 录音机页面。
 *
 * - 左右分栏：左侧 220dp 侧栏（顶部「＋新录音」+ 录音列表 RecyclerView）；右侧主区在
 *   「录制态 recordPanel」与「播放态 playPanel」之间互斥切换。
 * - 录音：MediaRecorder (MIC / MPEG_4 / AAC / 44.1kHz / 128kbps) 写入
 *   app-internal filesDir/recordings/rec_<ts>.m4a；录制结束后通过 [RecordingStore]
 *   注册到 MediaStore.Audio.Media，让左栏列表能查询到。
 * - 播放：MediaPlayer 读取同一文件路径，SeekBar + 200ms 进度 tick 刷新。
 * - 状态机：Idle / Recording / Playing / Paused；MediaRecorder.stop() 在录音 < 1s
 *   时会抛 RuntimeException，已 try-catch 并 Toast 提示。
 * - 权限：首次点「开始」时申请 RECORD_AUDIO；拒绝时显示 tvMicNeed + btnMicGoto 引导跳系统设置。
 */
class RecorderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRecorderBinding
    private lateinit var adapter: RecordingAdapter

    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentRecordingFile: File? = null
    private var recordStartElapsed: Long = 0L
    private var currentPlaying: Recording? = null
    private val handler = Handler(Looper.getMainLooper())

    private enum class Mode { Idle, Recording, Playing, Paused }
    private var mode: Mode = Mode.Idle

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startRecording()
        } else {
            showMicPermissionNeed()
        }
    }

    /**
     * 读取音频权限 launcher：API ≤ 32 申请 READ_EXTERNAL_STORAGE，API ≥ 33 申请 READ_MEDIA_AUDIO。
     * 任意分支申请后，无论结果如何都调一次 refreshList —— 拒绝时 queryAll 内部已 try-catch 兜底。
     */
    private val requestAudioReadPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecorderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = RecordingAdapter(::onRecordingClicked)
        binding.rvRecordings.layoutManager = LinearLayoutManager(this)
        binding.rvRecordings.adapter = adapter

        binding.btnNewRecording.setOnClickListener { switchToRecordPanel() }
        binding.btnRecord.setOnClickListener { onRecordBtnClicked() }
        binding.btnPlayPause.setOnClickListener { onPlayPauseClicked() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
        binding.btnMicGoto.setOnClickListener { openAppSettings() }

        binding.seekBar.setOnSeekBarChangeListener(object :
            android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                binding.tvPlayTime.text = formatPlayTime(p, binding.seekBar.max)
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                mediaPlayer?.seekTo(s?.progress ?: 0)
            }
        })

        switchToRecordPanel()
    }

    override fun onResume() {
        super.onResume()
        ensureAudioReadPermissionThenRefresh()
        // 录音中权限被撤销 → 清理
        if (mode == Mode.Recording && !hasMicPermission()) {
            stopRecordingCleanup()
        }
        updateMicPermissionUi()
    }

    override fun onPause() {
        super.onPause()
        // 离开页面时停止播放（不停止录音，避免后台录音耗电）
        if (mode == Mode.Playing || mode == Mode.Paused) {
            stopPlayer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseRecorder()
        stopPlayer()
        handler.removeCallbacksAndMessages(null)
    }

    // ============ 列表 ============

    private fun refreshList() {
        val list = RecordingStore.queryAll(this)
        adapter.submit(list)
        binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun onRecordingClicked(rec: Recording) {
        if (mode == Mode.Recording) return
        if (rec.dataPath.isNullOrBlank()) {
            // 文件路径缺失（MediaStore 行存在但本机文件已删），静默忽略
            Log.w(TAG, "Recording dataPath is null, skip play: ${rec.displayName}")
            return
        }
        switchToPlayPanel(rec)
    }

    // ============ 录制态 ============

    private fun onRecordBtnClicked() {
        when (mode) {
            Mode.Idle -> {
                if (!hasMicPermission()) {
                    requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    startRecording()
                }
            }
            Mode.Recording -> stopRecording()
            Mode.Playing, Mode.Paused -> { /* 必须先点 ＋新录音 才能切回 Idle */ }
        }
    }

    private fun startRecording() {
        try {
            val file = RecordingStore.createOutputFile(this)
            currentRecordingFile = file
            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(128000)
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()
            mediaRecorder = rec
            recordStartElapsed = SystemClock.elapsedRealtime()
            binding.chronometer.base = recordStartElapsed
            binding.chronometer.start()
            mode = Mode.Recording
            binding.btnRecord.isSelected = true
            binding.tvRecordBtnLabel.text = "■"
            binding.tvRecordHint.text = getString(R.string.recorder_stop)
            binding.tvRecordStatus.text = file.name
            binding.tvMicNeed.visibility = View.GONE
            binding.btnMicGoto.visibility = View.GONE
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            releaseRecorder()
            currentRecordingFile?.delete()
            currentRecordingFile = null
            Toast.makeText(this, R.string.camera_save_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        val rec = mediaRecorder ?: return
        val file = currentRecordingFile ?: return
        val durationMs = SystemClock.elapsedRealtime() - recordStartElapsed
        try {
            rec.stop()
        } catch (e: RuntimeException) {
            // 录音时长过短会抛 RuntimeException，删除临时文件
            Log.w(TAG, "stop() too short", e)
            file.delete()
            releaseRecorder()
            binding.chronometer.stop()
            resetRecordUi()
            mode = Mode.Idle
            Toast.makeText(this, R.string.camera_record_too_short, Toast.LENGTH_SHORT).show()
            return
        }
        rec.release()
        mediaRecorder = null
        binding.chronometer.stop()
        mode = Mode.Idle
        resetRecordUi()
        // 注册到 MediaStore
        if (durationMs > 0) {
            RecordingStore.commitToMediaStore(this, file, durationMs)
        } else {
            file.delete()
        }
        refreshList()
    }

    private fun stopRecordingCleanup() {
        // 录音中权限被撤销
        val rec = mediaRecorder
        val file = currentRecordingFile
        try { rec?.stop() } catch (_: Exception) {}
        rec?.release()
        mediaRecorder = null
        file?.delete()
        currentRecordingFile = null
        binding.chronometer.stop()
        resetRecordUi()
        mode = Mode.Idle
    }

    private fun releaseRecorder() {
        try { mediaRecorder?.release() } catch (_: Exception) {}
        mediaRecorder = null
    }

    private fun resetRecordUi() {
        binding.btnRecord.isSelected = false
        binding.tvRecordBtnLabel.text = "●"
        binding.tvRecordHint.text = getString(R.string.recorder_idle)
        binding.tvRecordStatus.text = ""
    }

    // ============ 播放态 ============

    private fun switchToPlayPanel(rec: Recording) {
        mode = Mode.Playing
        currentPlaying = rec
        binding.recordPanel.visibility = View.GONE
        binding.playPanel.visibility = View.VISIBLE
        binding.tvPlayName.text = rec.displayName
        val total = rec.durationMs.coerceAtLeast(0)
        binding.seekBar.max = total.toInt().coerceAtLeast(1000)
        binding.tvPlayTime.text = formatPlayTime(0, total.toInt())
        startPlayer(rec)
        // 高亮当前选中
        adapter.setSelectedUri(rec.uri)
    }

    private fun startPlayer(rec: Recording) {
        stopPlayer()
        val path = rec.dataPath ?: return
        try {
            val mp = MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
            mediaPlayer = mp
            updatePlayPauseBtn(playing = true)
            startProgressTick()
            mp.setOnCompletionListener {
                handler.removeCallbacks(progressTickRunnable)
                try { mediaPlayer?.release() } catch (_: Exception) {}
                mediaPlayer = null
                // 播放完毕：保持「继续」态（从头再播）
                mode = Mode.Paused
                updatePlayPauseBtn(playing = false)
                binding.seekBar.progress = 0
                binding.tvPlayTime.text = formatPlayTime(0, rec.durationMs.toInt())
            }
        } catch (e: Exception) {
            Log.e(TAG, "player start failed", e)
            Toast.makeText(this, R.string.camera_save_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 暂停/继续 toggle：
     * - 当前 Playing → 调 pause，文字切「继续」
     * - 当前 Paused / 播放完毕 → 调 start（或重建 MediaPlayer），文字切「暂停」
     * - 无 currentPlaying 时不响应
     */
    private fun onPlayPauseClicked() {
        val rec = currentPlaying ?: return
        when (mode) {
            Mode.Playing -> {
                try { mediaPlayer?.pause() } catch (_: Exception) {}
                handler.removeCallbacks(progressTickRunnable)
                mode = Mode.Paused
                updatePlayPauseBtn(playing = false)
            }
            Mode.Paused -> {
                if (mediaPlayer == null) {
                    // 播放完毕/被 stop 后第一次点 → 重新构造 MediaPlayer 从头播放
                    startPlayer(rec)
                } else {
                    try {
                        mediaPlayer?.start()
                        startProgressTick()
                        mode = Mode.Playing
                        updatePlayPauseBtn(playing = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "play failed", e)
                    }
                }
            }
            else -> { /* Idle / Recording 不应能进 playPanel */ }
        }
    }

    private fun updatePlayPauseBtn(playing: Boolean) {
        binding.btnPlayPause.setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play
        )
        binding.btnPlayPause.contentDescription = getString(
            if (playing) R.string.recorder_pause else R.string.recorder_resume
        )
    }

    private fun stopPlayer() {
        handler.removeCallbacks(progressTickRunnable)
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    private val progressTickRunnable = object : Runnable {
        override fun run() {
            val mp = mediaPlayer ?: return
            try {
                val pos = mp.currentPosition
                val dur = mp.duration.coerceAtLeast(0)
                if (dur > 0) {
                    binding.seekBar.max = dur
                    binding.seekBar.progress = pos
                    binding.tvPlayTime.text = formatPlayTime(pos, dur)
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, 200L)
        }
    }

    private fun startProgressTick() {
        handler.removeCallbacks(progressTickRunnable)
        handler.post(progressTickRunnable)
    }

    private fun formatPlayTime(pos: Int, max: Int): String {
        return "${RecordingStore.formatDuration(pos.toLong())} / ${RecordingStore.formatDuration(max.toLong())}"
    }

    private fun confirmDelete() {
        val rec = currentPlaying ?: return
        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_AlertDialog_Dark)
            .setTitle(R.string.recorder_delete)
            .setMessage(R.string.recorder_delete_confirm)
            .setPositiveButton(R.string.recorder_delete_confirm_btn) { _, _ ->
                stopPlayer()
                RecordingStore.delete(this, rec)
                refreshList()
                switchToRecordPanel()
            }
            .setNegativeButton(R.string.recorder_delete_cancel, null)
            .show()
    }

    // ============ 切换态 ============

    private fun switchToRecordPanel() {
        stopPlayer()
        mode = Mode.Idle
        currentPlaying = null
        adapter.setSelectedUri(null)
        binding.playPanel.visibility = View.GONE
        binding.recordPanel.visibility = View.VISIBLE
        binding.chronometer.stop()
        binding.chronometer.base = SystemClock.elapsedRealtime()
        resetRecordUi()
        updateMicPermissionUi()
    }

    // ============ 权限 ============

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 读取 MediaStore.Audio.Media 所需的权限：
     * - API ≤ 32 (Android 12L 及以下) 用 READ_EXTERNAL_STORAGE
     * - API ≥ 33 (Android 13+) 用 READ_MEDIA_AUDIO
     * - 任意 API 下 manifest 永久未声明时 ContextCompat.checkSelfPermission 返回 DENIED
     */
    private fun hasAudioReadPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 进页面时若未取得音频读权限，触发系统申请；已授权则直接刷新列表。
     * 申请回调在 [requestAudioReadPermission] 中再次调用 refreshList。
     */
    private fun ensureAudioReadPermissionThenRefresh() {
        if (hasAudioReadPermission()) {
            refreshList()
        } else {
            val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            requestAudioReadPermission.launch(perm)
        }
    }

    private fun showMicPermissionNeed() {
        binding.tvMicNeed.visibility = View.VISIBLE
        binding.btnMicGoto.visibility = View.VISIBLE
    }

    private fun updateMicPermissionUi() {
        if (mode == Mode.Recording) return
        if (hasMicPermission()) {
            binding.tvMicNeed.visibility = View.GONE
            binding.btnMicGoto.visibility = View.GONE
        } else {
            showMicPermissionNeed()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "open settings failed", e)
        }
    }

    companion object {
        private const val TAG = "RecorderActivity"
    }
}
