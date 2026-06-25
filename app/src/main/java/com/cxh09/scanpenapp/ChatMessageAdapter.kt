package com.cxh09.scanpenapp

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * 微聊右侧聊天记录 Adapter（基于平台 [ArrayAdapter]）。
 *
 * - 通过 [getViewTypeCount] + [getItemViewType] 区分「我」与「他人」两种气泡。
 * - 复用 convertView + ViewHolder，避免在 getView 中分配对象。
 * - 复用时校验 viewType，避免「我」的气泡布局被错误地复用到「他人」消息上。
 * - 头像颜色根据 [ChatMessage.avatarColor] 动态染色，不引入图片资源。
 */
class ChatMessageAdapter(
    context: Context,
) : ArrayAdapter<ChatMessage>(context, 0) {

    private companion object {
        const val TYPE_MINE = 0
        const val TYPE_OTHER = 1
    }

    fun submit(items: List<ChatMessage>) {
        clear()
        addAll(items)
        notifyDataSetChanged()
    }

    override fun getViewTypeCount(): Int = 2

    override fun getItemViewType(position: Int): Int =
        if (getItem(position)?.isMine == true) TYPE_MINE else TYPE_OTHER

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position) ?: return convertView ?: parent
        val expectedType = getItemViewType(position)
        val holder = (convertView?.tag as? MessageViewHolder)
            ?.takeIf { it.type == expectedType }
            ?: createHolder(parent, expectedType)
        bindHolder(holder, item)
        return holder.itemView
    }

    private fun bindHolder(holder: MessageViewHolder, item: ChatMessage) {
        holder.tvBubble.text = item.content
        if (item.time.isNullOrEmpty()) {
            holder.tvTime.visibility = View.GONE
        } else {
            holder.tvTime.visibility = View.VISIBLE
            holder.tvTime.text = item.time
        }
        if (holder.tvSender != null) {
            if (item.sender.isEmpty()) {
                holder.tvSender.visibility = View.GONE
            } else {
                holder.tvSender.visibility = View.VISIBLE
                holder.tvSender.text = item.sender
            }
        }
        applyAvatarColor(holder.vAvatar, item.avatarColor)
    }

    private fun applyAvatarColor(view: View, color: Int) {
        val drawable = (view.background as? GradientDrawable)?.mutate() as? GradientDrawable
        if (drawable != null) {
            drawable.setColor(color)
        } else {
            view.setBackgroundColor(color)
        }
    }

    private fun createHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val layout = if (viewType == TYPE_MINE) {
            R.layout.item_chat_message_mine
        } else {
            R.layout.item_chat_message_other
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        val tvSender = view.findViewById<TextView?>(R.id.tvMsgSender)
        return MessageViewHolder(
            itemView = view,
            type = viewType,
            vAvatar = view.findViewById(R.id.vMsgAvatar),
            tvBubble = view.findViewById(R.id.tvMsgBubble),
            tvTime = view.findViewById(R.id.tvMsgTime),
            tvSender = tvSender,
        ).also { view.tag = it }
    }

    private data class MessageViewHolder(
        val itemView: View,
        val type: Int,
        val vAvatar: View,
        val tvBubble: TextView,
        val tvTime: TextView,
        val tvSender: TextView?,
    )
}
