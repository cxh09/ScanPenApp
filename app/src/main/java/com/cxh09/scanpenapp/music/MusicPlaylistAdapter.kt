package com.cxh09.scanpenapp.music

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.cxh09.scanpenapp.R

/**
 * 歌单列表 Adapter。
 *
 * - 数据源 [items] + 当前选中歌单 ID [selectedId]。
 * - onBindViewHolder 中只做文本填充与点击转发，不分配新对象。
 */
class MusicPlaylistAdapter(
    private val onClick: (MusicPlaylist) -> Unit
) : RecyclerView.Adapter<MusicPlaylistAdapter.VH>() {

    private val items = mutableListOf<MusicPlaylist>()
    private var selectedId: String? = null

    fun submit(list: List<MusicPlaylist>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSelectedId(id: String?) {
        val old = selectedId
        selectedId = id
        items.forEachIndexed { i, p ->
            if (p.id == old || p.id == id) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_music_playlist, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.bind(item, item.id == selectedId, onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tvPlaylistName)
        private val meta: TextView = view.findViewById(R.id.tvPlaylistMeta)

        fun bind(p: MusicPlaylist, selected: Boolean, onClick: (MusicPlaylist) -> Unit) {
            name.text = p.name
            // 元信息：首数 · 创建者。空创建者只展示首数。
            val count = itemView.context.getString(R.string.music_playlist_subtitle, p.trackCount)
            meta.text = if (p.creator.isBlank()) count else "$count · ${p.creator}"
            itemView.isActivated = selected
            itemView.setOnClickListener { onClick(p) }
        }
    }
}
