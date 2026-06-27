package com.cxh09.scanpenapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 录音列表 Adapter。
 *
 * - 持 [items] 数据列表 + [selectedUri] 当前选中条目。
 * - ViewHolder 复用 [R.layout.item_recording]，在 [bind] 中填充 tvName / tvMeta 与选中态。
 * - onBindViewHolder 中不分配新对象、不做图片解码。
 */
class RecordingAdapter(
    private val onClick: (Recording) -> Unit
) : RecyclerView.Adapter<RecordingAdapter.VH>() {

    private val items = mutableListOf<Recording>()
    private var selectedUri: android.net.Uri? = null

    fun submit(list: List<Recording>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun setSelectedUri(uri: android.net.Uri?) {
        val old = selectedUri
        selectedUri = uri
        items.forEachIndexed { i, r ->
            if (r.uri == old || r.uri == uri) notifyItemChanged(i)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.bind(item, item.uri == selectedUri, onClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val name: TextView = view.findViewById(R.id.tvName)
        private val meta: TextView = view.findViewById(R.id.tvMeta)

        fun bind(rec: Recording, selected: Boolean, onClick: (Recording) -> Unit) {
            name.text = rec.displayName
            meta.text = "${RecordingStore.formatDuration(rec.durationMs)} · ${RecordingStore.formatTime(rec.dateAddedSec)}"
            itemView.isActivated = selected
            itemView.setOnClickListener { onClick(rec) }
        }
    }
}
