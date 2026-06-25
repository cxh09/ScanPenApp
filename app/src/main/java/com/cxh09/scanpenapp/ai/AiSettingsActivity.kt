package com.cxh09.scanpenapp.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
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

/**
 * AI 服务设置页面。
 *
 * - 负责"收集 + 校验 + 持久化 ApiConfig"，通过 [ApiConfigStore] 写入 SharedPreferences。
 * - 支持扫一扫：识别到的二维码内容会被解析为 `{"key","url","model"}` 的 JSON，
 *   解析成功后自动回填到对应输入框。
 * - 保存后自动 [finish] 回到前一页，前一页 [onResume] 时从 Store 重读最新配置。
 */
class AiSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiSettingsBinding
    private lateinit var store: ApiConfigStore
    private var testingJob: Job? = null

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

        store = ApiConfigStore(this)

        loadConfig()
        setupListeners()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onDestroy() {
        super.onDestroy()
        testingJob?.cancel()
    }

    private fun loadConfig() {
        val current = store.load()
        binding.etApiKey.setText(current.apiKey)
        binding.etBaseUrl.setText(current.baseUrl)
        binding.etModel.setText(
            current.model.ifBlank { getString(R.string.ai_settings_model_hint) }
                .takeIf { it.isNotBlank() } ?: ""
        )
        if (binding.etModel.text.isNullOrBlank()) {
            binding.etModel.setText("gpt-4o-mini")
        }
    }

    private fun setupListeners() {
        // 返回
        binding.btnBack.setOnClickListener { finish() }

        // 保存
        binding.btnSave.setOnClickListener {
            if (validateAndSave()) {
                finish()
            }
        }

        // 扫一扫
        binding.btnScan.setOnClickListener {
            scanLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        // 分享
        binding.btnShare.setOnClickListener {
            startActivity(Intent(this, QrShareActivity::class.java))
        }

        // Base URL 预设
        binding.btnPresetDeepseek.setOnClickListener {
            binding.etBaseUrl.setText("https://api.deepseek.com/v1")
        }
        binding.btnPresetOpencode.setOnClickListener {
            binding.etBaseUrl.setText("https://opencode.ai/zen/go/v1")
        }
        // 智谱 GLM 免费一键预设：填齐 Key/BaseURL/Model
        binding.btnPresetGlm.setOnClickListener {
            binding.etApiKey.setText("f8be67658e91407eaf703a92e0e1e325.ULwVYUtaToi2AJj0")
            binding.etBaseUrl.setText("https://open.bigmodel.cn/api/paas/v4")
            binding.etModel.setText("GLM-4.7-Flash")
            binding.tvTestStatus.text = ""
        }

        // 测试连接
        binding.btnTest.setOnClickListener { runTest() }

        // 键盘 ActionNext / ActionDone 跳转
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
     * 解析扫码结果，期望 JSON 形如：
     * ```
     * { "key": "...", "url": "...", "model": "..." }
     * ```
     * 任一字段不存在或为空都不会破坏既有输入。
     */
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

    private fun validateAndSave(): Boolean {
        val key = binding.etApiKey.text?.toString().orEmpty().trim()
        val host = binding.etBaseUrl.text?.toString().orEmpty().trim()
        val model = binding.etModel.text?.toString().orEmpty().trim()
        if (key.isEmpty() || model.isEmpty()) {
            binding.tvTestStatus.setText(R.string.ai_settings_required)
            return false
        }
        store.save(ApiConfig(apiKey = key, baseUrl = host, model = model))
        return true
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
        val tempConfig = ApiConfig(apiKey = key, baseUrl = host, model = model)
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
