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
 * 搜索结果列表 Adapter（2 列网格）。
 *
 * - 序号从 1 开始；时长 > 0 时展示 mm:ss。
 * - 点击整张卡片触发回调。
 * - 暴露 [currentList] 给 [com.cxh09.scanpenapp.MusicSearchActivity] 用于点击时构造播放队列
 */
class MusicSearchAdapter(
    private val onClick: (PlayableSong) -> Unit
) : RecyclerView.Adapter<MusicSearchAdapter.VH>() {

    private val items = mutableListOf<PlayableSong>()

    /** 当前列表的快照。 */
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
            .inflate(R.layout.item_music_search, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position], position + 1, onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val index: TextView = view.findViewById(R.id.tvSearchIndex)
        private val name: TextView = view.findViewById(R.id.tvSearchName)
        private val artist: TextView = view.findViewById(R.id.tvSearchArtist)
        private val duration: TextView = view.findViewById(R.id.tvSearchDuration)

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
