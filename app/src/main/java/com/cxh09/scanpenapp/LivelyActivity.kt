package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.cxh09.scanpenapp.ai.AiSettingsActivity
import com.cxh09.scanpenapp.ai.ApiConfig
import com.cxh09.scanpenapp.ai.ApiConfigStore
import com.cxh09.scanpenapp.ai.ModelConfigStore
import com.cxh09.scanpenapp.ai.ModelDrawerBinder
import com.cxh09.scanpenapp.ai.OpenAiClientHolder
import com.cxh09.scanpenapp.ai.systemMessage
import com.cxh09.scanpenapp.ai.userMessage
import com.cxh09.scanpenapp.databinding.ActivitySkillRunBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「灵动表达」技能运行页：
 * - 左侧 80dp 边栏：3 个 icon+文字 按钮（返回 / API 设置 / 关于）
 * - 右侧主区：上下 1:1 分割
 *   - 上半：EditText（多行，可滚动）+ 模型按钮 + 运行按钮
 *   - 下半：ScrollView + TextView（流式渲染 AI 结果）
 *
 * 单轮对话：仅 system prompt（[R.string.ai_skill_lively_prompt]）+ user 输入，无多轮上下文。
 * 复用 [OpenAiClientHolder] / [ApiConfigStore] / [ModelConfigStore]，与 AskAiActivity 共用同一份配置。
 */
class LivelyActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySkillRunBinding
    private lateinit var configStore: ApiConfigStore
    private lateinit var modelStore: ModelConfigStore
    private lateinit var clientHolder: OpenAiClientHolder
    private lateinit var modelDrawer: ModelDrawerBinder

    private var sending: Boolean = false
    private var sendingJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillRunBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        configStore = ApiConfigStore(this)
        modelStore = ModelConfigStore(this)
        clientHolder = OpenAiClientHolder { configStore.load() }
        // 复用 AskAiActivity 同款右侧抽屉选模型（含跳 AI 服务入口）
        modelDrawer = ModelDrawerBinder(this, modelStore) {
            clientHolder.rebuild()
            applyModelToBtn()
        }.also { it.bind() }

        binding.etInput.hint = getString(R.string.skill_run_input_hint_lively)
        // tvResult 不放占位文字：保持空白，等待流式结果
        applyModelToBtn()
        // 双击输出区 → 跳全屏查看（复用 AskAiActivity 的 AiMessageFullscreenActivity）
        attachDoubleTapToFullscreen(binding.tvResult)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSettings.setOnClickListener { onSettingsClicked() }
        binding.btnAbout.setOnClickListener { onAboutClicked() }
        binding.btnModel.setOnClickListener { modelDrawer.open() }
        binding.btnRun.setOnClickListener {
            if (sending) onStopClicked() else onRunClicked()
        }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                if (!sending) onRunClicked()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回：配置可能变了，重建 client + 刷新模型名
        clientHolder.rebuild()
        applyModelToBtn()
        // 同步用户在 AI 服务页可能改过的配置
        modelDrawer.reload()
    }

    override fun onDestroy() {
        sendingJob?.cancel()
        super.onDestroy()
    }

    private fun onRunClicked() {
        if (sending) return
        val text = binding.etInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, R.string.skill_run_empty_hint, Toast.LENGTH_SHORT).show()
            binding.etInput.requestFocus()
            return
        }

        val config: ApiConfig = configStore.load()
        if (!config.isComplete) {
            Toast.makeText(this, R.string.skill_run_error_no_config, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AiSettingsActivity::class.java))
            return
        }

        hideKeyboard()
        sending = true
        updateRunButton(sending = true)
        // 清空旧结果，开始新一轮
        binding.tvResult.text = ""

        val responseBuffer = StringBuilder()
        val uiHandler = Handler(Looper.getMainLooper())
        val uiUpdateRunnable = Runnable {
            binding.outputSection.post {
                binding.tvResult.append(responseBuffer.toString())
                responseBuffer.setLength(0)
                scrollResultToBottom()
            }
        }

        sendingJob = lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val openai = clientHolder.get()
                    val request = clientHolder.buildChatRequest(
                        config,
                        listOf(
                            systemMessage(getString(R.string.ai_skill_lively_prompt)),
                            userMessage(text),
                        ),
                    )
                    openai.chatCompletions(request).collect { chunk ->
                        chunk.choices.forEach { choice ->
                            val piece = choice.delta?.content
                            if (!piece.isNullOrEmpty()) {
                                responseBuffer.append(piece)
                                uiHandler.removeCallbacks(uiUpdateRunnable)
                                uiHandler.postDelayed(uiUpdateRunnable, 50L)
                            }
                        }
                    }
                }
            }
            uiHandler.removeCallbacks(uiUpdateRunnable)
            // 把 buffer 残余 flush
            if (responseBuffer.isNotEmpty()) {
                binding.outputSection.post {
                    binding.tvResult.append(responseBuffer.toString())
                    responseBuffer.setLength(0)
                    scrollResultToBottom()
                }
            }

            sending = false
            updateRunButton(sending = false)

            val exception = outcome.exceptionOrNull()
            when {
                exception is CancellationException -> {
                    // 用户主动停止：保留已累积内容
                }
                outcome.isSuccess -> {
                    if (binding.tvResult.text.isNullOrBlank()) {
                        binding.tvResult.text = getString(R.string.ai_msg_empty)
                    }
                }
                else -> {
                    val msg = exception?.message?.take(120)
                        ?: exception?.javaClass?.simpleName
                        ?: "未知错误"
                    binding.tvResult.text = getString(R.string.ai_error_send_fail, msg)
                }
            }
            scrollResultToBottom()
        }
    }

    private fun onStopClicked() {
        sendingJob?.cancel()
    }

    /**
     * 运行按钮 toggle 状态：
     * - sending=true → 显示「×」停止图标 + R.string.skill_run_stop
     * - sending=false → 显示「发送」图标 + R.string.skill_run_action
     */
    private fun updateRunButton(sending: Boolean) {
        val iconView = (binding.btnRun.getChildAt(0) as? ImageView) ?: return
        iconView.setImageResource(if (sending) R.drawable.ic_close else R.drawable.ic_send)
        binding.btnRun.contentDescription = getString(
            if (sending) R.string.skill_run_stop else R.string.skill_run_action
        )
    }

    private fun scrollResultToBottom() {
        binding.outputSection.post {
            val bottom = binding.tvResult.bottom
            if (bottom > binding.outputSection.height) {
                binding.outputSection.scrollTo(0, bottom - binding.outputSection.height)
            }
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    private fun applyFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    // ============================================================
    // 侧边栏：API 设置 / 关于
    // ============================================================

    /** 跳到 AI 服务设置页：用户可在那里填 API Key / Model 等。 */
    private fun onSettingsClicked() {
        startActivity(Intent(this, AiSettingsActivity::class.java))
    }

    /**
     * 「关于」弹窗：显示 App 名称 + 版本号 + 简短说明。
     * - 不做新 Activity，轻量 AlertDialog 实现
     * - 版本号取 PackageManager（debug/release 都拿得到）
     */
    private fun onAboutClicked() {
        // 跳转技能「关于」新 Activity，展示 logo / 名称 / 介绍 / 作者 cxh09
        SkillAboutActivity.start(this, SkillAboutActivity.KEY_LIVELY)
    }

    // ============================================================
    // 模型选择（输入框内 btnModel）
    // ============================================================

    private fun applyModelToBtn() {
        val current = modelStore.current()
        binding.btnModel.text = current.name.ifBlank {
            getString(R.string.ai_settings_model_untitled)
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, LivelyActivity::class.java))
        }
    }

    // ============================================================
    // 双击全屏（复用 AiMessageFullscreenActivity）
    // ============================================================

    /**
     * 给输出 TextView 绑定双击跳转全屏。
     * - 复用 [AskAiActivity] 同款 [AiMessageFullscreenActivity.start]，避免自己造一个全屏页
     * - 用 GestureDetector 仅消费 double tap；其它手势（单击 / 长按 / 滑动选择）return false
     *   转发回 TextView 自身，保证 textIsSelectable 长按选择不被破坏
     * - title 传技能名（"灵动表达"），让全屏页 toolbar 上下文清晰
     * - conversationId 传 null：技能页是单轮，不属于任何持久化对话
     */
    private fun attachDoubleTapToFullscreen(target: TextView) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val text = target.text
                if (text.isNullOrBlank()) return true
                AiMessageFullscreenActivity.start(
                    this@LivelyActivity,
                    text,
                    getString(R.string.ai_skill_lively_name),
                    null,
                )
                return true
            }
        })
        target.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            false
        }
    }
}
