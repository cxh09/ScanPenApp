package com.cxh09.scanpenapp.music

/**
 * 歌单元数据。
 *
 * @param id 歌单 ID（云端或本地生成）。
 * @param name 歌单名，例如「我喜欢的音乐」。
 * @param trackCount 歌曲数量。
 * @param creator 创建者 / 来源描述。
 */
data class MusicPlaylist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val creator: String,
    val coverImgUrl: String = "",
    val playCount: Long = 0L
)
