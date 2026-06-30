package com.cxh09.scanpenapp.music.player

/**
 * 可播放歌曲模型。统一所有列表（日推 / 搜索 / 歌单内歌曲）点击后进入播放队列的对象。
 *
 * 现有 `MusicDailySong` 通过 `typealias` 直接等于 [PlayableSong]，避免在
 * [com.cxh09.scanpenapp.music] 各 adapter / 列表间做转换。
 *
 * @param id 歌曲 ID（字符串形式，便于与 API 层透传）
 * @param name 歌曲名
 * @param artist 歌手
 * @param album 专辑名（可选）
 * @param durationMs 时长（毫秒），0 表示未知
 */
data class PlayableSong(
    val id: String,
    val name: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
)
