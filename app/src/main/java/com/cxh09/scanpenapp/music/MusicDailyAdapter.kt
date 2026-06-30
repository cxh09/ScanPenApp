package com.cxh09.scanpenapp.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cxh09.scanpenapp.R
import com.cxh09.scanpenapp.music.player.PlayableSong
import java.util.concurrent.TimeUnit

/**
 * 每日推荐单曲列表 Adapter。
 *
 * - 序号自动从 1 开始；时长 > 0 时展示 mm:ss。
 * - onBindViewHolder 中只做文本填充与点击转发，不分配对象。
 * - 暴露 [currentList] 给 [com.cxh09.scanpenapp.MusicActivity] 用于点击时构造播放队列
 * - 暴露 [onClick] 回调统一在 Activity 层走 [com.cxh09.scanpenapp.MusicActivity.playSongFlow]
 *
 * 内部统一使用 [PlayableSong]（通过 [MusicDailySong] typealias 兼容）。
 */
class MusicDailyAdapter(
    private val onClick: (PlayableSong) -> Unit
) : RecyclerView.Adapter<MusicDailyAdapter.VH>() {

    private val items = mutableListOf<PlayableSong>()

    /**
     * 当前列表的快照（不可变 list）。
     * [com.cxh09.scanpenapp.MusicActivity] 点击时使用此列表作为 `allSongs` 入参。
     */
    var currentList: List<PlayableSong> = emptyList()
        private set

    fun submit(list: List<PlayableSong>) {
        items.clear()
        items.addAll(list)
        currentList = list.toList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_music_daily, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position + 1, onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val index: TextView = view.findViewById(R.id.tvIndex)
        private val name: TextView = view.findViewById(R.id.tvSongName)
        private val artist: TextView = view.findViewById(R.id.tvSongArtist)
        private val duration: TextView = view.findViewById(R.id.tvSongDuration)

        fun bind(song: PlayableSong, position: Int, onClick: (PlayableSong) -> Unit) {
            index.text = position.toString()
            name.text = song.name
            artist.text = if (song.album.isNullOrBlank()) song.artist else "${song.artist} · ${song.album}"
            duration.text = if (song.durationMs > 0) formatDuration(song.durationMs) else ""
            itemView.setOnClickListener { onClick(song) }
        }

        private fun formatDuration(ms: Long): String {
            val totalSec = TimeUnit.MILLISECONDS.toSeconds(ms)
            val m = totalSec / 60
            val s = totalSec % 60
            return "%d:%02d".format(m, s)
        }
    }
}
