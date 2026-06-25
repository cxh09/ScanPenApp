package com.cxh09.scanpenapp

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 微聊左侧联系人列表 Adapter（基于平台 [ArrayAdapter]）。
 *
 * - 借助 convertView 复用 + ViewHolder 模式，避免在 getView 中分配对象。
 * - 选中态用 [setSelection] 控制并通过 [bg_contact_item] 的 state_activated 着色。
 * - 头像颜色根据 [ChatContact.avatarColor] 动态染色，不引入图片资源。
 * - 选中时将文字色反白成 [R.color.chat_sidebar_active_text]，未选中时使用各层级文本色。
 */
class ChatContactAdapter(
    context: Context,
) : ArrayAdapter<ChatContact>(context, 0) {

    private var selectedId: Long = -1L

    fun submit(items: List<ChatContact>) {
        clear()
        addAll(items)
        notifyDataSetChanged()
    }

    fun setSelection(id: Long) {
        if (selectedId == id) return
        selectedId = id
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position) ?: return convertView ?: parent
        val holder = convertView?.tag as? ContactViewHolder ?: createHolder(parent)
        val isActive = item.id == selectedId
        holder.itemView.isActivated = isActive
        holder.tvName.text = item.name
        holder.tvTime.text = item.time
        holder.tvPreview.text = item.preview
        applyTextColors(holder, isActive)
        applyAvatarColor(holder.vAvatar, item.avatarColor)
        return holder.itemView
    }

    private fun applyTextColors(holder: ContactViewHolder, isActive: Boolean) {
        val ctx = holder.itemView.context
        if (isActive) {
            val activeColor = ContextCompat.getColor(ctx, R.color.chat_sidebar_active_text)
            holder.tvName.setTextColor(activeColor)
            holder.tvTime.setTextColor(activeColor)
            holder.tvPreview.setTextColor(activeColor)
        } else {
            holder.tvName.setTextColor(ContextCompat.getColor(ctx, R.color.chat_text_primary))
            holder.tvTime.setTextColor(ContextCompat.getColor(ctx, R.color.chat_text_tertiary))
            holder.tvPreview.setTextColor(ContextCompat.getColor(ctx, R.color.chat_text_secondary))
        }
    }

    private fun applyAvatarColor(view: View, color: Int) {
        val drawable = (view.background as? GradientDrawable)?.mutate() as? GradientDrawable
        if (drawable != null) {
            drawable.setColor(color)
        } else {
            view.setBackgroundColor(color)
        }
    }

    private fun createHolder(parent: ViewGroup): ContactViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_contact, parent, false)
        return ContactViewHolder(
            itemView = view,
            vAvatar = view.findViewById(R.id.vContactAvatar),
            tvName = view.findViewById(R.id.tvContactName),
            tvTime = view.findViewById(R.id.tvContactTime),
            tvPreview = view.findViewById(R.id.tvContactPreview),
        ).also { view.tag = it }
    }

    private data class ContactViewHolder(
        val itemView: View,
        val vAvatar: View,
        val tvName: TextView,
        val tvTime: TextView,
        val tvPreview: TextView,
    )
}
