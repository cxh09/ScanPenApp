package com.cxh09.scanpenapp.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.aallam.openai.client.OpenAI
import com.cxh09.scanpenapp.R
import com.cxh09.scanpenapp.databinding.ActivityAiSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/**
 * AI 服务设置页（左右分栏）。
 *
 * - 左侧 [ModelConfigStore] 中的所有 [ModelConfig] 列表，点击切换选中。
 * - 右侧编辑当前选中配置：`name / apiKey / baseUrl / model`，底部「保存」按当前字段回写，
 *   底部「删除」弹确认后移除当前配置（保证列表至少留 1 条占位）。
 * - 测试连接、扫一扫、分享、预设 BaseURL 与原单条表单一致，仅作用对象改为「当前选中配置」。
 * - [onResume] 不主动 finish：用户可在页内继续切换 / 编辑；只有返回按钮才退出。
 */
class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private lateinit var store: ModelConfigStore
    private lateinit var adapter: ModelConfigAdapter
    private var testingJob: Job? = null
    /** 右栏当前显示的 id（即「正在编辑」的配置）。与 [ModelConfigStore.currentId] 解耦。 */
    private var editingId: String? = null
    /** 是否处于「草稿」态：点了 + 但还没保存。草稿不写入 store，左侧列表也不显示。 */
    private var isDraft: Boolean = false
    /** 草稿态下预生成的 id；保存时把它写进 store 即可，保证新配置 id 唯一。 */
    private var draftId: String? = null

    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val raw = result.data?.getStringExtra(QrScanActivity.EXTRA_SCAN_RESULT)
                if (!raw.isNullOrBlank()) {
                    applyScannedJson(raw)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        store = ModelConfigStore(this)
        adapter = ModelConfigAdapter(this, ModelConfigAdapter.VARIANT_SETTINGS)
        binding.lvModels.adapter = adapter
        binding.lvModels.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                adapter.getItem(position)?.let(::onModelClicked)
            }

        setupListeners()
        reloadAll()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        testingJob?.cancel()
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnAddModel.setOnClickListener { addNewModel() }

        binding.btnSave.setOnClickListener { onSaveClicked() }

        binding.btnDeleteModel.setOnClickListener { onDeleteClicked() }

        binding.btnScan.setOnClickListener {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        binding.btnShare.setOnClickListener {
            startActivity(Intent(this, QrShareActivity::class.java))
        }

        // Base URL 预设：仅作用到当前编辑配置（写入输入框，不立即落库）
        binding.btnPresetDeepseek.setOnClickListener {
            binding.etBaseUrl.setText("https://api.deepseek.com/v1")
        }
        binding.btnPresetOpencode.setOnClickListener {
            binding.etBaseUrl.setText("https://opencode.ai/zen/go/v1")
        }
        // 智谱 GLM 免费一键预设：填齐当前编辑配置的 Key/BaseURL/Model
        binding.btnPresetGlm.setOnClickListener {
            binding.etApiKey.setText("f8be67658e91407eaf703a92e0e1e325.ULwVYUtaToi2AJj0")
            binding.etBaseUrl.setText("https://open.bigmodel.cn/api/paas/v4")
            binding.etModel.setText("GLM-4.7-Flash")
            binding.tvTestStatus.text = ""
        }

        // 测试连接
        binding.btnTest.setOnClickListener { runTest() }

        // 输入框 IME 跳转
        binding.etName.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etApiKey.requestFocus()
                true
            } else false
        }
        binding.etApiKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etBaseUrl.requestFocus()
                true
            } else false
        }
        binding.etBaseUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                binding.etModel.requestFocus()
                true
            } else false
        }
    }

    /**
     * 全量重新加载：
     * 1) 拉取 [ModelConfigStore] 列表
     * 2) 选回 [editingId]（找不到则用当前 current）
     * 3) 刷新右栏输入框
     */
    private fun reloadAll() {
        val list = store.loadAll()
        adapter.submit(list)
        val targetId = editingId?.takeIf { id -> list.any { it.id == id } }
            ?: store.currentId()
        editingId = targetId
        adapter.setSelected(targetId)
        renderEditor(list.first { it.id == targetId })
    }

    private fun onModelClicked(model: ModelConfig) {
        // 草稿态下点别的配置 → 视为放弃草稿
        if (isDraft) {
            isDraft = false
            draftId = null
        }
        if (model.id == editingId) return
        editingId = model.id
        adapter.setSelected(model.id)
        renderEditor(model)
    }

    /**
     * 进入「新建草稿」：仅清空表单 + 进入草稿态，不写 store、不动左侧列表。
     * 真正的写入发生在 [onSaveClicked] 校验通过之后。
     */
    private fun addNewModel() {
        isDraft = true
        draftId = UUID.randomUUID().toString()
        editingId = null
        adapter.setSelected(null)
        renderDraft()
    }

    private fun onSaveClicked() {
        val name = binding.etName.text?.toString().orEmpty().trim()
            .ifBlank { getString(R.string.ai_settings_model_default_name) }
        val key = binding.etApiKey.text?.toString().orEmpty().trim()
        val host = binding.etBaseUrl.text?.toString().orEmpty().trim()
        val model = binding.etModel.text?.toString().orEmpty().trim()
        if (key.isEmpty() || model.isEmpty()) {
            binding.tvTestStatus.setText(R.string.ai_settings_required)
            return
        }
        if (isDraft) {
            // 草稿落库：用预生成的 draftId 创建新配置，然后退出草稿态
            val newId = draftId ?: UUID.randomUUID().toString()
            val newModel = ModelConfig(
                id = newId,
                name = name,
                apiKey = key,
                baseUrl = host,
                model = model,
            )
            store.add(newModel)
            isDraft = false
            draftId = null
            editingId = newId
        } else {
            val id = editingId ?: return
            store.updateById(id) {
                it.copy(
                    name = name,
                    apiKey = key,
                    baseUrl = host,
                    model = model,
                )
            }
        }
        Toast.makeText(this, R.string.ai_settings_saved, Toast.LENGTH_SHORT).show()
        reloadAll()
    }

    private fun onDeleteClicked() {
        // 草稿态：删除按钮 = 放弃草稿、清空表单
        if (isDraft) {
            isDraft = false
            draftId = null
            editingId = null
            adapter.setSelected(null)
            // 草稿没落库，无需弹确认框，直接重置到当前已有配置
            reloadAll()
            return
        }
        val id = editingId ?: return
        val list = store.loadAll()
        val target = list.firstOrNull { it.id == id } ?: return
        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_AlertDialog_Dark)
            .setTitle(R.string.ai_settings_model_delete_title)
            .setMessage(getString(R.string.ai_settings_model_delete_message, target.name))
            .setPositiveButton(R.string.ai_settings_model_delete_confirm) { _, _ ->
                store.delete(id)
                editingId = null
                reloadAll()
            }
            .setNegativeButton(R.string.ai_settings_model_delete_cancel, null)
            .show()
    }

    private fun renderEditor(model: ModelConfig) {
        binding.tvEditTitle.text = model.name.ifBlank {
            getString(R.string.ai_settings_model_default_name)
        }
        binding.etName.setText(model.name)
        binding.etApiKey.setText(model.apiKey)
        binding.etBaseUrl.setText(model.baseUrl)
        binding.etModel.setText(model.model)
        binding.tvTestStatus.text = ""
        // 至少留 1 条时仍允许删除，store.delete 已保证兜底占位
        binding.btnDeleteModel.visibility = View.VISIBLE
        binding.formScroll.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
    }

    /** 草稿态：清空表单，标题写「新建配置」，删除按钮充当「放弃草稿」。 */
    private fun renderDraft() {
        binding.tvEditTitle.setText(R.string.ai_settings_add_model)
        binding.etName.setText("")
        binding.etApiKey.setText("")
        binding.etBaseUrl.setText("")
        binding.etModel.setText("gpt-4o-mini")
        binding.tvTestStatus.text = ""
        binding.btnDeleteModel.visibility = View.VISIBLE
        binding.formScroll.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
    }

    private fun applyScannedJson(raw: String) {
        try {
            val json = JSONObject(raw)
            val key = json.optString(FIELD_KEY).trim()
            val url = json.optString(FIELD_URL).trim()
            val model = json.optString(FIELD_MODEL).trim()
            if (key.isEmpty() && url.isEmpty() && model.isEmpty()) {
                binding.tvTestStatus.setText(R.string.ai_settings_scan_invalid)
                return
            }
            if (key.isNotEmpty()) binding.etApiKey.setText(key)
            if (url.isNotEmpty()) binding.etBaseUrl.setText(url)
            if (model.isNotEmpty()) binding.etModel.setText(model)
            binding.tvTestStatus.setText(R.string.ai_settings_scan_filled)
        } catch (e: JSONException) {
            binding.tvTestStatus.setText(R.string.ai_settings_scan_invalid)
        }
    }

    private fun runTest() {
        val key = binding.etApiKey.text?.toString().orEmpty().trim()
        val host = binding.etBaseUrl.text?.toString().orEmpty().trim()
        val model = binding.etModel.text?.toString().orEmpty().trim()
        if (key.isEmpty() || model.isEmpty()) {
            binding.tvTestStatus.setText(R.string.ai_settings_required)
            return
        }
        testingJob?.cancel()
        binding.btnTest.isEnabled = false
        binding.tvTestStatus.text = getString(R.string.ai_settings_testing)
        val tempConfig = ApiConfig(
            apiKey = key,
            baseUrl = host,
            model = model,
        )
        val tempHolder = OpenAiClientHolder { tempConfig }
        val openai: OpenAI = tempHolder.get()

        testingJob = lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { openai.models() }
            }
            binding.btnTest.isEnabled = true
            outcome
                .onSuccess {
                    binding.tvTestStatus.setText(R.string.ai_settings_test_ok)
                }
                .onFailure { e ->
                    val msg = e.message?.take(120) ?: e::class.java.simpleName
                    binding.tvTestStatus.text =
                        getString(R.string.ai_settings_test_fail, msg)
                }
        }
    }

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val FIELD_KEY = "key"
        const val FIELD_URL = "url"
        const val FIELD_MODEL = "model"
    }
}
