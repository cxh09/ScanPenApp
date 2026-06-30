package com.cxh09.scanpenapp.music

import com.cxh09.scanpenapp.music.player.PlayableSong

/**
 * 每日推荐单曲。
 *
 * 字段与 [com.cxh09.scanpenapp.music.player.PlayableSong] 完全一致；通过 [typealias]
 * 让 [MusicDailySong] 等同于 [PlayableSong]，所有 adapter / 列表可直接用于播放队列。
 *
 * @param id 歌曲 ID。
 * @param name 歌曲名。
 * @param artist 歌手。
 * @param album 专辑名（可选）。
 * @param durationMs 时长（毫秒），用于「时长」副标题。
 */
typealias MusicDailySong = PlayableSong
