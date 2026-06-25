package com.cxh09.scanpenapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 历史对话列表 Adapter（基于平台 [ArrayAdapter]）。
 *
 * - 不引入 androidx.recyclerview，节省包体。
 * - 借助 convertView 复用 + ViewHolder 模式，避免在 getView 中分配对象。
 * - 数据由调用方通过 [submit] 注入，避免在 Adapter 中硬编码字符串。
 * - 点击事件通过 [ListView.setOnItemClickListener] 在 Activity 中统一处理，
 *   这里不设 itemView.setOnClickListener，避免与系统分发逻辑互相覆盖。
 * - 选中态 / 未选中态都使用圆角背景 Drawable，视觉风格与「新对话」按钮同款；
 *   仅靠颜色深浅区分状态，选中态更深一档。
 */
class ChatHistoryAdapter(
    context: Context,
) : ArrayAdapter<ChatHistoryItem>(context, 0) {

    private var selectedId: Long? = null
    // 缓存两个圆角背景 Drawable，避免在 getView 中重复解析资源
    private val normalBackground by lazy {
        ContextCompat.getDrawable(context, R.drawable.bg_history_normal)
    }
    private val selectedBackground by lazy {
        ContextCompat.getDrawable(context, R.drawable.bg_history_selected)
    }

    fun setSelected(id: Long?) {
        selectedId = id
        notifyDataSetChanged()
    }

    fun submit(items: List<ChatHistoryItem>) {
        clear()
        addAll(items)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position) ?: return convertView ?: parent
        val holder = convertView?.tag as? HistoryViewHolder ?: createHolder(parent)
        holder.tvTitle.text = item.title
        holder.ivStar.visibility = if (item.isStarred) View.VISIBLE else View.GONE
        // 选中态 / 未选中态使用同款形状的圆角 Drawable，仅颜色不同
        holder.itemView.background = if (item.id == selectedId) {
            selectedBackground
        } else {
            normalBackground
        }
        return holder.itemView
    }

    private fun createHolder(parent: ViewGroup): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_history, parent, false)
        return HistoryViewHolder(
            itemView = view,
            ivIcon = view.findViewById(R.id.ivHistoryIcon),
            tvTitle = view.findViewById(R.id.tvHistoryTitle),
            ivStar = view.findViewById(R.id.ivHistoryStar),
        ).also { view.tag = it }
    }

    private data class HistoryViewHolder(
        val itemView: View,
        val ivIcon: ImageView,
        val tvTitle: TextView,
        val ivStar: ImageView,
    )
}
