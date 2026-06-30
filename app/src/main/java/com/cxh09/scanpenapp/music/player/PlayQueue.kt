package com.cxh09.scanpenapp.music.player

import kotlin.random.Random

/**
 * 全局单例播放队列。管理 `songs + index + mode`，按 [Mode] 规则决定 [next] / [prev] / [jumpTo] 行为。
 *
 * - [setQueue] 替换整个队列；index 用 `coerceIn` 防越界
 * - [next] / [prev] 按 mode 决定绕回 / 保持 / 随机
 * - [Mode.REPEAT_ONE] 时 [next] / [prev] 都返回当前曲目（不切歌）；由 [MusicPlayer] 的
 *   `OnCompletionListener` 触发 UI 层调 [setQueue] + [MusicPlayer.play] 来实现「单曲循环」
 *
 * 生命周期：从 [com.cxh09.scanpenapp.MusicActivity.playSongFlow] 起播时初始化；App 退出
 * 时不主动清理（Android 进程结束自然释放）。
 */
object PlayQueue {

    /** 播放模式。 */
    enum class Mode {
        /** 列表播完停。 */
        NORMAL,
        /** 列表循环。 */
        LOOP,
        /** 单曲循环。 */
        REPEAT_ONE,
        /** 随机播放。 */
        SHUFFLE,
    }

    private var songs: List<PlayableSong> = emptyList()
    private var index: Int = -1
    private var mode: Mode = Mode.NORMAL

    /** 当前模式。 */
    fun mode(): Mode = mode

    /** 设置模式。 */
    fun setMode(m: Mode) {
        mode = m
    }

    /**
     * 循环切换模式：NORMAL → LOOP → REPEAT_ONE → SHUFFLE → NORMAL
     */
    fun cycleMode(): Mode {
        mode = when (mode) {
            Mode.NORMAL -> Mode.LOOP
            Mode.LOOP -> Mode.REPEAT_ONE
            Mode.REPEAT_ONE -> Mode.SHUFFLE
            Mode.SHUFFLE -> Mode.NORMAL
        }
        return mode
    }

    /**
     * 设置队列。
     * @param songs 新的播放列表（替换旧）
     * @param startIndex 起始位置；越界会被 `coerceIn` 修正
     */
    fun setQueue(songs: List<PlayableSong>, startIndex: Int) {
        this.songs = songs
        this.index = if (songs.isEmpty()) {
            -1
        } else {
            startIndex.coerceIn(0, songs.size - 1)
        }
    }

    /** 当前播放的歌曲。无队列 / index 越界时返回 null。 */
    fun current(): PlayableSong? = songs.getOrNull(index)

    /** 队列大小。 */
    fun size(): Int = songs.size

    /**
     * 当前队列的快照（不可变 list）。
     * 用于 mini player 下一首重新走播放流程时传递 `allSongs` 参数。
     */
    fun snapshot(): List<PlayableSong> = songs

    /**
     * 跳到指定下标。越界会被 `coerceIn` 修正。
     * @return 跳到的新 [current]；空队列返回 null
     */
    fun jumpTo(i: Int): PlayableSong? {
        if (songs.isEmpty()) {
            index = -1
            return null
        }
        index = i.coerceIn(0, songs.size - 1)
        return current()
    }

    /**
     * 下一首。
     * - [Mode.REPEAT_ONE] → 返回当前（不切）
     * - [Mode.SHUFFLE] → 随机取一个 ≠ 当前 index（队列长度 > 1 时）；队列只有 1 首时返回当前
     * - [Mode.NORMAL] 末尾 → 返回 null
     * - [Mode.LOOP] 末尾 → 回到 0
     */
    fun next(): PlayableSong? {
        if (songs.isEmpty()) return null
        if (mode == Mode.REPEAT_ONE) return current()
        if (mode == Mode.SHUFFLE) return pickRandomNotCurrent()
        val next = index + 1
        if (next >= songs.size) {
            return if (mode == Mode.LOOP) {
                index = 0
                current()
            } else {
                // NORMAL：保持末尾；调用方据此判断是否停止
                index = songs.size - 1
                current()
            }
        }
        index = next
        return current()
    }

    /**
     * 上一首。
     * - [Mode.REPEAT_ONE] → 返回当前
     * - [Mode.SHUFFLE] → 同 [next] 随机
     * - [Mode.NORMAL] 开头 → 回到 0
     * - [Mode.LOOP] 开头 → 回到末尾
     */
    fun prev(): PlayableSong? {
        if (songs.isEmpty()) return null
        if (mode == Mode.REPEAT_ONE) return current()
        if (mode == Mode.SHUFFLE) return pickRandomNotCurrent()
        val prev = index - 1
        if (prev < 0) {
            return if (mode == Mode.LOOP) {
                index = songs.size - 1
                current()
            } else {
                index = 0
                current()
            }
        }
        index = prev
        return current()
    }

    /**
     * 随机取一个 ≠ 当前 index 的歌曲。队列只有 1 首时返回当前。
     */
    private fun pickRandomNotCurrent(): PlayableSong? {
        if (songs.isEmpty()) return null
        if (songs.size == 1) {
            index = 0
            return current()
        }
        var pick: Int
        do {
            pick = Random.nextInt(songs.size)
        } while (pick == index)
        index = pick
        return current()
    }
}
