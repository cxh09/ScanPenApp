package com.cxh09.scanpenapp.ai

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cxh09.scanpenapp.R

/**
 * 多套模型配置列表的 Adapter。
 *
 * - 通过构造参数 [variant] 区分两种形态：
 *   - [VARIANT_SETTINGS]：左栏（设置页），不显示打勾；用 `item_model_config.xml`。
 *   - [VARIANT_DRAWER]：右抽屉（问 AI 页），右侧打勾；用 `item_model_drawer.xml`。
 * - 选中态 / 未选中态都用同款圆角 Drawable，仅颜色深浅不同；点击事件由调用方通过
 *   `ListView.setOnItemClickListener` 统一处理，这里不绑 itemView.onClick，避免与系统分发冲突。
 * - ViewHolder 模式 + convertView 复用，不在 getView 中分配对象或解码图片。
 */
class ModelConfigAdapter(
    context: Context,
    private val variant: Int = VARIANT_SETTINGS,
) : ArrayAdapter<ModelConfig>(context, 0) {

    private var selectedId: String? = null
    private val normalBackground by lazy {
        ContextCompat.getDrawable(context, R.drawable.bg_history_normal)
    }
    private val selectedBackground by lazy {
        ContextCompat.getDrawable(context, R.drawable.bg_history_selected)
    }

    fun setSelected(id: String?) {
        selectedId = id
        notifyDataSetChanged()
    }

    fun submit(items: List<ModelConfig>) {
        clear()
        addAll(items)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = getItem(position) ?: return convertView ?: parent
        val holder = convertView?.tag as? ModelViewHolder ?: createHolder(parent)
        bindHolder(holder, item)
        return holder.itemView
    }

    private fun bindHolder(holder: ModelViewHolder, item: ModelConfig) {
        holder.tvName.text = item.name.ifBlank {
            holder.itemView.context.getString(R.string.ai_settings_model_untitled)
        }
        holder.tvSubtitle.text = item.model
        holder.tvUnconfigured.visibility = if (item.isComplete) View.GONE else View.VISIBLE
        holder.itemView.background = if (item.id == selectedId) {
            selectedBackground
        } else {
            normalBackground
        }
        when (variant) {
            VARIANT_DRAWER -> {
                holder.ivChecked.visibility = if (item.id == selectedId) View.VISIBLE else View.GONE
            }
            else -> {
                holder.ivChecked.visibility = View.GONE
            }
        }
    }

    private fun createHolder(parent: ViewGroup): ModelViewHolder {
        val layoutRes = when (variant) {
            VARIANT_DRAWER -> R.layout.item_model_drawer
            else -> R.layout.item_model_config
        }
        val view = LayoutInflater.from(parent.context).inflate(layoutRes, parent, false)
        return when (variant) {
            VARIANT_DRAWER -> ModelViewHolder(
                itemView = view,
                tvName = view.findViewById(R.id.tvDrawerModelName),
                tvSubtitle = view.findViewById(R.id.tvDrawerModelSubtitle),
                tvUnconfigured = TextView(parent.context), // 抽屉版不显示未配置标记
                ivChecked = view.findViewById(R.id.ivDrawerChecked),
            ).also { view.tag = it }
            else -> ModelViewHolder(
                itemView = view,
                tvName = view.findViewById(R.id.tvModelName),
                tvSubtitle = view.findViewById(R.id.tvModelSubtitle),
                tvUnconfigured = view.findViewById(R.id.tvModelUnconfigured),
                ivChecked = ImageView(parent.context), // 设置版不显示打勾
            ).also { view.tag = it }
        }
    }

    private data class ModelViewHolder(
        val itemView: View,
        val tvName: TextView,
        val tvSubtitle: TextView,
        val tvUnconfigured: TextView,
        val ivChecked: ImageView,
    )

    companion object {
        const val VARIANT_SETTINGS = 0
        const val VARIANT_DRAWER = 1
    }
}
