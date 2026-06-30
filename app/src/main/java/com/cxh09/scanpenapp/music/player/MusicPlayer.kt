package com.cxh09.scanpenapp.music.player

import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * 全局单例播放引擎。封装 [MediaPlayer] + 状态机 + Listener 回调。
 *
 * ## 线程模型
 * - [MediaPlayer] 创建与所有 setter 必须在带 Looper 的线程上调用（API 限制）
 * - 本类所有公开方法都在 [Looper.getMainLooper] 的 [Handler] 上跑（保证主线程）
 * - 内部耗时操作（prepare）走 [MediaPlayer.prepareAsync]，回调 [OnPreparedListener] /
 *   [OnCompletionListener] / [OnErrorListener] 都自动在主线程
 *
 * ## 状态机
 * ```
 * IDLE → PREPARING → PLAYING ⇄ PAUSED
 *                            ↓
 *                          IDLE (completion / error / stop / release)
 * ```
 *
 * ## 错误处理
 * - 任意 [OnErrorListener] 触发后自动调 [stop] + 状态置 `IDLE`，避免下次 `play` 状态污染
 * - 10s 内未 [OnPreparedListener] 触发 → 触发 [Listener.onError] 并 stop（防挂起）
 */
object MusicPlayer {

    /** 错误码：10s 内未 prepare 完成。 */
    const val ERR_LOAD_TIMEOUT = -1001

    /** 状态枚举。 */
    enum class State {
        /** 空闲，未播放。 */
        IDLE,
        /** 正在准备（`prepareAsync` 中）。 */
        PREPARING,
        /** 正在播放。 */
        PLAYING,
        /** 已暂停。 */
        PAUSED,
    }

    /**
     * 播放事件监听器。
     *
     * 所有回调都在主线程。
     */
    interface Listener {
        /** 状态变更。 */
        fun onStateChanged(state: State)
        /** 播放进度。250ms 节流。 */
        fun onPosition(positionMs: Long, durationMs: Long)
        /** 播放完成。 */
        fun onCompletion()
        /** 出错。 */
        fun onError(code: Int, message: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var state: State = State.IDLE

    private var currentTitle: String = ""
    private var currentArtist: String = ""
    private var currentDurationMs: Long = 0L

    private val listeners = CopyOnWriteArraySet<Listener>()

    /** prepare 超时任务；触发后 stop + onError。 */
    private val prepareTimeoutRunnable = Runnable {
        if (state == State.PREPARING) {
            notifyError(ERR_LOAD_TIMEOUT, "prepare timeout")
            stop()
        }
    }

    /** 250ms 节流的位置循环。 */
    private val positionLoopRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            if (state == State.PLAYING || state == State.PREPARING) {
                try {
                    val pos = if (state == State.PLAYING) p.currentPosition.toLong() else 0L
                    val dur = if (p.duration > 0) p.duration.toLong() else currentDurationMs
                    if (dur > 0) currentDurationMs = dur
                    notifyPosition(pos, currentDurationMs)
                } catch (_: IllegalStateException) {
                    // player released mid-loop
                }
                mainHandler.postDelayed(this, POSITION_LOOP_INTERVAL_MS)
            }
        }
    }

    // ============== 监听器管理 ==============

