package com.cxh09.scanpenapp

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.cxh09.scanpenapp.databinding.ItemSettingBinding

/**
 * 设置页列表 Adapter。
 *
 * - 构造时接收一次性数据 [items] 与点击回调 [onItemClick]，运行期不再分配新对象。
 * - ViewHolder 通过 ViewBinding 持有视图引用，避免在 onBindViewHolder 中做对象分配或图片解码。
 */
class SettingsAdapter(
    private val items: List<SettingItem>,
    private val onItemClick: (SettingItem) -> Unit,
) : RecyclerView.Adapter<SettingsAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemSettingBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.bind(item, onItemClick)
    }

    override fun getItemCount(): Int = items.size

    class VH(private val binding: ItemSettingBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: SettingItem, onItemClick: (SettingItem) -> Unit) {
            binding.ivSettingIcon.setImageResource(item.iconRes)
            binding.tvSettingTitle.text = item.title
            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
