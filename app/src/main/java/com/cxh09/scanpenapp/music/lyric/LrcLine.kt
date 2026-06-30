package com.cxh09.scanpenapp.music.lyric

/**
 * 一行歌词。
 *
 * @param timeMs 时间戳（毫秒），对应 MediaPlayer 的 [android.media.MediaPlayer.getCurrentPosition]
 * @param text 歌词文本（可能为空字符串；空文本应被 LRC 解析器直接跳过）
 */
data class LrcLine(
    val timeMs: Long,
    val text: String,
)