    fun addListener(l: Listener) {
        listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ============== 状态读取 ==============

    fun state(): State = state
    fun isPlaying(): Boolean = state == State.PLAYING
    fun currentTitle(): String = currentTitle
    fun currentArtist(): String = currentArtist
    fun currentPositionMs(): Long {
        val p = player ?: return 0L
        return try {
            p.currentPosition.toLong()
        } catch (_: IllegalStateException) {
            0L
        }
    }
    fun durationMs(): Long {
        val p = player ?: return currentDurationMs
        return try {
            val d = p.duration
            if (d > 0) d.toLong() else currentDurationMs
        } catch (_: IllegalStateException) {
            currentDurationMs
        }
    }

    // ============== 控制 ==============

    /**
     * 播放一首歌曲。先 `reset` + `setAudioStreamType(STREAM_MUSIC)` + `setDataSource(url)`，
     * 然后 `prepareAsync`。`OnPreparedListener` 触发时 `start()` + 通知 `PLAYING`。
     *
     * 必须在主线程调用（[Looper.getMainLooper] 是默认 Looper）。
     *
     * @param url 实际可播放 URL
     * @param title 歌名（缓存给 Listener 用，不参与解码）
     * @param artist 歌手
     * @param durationMs 时长（毫秒），0 = 未知；onPrepared 之后会用 MediaPlayer 的实际 duration 覆盖
     */
    fun play(url: String, title: String, artist: String, durationMs: Long) {
        runOnMain {
            // 先取消上一首的 timeout
            mainHandler.removeCallbacks(prepareTimeoutRunnable)
            mainHandler.removeCallbacks(positionLoopRunnable)
            // 释放旧 player
            releasePlayerOnly()
            currentTitle = title
            currentArtist = artist
            currentDurationMs = durationMs
            state = State.PREPARING
            notifyStateChanged()
            val p = MediaPlayer()
            try {
                p.setAudioStreamType(AudioManager.STREAM_MUSIC)
                p.setDataSource(url)
                p.setOnPreparedListener {
                    mainHandler.removeCallbacks(prepareTimeoutRunnable)
                    try {
                        it.start()
                        state = State.PLAYING
                        currentDurationMs = it.duration.toLong().coerceAtLeast(0L)
                        notifyStateChanged()
                        schedulePositionLoop()
                    } catch (e: Exception) {
                        notifyError(-1, e.message ?: "start failed")
                        stop()
                    }
                }
                p.setOnCompletionListener {
                    mainHandler.removeCallbacks(positionLoopRunnable)
                    state = State.IDLE
                    notifyStateChanged()
                    notifyCompletion()
                }
                p.setOnErrorListener { _, what, extra ->
                    mainHandler.removeCallbacks(prepareTimeoutRunnable)
                    mainHandler.removeCallbacks(positionLoopRunnable)
                    state = State.IDLE
                    notifyError(what, "what=$what extra=$extra")
                    releasePlayerOnly()
                    notifyStateChanged()
                    true
                }
                player = p
                p.prepareAsync()
                // 10s 超时
                mainHandler.postDelayed(prepareTimeoutRunnable, PREPARE_TIMEOUT_MS)
            } catch (e: Exception) {
                notifyError(-2, e.message ?: "init failed")
                p.release()
                player = null
                state = State.IDLE
                notifyStateChanged()
            }
        }
    }

    /** 暂停。仅 PLAYING 状态生效。 */
    fun pause() {
        runOnMain {
            val p = player ?: return@runOnMain
            if (state != State.PLAYING) return@runOnMain
            try {
                if (p.isPlaying) p.pause()
            } catch (_: IllegalStateException) {
            }
            mainHandler.removeCallbacks(positionLoopRunnable)
            state = State.PAUSED
            notifyStateChanged()
        }
    }

    /** 恢复。仅 PAUSED 状态生效。 */
    fun resume() {
        runOnMain {
            val p = player ?: return@runOnMain
            if (state != State.PAUSED) return@runOnMain
            try {
                p.start()
            } catch (e: Exception) {
                notifyError(-3, e.message ?: "resume failed")
                stop()
                return@runOnMain
            }
            state = State.PLAYING
            notifyStateChanged()
            schedulePositionLoop()
        }
    }

    /**
     * 跳到指定位置。超界会被 clamp 到 `[0, durationMs]`。
     */
    fun seekTo(ms: Int) {
        runOnMain {
            val p = player ?: return@runOnMain
            val dur = durationMs()
            val target = when {
                ms < 0 -> 0
                dur > 0 && ms > dur -> dur.toInt()
                else -> ms
            }
            try {
                p.seekTo(target)
            } catch (_: IllegalStateException) {
            }
        }
    }

    /**
     * 停止。置 `state=IDLE`，不 release MediaPlayer 本身（便于快速重新 play）。
     * 调 [play] 时会先 release 旧 player。
     */
    fun stop() {
        runOnMain {
            mainHandler.removeCallbacks(prepareTimeoutRunnable)
            mainHandler.removeCallbacks(positionLoopRunnable)
            releasePlayerOnly()
            state = State.IDLE
            notifyStateChanged()
        }
    }

    /**
     * 彻底释放。`MediaPlayer.release()` + 清空 listeners。
     * App 退出时 / Activity 销毁可调用。
     */
    fun release() {
        runOnMain {
            mainHandler.removeCallbacks(prepareTimeoutRunnable)
            mainHandler.removeCallbacks(positionLoopRunnable)
            releasePlayerOnly()
            state = State.IDLE
            currentTitle = ""
            currentArtist = ""
            currentDurationMs = 0L
            listeners.clear()
            notifyStateChanged()
        }
    }

    // ============== 内部 ==============

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun releasePlayerOnly() {
        val p = player ?: return
        try {
            p.reset()
            p.release()
        } catch (_: Exception) {
        }
        player = null
    }

    private fun schedulePositionLoop() {
        mainHandler.removeCallbacks(positionLoopRunnable)
        mainHandler.postDelayed(positionLoopRunnable, POSITION_LOOP_INTERVAL_MS)
    }

    private fun notifyStateChanged() {
        val s = state
        listeners.forEach { runCatching { it.onStateChanged(s) } }
    }

    private fun notifyPosition(pos: Long, dur: Long) {
        listeners.forEach { runCatching { it.onPosition(pos, dur) } }
    }

    private fun notifyCompletion() {
        listeners.forEach { runCatching { it.onCompletion() } }
    }

    private fun notifyError(code: Int, message: String) {
        listeners.forEach { runCatching { it.onError(code, message) } }
    }

    private const val PREPARE_TIMEOUT_MS = 10_000L
    private const val POSITION_LOOP_INTERVAL_MS = 250L
}
