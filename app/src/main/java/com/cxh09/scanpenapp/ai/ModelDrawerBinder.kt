package com.cxh09.scanpenapp.ai

import android.app.Activity
import android.view.View
import android.widget.ListView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.cxh09.scanpenapp.R

/**
 * 模型选择抽屉绑定器：把「右侧抽屉式模型选择」的 UI 与交互从 [com.cxh09.scanpenapp.AskAiActivity] 抽出，
 * 让 [com.cxh09.scanpenapp.WritingActivity] / [com.cxh09.scanpenapp.LivelyActivity] /
 * [com.cxh09.scanpenapp.TranslateActivity] 复用同一套体验（标题 + 关闭 + 列表 + 跳 AI 服务）。
 *
 * 复用前置条件：调用方 Activity 的 layout 根必须是 [DrawerLayout]（id = `drawerRoot`），且包含一组
 * 与 [com.cxh09.scanpenapp.AskAiActivity] 同 id 的抽屉子 View（`drawerModels` / `lvModelsInDrawer` /
 * `btnCloseDrawer` / `settingsRow` / `tvDrawerEmpty`）。[ModelDrawerBinder] 在 [bind] 时按 id 查找，
 * 任何 id 缺失就抛 [IllegalStateException]，避免上线后才发现 UI 不一致。
 *
 * @param onModelChanged 切换模型后的回调：调用方负责 rebuild client + 刷新按钮文案。抽屉由本类
 *   自动关闭，回调不需要再 closeDrawer。
 */
class ModelDrawerBinder(
    private val activity: Activity,
    private val modelStore: ModelConfigStore,
    private val onModelChanged: () -> Unit,
) {

    private var drawerRoot: DrawerLayout? = null
    private var listView: ListView? = null
    private var emptyView: View? = null
    private var adapter: ModelConfigAdapter? = null
    private var bound: Boolean = false

    /**
     * 绑定 Activity 根 layout 里的抽屉 View。必须在 [Activity.setContentView] 之后调用。
     * - 重复 bind 幂等：仅在首次执行
     * - 缺失关键 id 立刻抛 [IllegalStateException]，避免抽屉显示异常而调用方不知情
     */
    fun bind() {
        if (bound) return
        val root = activity.findViewById<DrawerLayout>(R.id.drawerRoot)
            ?: throw IllegalStateException("ModelDrawerBinder: 根 layout 缺少 id=drawerRoot 的 DrawerLayout")
        val lv = activity.findViewById<ListView>(R.id.lvModelsInDrawer)
            ?: throw IllegalStateException("ModelDrawerBinder: layout 缺少 id=lvModelsInDrawer 的 ListView")
        val empty = activity.findViewById<View>(R.id.tvDrawerEmpty)
        val close = activity.findViewById<View>(R.id.btnCloseDrawer)
            ?: throw IllegalStateException("ModelDrawerBinder: layout 缺少 id=btnCloseDrawer")
        val settings = activity.findViewById<View>(R.id.settingsRow)

        val adp = ModelConfigAdapter(activity, ModelConfigAdapter.VARIANT_DRAWER)
        lv.adapter = adp
        lv.setOnItemClickListener { _, _, position, _ ->
            adp.getItem(position)?.let(::onDrawerItemClicked)
        }
        close.setOnClickListener { root.closeDrawer(GravityCompat.END) }
        settings?.setOnClickListener {
            root.closeDrawer(GravityCompat.END)
            activity.startActivity(android.content.Intent(activity, AiSettingsActivity::class.java))
        }
        root.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)

        drawerRoot = root
        listView = lv
        emptyView = empty
        adapter = adp
        bound = true
    }

    /**
     * 重新加载模型列表（用于 onResume 同步用户在 AI 服务页的改动）。
     * 同时刷新列表选中态与空态。
     */
    fun reload() {
        if (!bound) return
        val list = modelStore.loadAll()
        val adp = adapter ?: return
        adp.submit(list)
        adp.setSelected(modelStore.currentId())
        val empty = list.isEmpty()
        emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
        listView?.visibility = if (empty) View.GONE else View.VISIBLE
    }

    /**
     * 打开抽屉（点击输入框上的「模型」按钮时调用）。
     * 打开前先 reload 一次，保证抽屉里看到的是最新模型列表。
     */
    fun open() {
        if (!bound) return
        reload()
        drawerRoot?.openDrawer(GravityCompat.END)
    }

    private fun onDrawerItemClicked(model: ModelConfig) {
        // 与原 AskAiActivity 行为一致：点同一项 no-op；切换时 setCurrent + 关闭 + 回调
        if (model.id == modelStore.currentId()) {
            drawerRoot?.closeDrawer(GravityCompat.END)
            return
        }
        modelStore.setCurrent(model.id)
        drawerRoot?.closeDrawer(GravityCompat.END)
        onModelChanged()
    }
}
