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
 * 「写作」技能运行页（原「联网」位改写为正式写作技能）：与 [LivelyActivity] / [TranslateActivity] 同构。
 */
class WritingActivity : AppCompatActivity() {

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

        binding.etInput.hint = getString(R.string.skill_run_input_hint_writing)
        // tvResult 不放占位文字：保持空白，等待流式结果
        applyModelToBtn()
        // 双击输出区 → 跳全屏查看
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
                            systemMessage(getString(R.string.ai_skill_writing_prompt)),
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
                    // 用户主动停止
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

    private fun onSettingsClicked() {
        startActivity(Intent(this, AiSettingsActivity::class.java))
    }

    private fun onAboutClicked() {
        SkillAboutActivity.start(this, SkillAboutActivity.KEY_WRITING)
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
            context.startActivity(Intent(context, WritingActivity::class.java))
        }
    }

    // ============================================================
    // 双击全屏（复用 AiMessageFullscreenActivity）
    // ============================================================

    private fun attachDoubleTapToFullscreen(target: TextView) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val text = target.text
                if (text.isNullOrBlank()) return true
                AiMessageFullscreenActivity.start(
                    this@WritingActivity,
                    text,
                    getString(R.string.ai_skill_writing_name),
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
