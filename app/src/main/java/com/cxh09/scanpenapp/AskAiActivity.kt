package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.util.Base64
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.chat.ContentPart
import com.aallam.openai.api.chat.ImagePart
import com.aallam.openai.api.chat.TextPart
import com.cxh09.scanpenapp.ai.AiSettingsActivity
import com.cxh09.scanpenapp.ai.ApiConfig
import com.cxh09.scanpenapp.ai.ApiConfigStore
import com.cxh09.scanpenapp.ai.Attachment
import com.cxh09.scanpenapp.ai.Conversation
import com.cxh09.scanpenapp.ai.ConversationStore
import com.cxh09.scanpenapp.ai.MessageRecord
import com.cxh09.scanpenapp.ai.ModelConfig
import com.cxh09.scanpenapp.ai.ModelConfigAdapter
import com.cxh09.scanpenapp.ai.ModelConfigStore
import com.cxh09.scanpenapp.ai.OpenAiClientHolder
import com.cxh09.scanpenapp.ai.userMessage
import com.cxh09.scanpenapp.databinding.ActivityAskAiBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.nio.charset.MalformedInputException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AskAiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAskAiBinding
    private lateinit var historyAdapter: ChatHistoryAdapter
    private lateinit var configStore: ApiConfigStore
    private lateinit var modelStore: ModelConfigStore
    private lateinit var modelDrawerAdapter: ModelConfigAdapter
    private lateinit var clientHolder: OpenAiClientHolder
    private lateinit var conversationStore: ConversationStore
    private var currentConversationId: Long? = null

    /**
     * Markdown 渲染器。
     * - 基础：标题、粗体、斜体、行内代码、代码块、列表、引用、链接
     * - 插件：删除线（StrikethroughPlugin）、表格（TablePlugin）
     * - 体积考虑：不引 image-glide / ext-latex（Glide 全家桶、WebView/LaTeX 体量大，与项目规则冲突）
     */
    private val markwon: Markwon by lazy {
        Markwon.builder(this)
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .build()
    }

    /**
     * 单线程后台执行器，专门用于流式 Markdown 解析，避免阻塞 UI 线程。
     * - 单线程：保证同一时刻只有一个解析任务在跑，避免流式 chunk 积压。
     * - 守护线程：Activity 销毁时不阻塞进程退出。
     */
    private val renderExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "md-render").apply { isDaemon = true }
    }

    /**
     * 流式渲染状态（每个 AI 气泡在 onSendClicked 开始时重置）：
     * - [renderGeneration] 单调递增，用于丢弃过时的解析结果（buffer 已被新 chunk 更新）。
     * - [lastRenderedNewlinePos] 上一次提交渲染的 buffer 中最后一个 '\n' 的位置；超过即代表有新的完整行。
     * - [lastRenderedLength] 上一次提交渲染时 buffer 的总长度，用于把「未闭合的尾行」按纯文本追加。
     */
    private var renderGeneration: Int = 0
    private var lastRenderedNewlinePos: Int = -1
    private var lastRenderedLength: Int = 0
    /**
     * 思考内容流式渲染游标：上一次提交的 buffer 长度。仅按增量 append，避免重置 TextView
     * 造成选择丢失 / 光标跳动；与 [renderGeneration] 配合，过期的写入会被丢弃。
     */
    private var lastRenderedThinkingLength: Int = 0

    /** 当前会话的消息历史（仅用于本轮 New Chat 内的多轮上下文）。 */
    private val sessionHistory: MutableList<ChatMessage> = mutableListOf()
    /**
     * 与 [sessionHistory] 严格平行的推理内容列表：同下标对应同一条消息的 reasoning_content。
     * - user 消息对应 null
     * - assistant 消息对应累积的 `thinkingBuffer.toString()`（可能为空串 → 实际也存空串，
     *   持久化端会判空省略字段）
     *
     * 设计上没用 [Map] 而用平行 [List]：与 [sessionHistory] 同步 push / clear 更不易错位。
     * 不直接挂到 [ChatMessage] 上是因为 [ChatMessage] 是 openai-kotlin 库的不可变类。
     */
    private val sessionReasoning: MutableList<String?> = mutableListOf()
    private var sending: Boolean = false
    /**
     * 思考模式本地镜像：初值在 onCreate 末尾从 [configStore] 同步，并随 [toggleThinkingMode]
     * 持久化回 store。buildChatRequest 读的是 store 里的 [ApiConfig.thinkingMode]，
     * 这里是 UI 状态源，保证重启 Activity 后按钮高亮与上次设置一致。
     */
    private var thinkingMode: Boolean = false

    /**
     * 当前会话的多模态附件（图片 / 文本文件）。生命周期与 [sessionHistory] 一致：
     * 「新对话」清空；同一会话内多轮发送时复用。
     * - 不持久化（仅内存态）。
     * - 不下发权限（全部走 SAF）。
     */
    private val attachments: MutableList<Attachment> = mutableListOf()

    /** 文本附件最大字节数（UTF-8 50KB）。 */
    private val attachmentMaxBytes: Long = 50L * 1024L

    /** 单张图片 base64 后最大允许字节数（1MB）。超过则按三级降级继续缩。 */
    private val attachmentImageMaxBase64Bytes: Long = 1024L * 1024L

    /** 拍照 launcher：回调 Boolean 表拍照成功；输出写入 [pendingPhotoUri] 指定的 FileProvider Uri。 */
    private lateinit var takePicture: ActivityResultLauncher<Uri>
    /** 选文件 launcher：SAF 选任意文本文件。 */
    private lateinit var pickFile: ActivityResultLauncher<String>
    /** 拍照时正在使用的临时文件 Uri（onCreate 之外不要主动清空，让回调处理）。 */
    private var pendingPhotoUri: Uri? = null
    /** 拍照时正在使用的临时文件对象（与 [pendingPhotoUri] 配对，拍照完成后用其 delete() 清理）。 */
    private var pendingPhotoFile: File? = null

    /** 左侧「历史对话」列表，运行期由用户首次发消息时追加。 */
    private val history: MutableList<ChatHistoryItem> = mutableListOf()
    private var nextHistoryId: Long = 1L
    /** 当前会话是否已在历史列表中创建过条目；「新对话」后会重置。 */
    private var currentSessionRecorded: Boolean = false
    private val timeFormatter = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

    /** 标记历史列表是否处于滚动中（滚动状态非 IDLE，覆盖 touch-scroll / fling）。 */
    private var historyScrolling: Boolean = false
    /** 标记本次按下的触摸位移是否已超过 touch slop，超过即视为滚动意图，长按删除不应触发。 */
    private var historyTouchMoved: Boolean = false
    private var historyTouchDownX: Float = 0f
    private var historyTouchDownY: Float = 0f
    private val historyTouchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskAiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        configStore = ApiConfigStore(this)
        modelStore = ModelConfigStore(this)
        clientHolder = OpenAiClientHolder { configStore.load() }
        conversationStore = ConversationStore(this)
        loadHistoryFromStore()

        setupHistoryList()
        setupInputBar()
        setupSettingsRow()
        setupModelDrawer()
        applyModelToInputBar()
        registerAttachmentPickers()

        // 同步持久化的思考模式到 UI（按钮高亮 + 局部状态）
        applyThinkingMode(modelStore.current().thinkingMode)

        if (!modelStore.current().isComplete) {
            openSettings()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    override fun onDestroy() {
        // 让任何进行中的渲染任务在回写 UI 时被 generation 校验直接丢弃
        renderGeneration++
        renderExecutor.shutdown()
        super.onDestroy()
    }

    private fun setupHistoryList() {
        historyAdapter = ChatHistoryAdapter(this)
        binding.lvHistory.adapter = historyAdapter
        binding.lvHistory.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                historyAdapter.getItem(position)?.let(::onHistoryClicked)
            }
        // 滚动期间不响应长按删除：双重保险
        // - OnScrollListener：滚动状态非 IDLE（touch-scroll / fling）时直接拦截
        // - OnTouchListener：跟踪 ACTION_DOWN 位置，ACTION_MOVE 超过 touch slop 即视为滚动意图
        binding.lvHistory.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, scrollState: Int) {
                historyScrolling = scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE
            }
            override fun onScroll(
                view: AbsListView,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int,
            ) = Unit
        })
        binding.lvHistory.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    historyTouchMoved = false
                    historyTouchDownX = ev.x
                    historyTouchDownY = ev.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.x - historyTouchDownX
                    val dy = ev.y - historyTouchDownY
                    if (dx * dx + dy * dy > historyTouchSlop * historyTouchSlop) {
                        historyTouchMoved = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    historyTouchMoved = false
                }
            }
            false
        }
        // 长按删除：滚动期间 / 触摸已位移时一律不触发，避免滑动时误弹删除确认
        binding.lvHistory.onItemLongClickListener =
            AdapterView.OnItemLongClickListener { _, _, position, _ ->
                if (historyScrolling || historyTouchMoved) return@OnItemLongClickListener false
                historyAdapter.getItem(position)?.let(::onHistoryLongPressed)
                true
            }
        // 历史列表初始为空，由用户首次发消息时再追加
        historyAdapter.submit(history)
    }

    private fun onHistoryLongPressed(item: ChatHistoryItem) {
        // 二次确认，避免误触清空上下文；不沿用 Toast，确认成本太低容易误删
        // 显式套用深色 Dialog 主题，避免系统默认的浅色背景与全站深色 UI 不一致
        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_AlertDialog_Dark)
            .setTitle(R.string.ai_history_delete_title)
            .setMessage(R.string.ai_history_delete_message)
            .setPositiveButton(R.string.ai_history_delete_confirm) { _, _ -> deleteHistoryItem(item) }
            .setNegativeButton(R.string.ai_history_delete_cancel, null)
            .show()
    }

    private fun deleteHistoryItem(item: ChatHistoryItem) {
        val removed = history.removeAll { it.id == item.id }
        if (!removed) return
        historyAdapter.submit(history)
        // 如果删的是当前正在查看的会话，把右侧消息区重置为空，避免出现「列表没了、消息还在」
        if (currentConversationId == item.id) {
            currentConversationId = null
            currentSessionRecorded = false
            sessionHistory.clear()
            binding.llMessages.removeAllViews()
            // 重置流式渲染状态，防止旧任务把文字写回已清空的 TextView
            renderGeneration++
            lastRenderedNewlinePos = -1
            lastRenderedLength = 0
            lastRenderedThinkingLength = 0
            lastRenderedLength = 0
            historyAdapter.setSelected(null)
        }
        // IO 线程删除持久化文件，主线程不阻塞
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                conversationStore.deleteConversation(item.id)
            }
        }
    }

    private fun setupInputBar() {
        binding.btnNewChat.setOnClickListener { startNewChat() }
        binding.btnQuick.setOnClickListener { toggleThinkingMode() }
        binding.btnModel.setOnClickListener {
            // 打开抽屉前刷新一次列表（用户可能刚改过配置）
            loadModelsIntoDrawer()
            binding.drawerRoot.openDrawer(GravityCompat.END)
        }
        binding.btnAdd.setOnClickListener { onAddClicked() }
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendClicked()
                true
            } else {
                false
            }
        }
        binding.btnSend.setOnClickListener { onSendClicked() }
    }

    private fun toggleThinkingMode() {
        applyThinkingMode(!thinkingMode)
        // 持久化到 ModelConfigStore 的当前选中配置；
        // onSendClicked 走 configStore.load().thinkingMode 拼装请求，二者互通
        modelStore.updateCurrent { it.copy(thinkingMode = thinkingMode) }
    }

    /**
     * 把 [enabled] 同时反映到按钮视觉（selected + 字体加粗）和局部 [thinkingMode] 字段。
     * 抽出来供 onCreate 初始化与 toggleThinkingMode 共用，避免两处视觉同步代码漂移。
     */
    private fun applyThinkingMode(enabled: Boolean) {
        thinkingMode = enabled
        binding.btnQuick.isSelected = enabled
        binding.btnQuick.setTypeface(
            null,
            if (enabled) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    private fun setupSettingsRow() {
        binding.settingsRow.setOnClickListener { openSettings() }
    }

    private fun openSettings() {
        startActivity(Intent(this, AiSettingsActivity::class.java))
    }

    /**
     * 初始化右侧模型选择抽屉：
     * - 列表 Adapter 用 [ModelConfigAdapter.VARIANT_DRAWER]（带打勾标记）
     * - 点击列表项 → 切换 current_id → 关闭抽屉 → 重建 client → 刷新输入框按钮文案
     * - 关闭按钮：仅关闭抽屉
     * - 跳到设置：跳到 [AiSettingsActivity]
     * - 抽屉默认 lockMode=locked，避免误滑
     */
    private fun setupModelDrawer() {
        modelDrawerAdapter = ModelConfigAdapter(this, ModelConfigAdapter.VARIANT_DRAWER)
        binding.lvModelsInDrawer.adapter = modelDrawerAdapter
        binding.lvModelsInDrawer.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                modelDrawerAdapter.getItem(position)?.let(::onDrawerModelPicked)
            }
        binding.btnCloseDrawer.setOnClickListener {
            binding.drawerRoot.closeDrawer(GravityCompat.END)
        }
        binding.drawerRoot.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        loadModelsIntoDrawer()
    }

    private fun onDrawerModelPicked(model: ModelConfig) {
        modelStore.setCurrent(model.id)
        binding.drawerRoot.closeDrawer(GravityCompat.END)
        clientHolder.rebuild()
        applyModelToInputBar()
    }

    /** 把当前选中模型的 name 渲染到输入框 [btnModel] 按钮上。 */
    private fun applyModelToInputBar() {
        val current = modelStore.current()
        binding.btnModel.text = current.name.ifBlank {
            getString(R.string.ai_settings_model_default_name)
        }
    }

    /** 抽屉打开前重新加载（用户可能从 AI 服务页改了配置）。 */
    private fun loadModelsIntoDrawer() {
        val list = modelStore.loadAll()
        modelDrawerAdapter.submit(list)
        modelDrawerAdapter.setSelected(modelStore.currentId())
        // 列表空时显示空态
        val empty = list.isEmpty()
        binding.tvDrawerEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.lvModelsInDrawer.visibility = if (empty) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // 从 AiSettingsActivity 返回后重读配置并刷新客户端
        clientHolder.rebuild()
        loadModelsIntoDrawer()
        applyModelToInputBar()
        // 思考模式可能也被改了，同步一下按钮高亮
        applyThinkingMode(modelStore.current().thinkingMode)
    }

    private fun startNewChat() {
        // Save current before switching
        saveCurrentConversation()

        sessionHistory.clear()
        sessionReasoning.clear()
        binding.llMessages.removeAllViews()
        currentSessionRecorded = false
        currentConversationId = null
        // 重置流式渲染状态，避免上一轮的尾部被新一轮误判为「已渲染」
        renderGeneration++
        lastRenderedNewlinePos = -1
        lastRenderedLength = 0
        clearInput()

        // 「新对话」= session 重置，附件也跟着清空，预览行 renderAttachmentPreview 会自己 GONE
        attachments.clear()
        renderAttachmentPreview()

        // Deselect in history
        historyAdapter.setSelected(null)
    }

    private fun onHistoryClicked(item: ChatHistoryItem) {
        // Save current conversation if it has unsaved changes
        saveCurrentConversation()

        lifecycleScope.launch {
            val conversation = withContext(Dispatchers.IO) {
                conversationStore.loadConversation(item.id)
            } ?: return@launch

            currentConversationId = item.id
            currentSessionRecorded = true

            // Rebuild sessionHistory for multi-turn context
            sessionHistory.clear()
            conversation.messages.forEach { msg ->
                val role = when (msg.role) {
                    "user" -> ChatRole.User
                    "assistant" -> ChatRole.Assistant
                    else -> ChatRole.User
                }
                sessionHistory.add(ChatMessage(role = role, content = msg.content))
            }

            // Rebuild UI
            showMessagesArea()
            binding.llMessages.removeAllViews()
            conversation.messages.forEach { msg ->
                when (msg.role) {
                    "user" -> appendUserMessage(msg.content)
                    "assistant" -> {
                        // 历史 assistant 消息：恢复 reasoning（如果有）到思考区，并切到 ANSWERING
                        // 阶段让思考块以折叠形式呈现（点 header 可展开看历史推理）；
                        // 没有 reasoning 时停留 PENDING（思考块 GONE，纯答案气泡）。
                        val views = appendAiMessage("")
                        markwon.setMarkdown(views.answer, msg.content)
                        val reasoning = msg.reasoning?.takeIf { it.isNotEmpty() }
                        if (reasoning != null) {
                            views.thinkingContent.text = reasoning
                            views.phase = AiMessageViews.PHASE_ANSWERING
                        }
                    }
                }
            }
            scrollMessagesToBottom()

            // Highlight selected
            historyAdapter.setSelected(item.id)

            // Reset streaming state
            renderGeneration++
            lastRenderedNewlinePos = -1
            lastRenderedLength = 0
            lastRenderedThinkingLength = 0

            clearInput()
        }
    }

    private fun onSendClicked() {
        if (sending) return
        val text = binding.etInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return

        val config: ApiConfig = configStore.load()
        if (!config.isComplete) {
            Toast.makeText(this, R.string.ai_error_no_config, Toast.LENGTH_SHORT).show()
            openSettings()
            return
        }

        hideKeyboard()
        sending = true
        binding.btnSend.isEnabled = false

        if (!currentSessionRecorded) {
            val now = timeFormatter.format(Date())
            val convId = System.currentTimeMillis()
            currentConversationId = convId
            history.add(0, ChatHistoryItem(id = convId, title = now, createdAt = System.currentTimeMillis()))
            historyAdapter.submit(history)
            currentSessionRecorded = true
        }

        appendUserMessage(text)
        sessionHistory.add(buildUserMessage(text))
        sessionReasoning.add(null)  // user 消息无 reasoning，与 sessionHistory 平行
        clearInput()

        val aiViews = appendAiMessage(getString(R.string.ai_msg_thinking))
        // 累积缓冲区：只在 IO 线程 append；UI 端通过节流 Handler 读取快照。
        val responseBuffer = StringBuilder()
        val thinkingBuffer = StringBuilder()
        var firstChunk = true
        // 每个 AI 气泡：重置流式渲染状态
        renderGeneration++
        lastRenderedNewlinePos = -1
        lastRenderedLength = 0
        lastRenderedThinkingLength = 0

        // 流式 UI 节流：~50ms debounce，避免每 chunk 都触发 layout + scroll + markdown 解析
        val uiHandler = Handler(Looper.getMainLooper())
        val uiUpdateRunnable = Runnable {
            val snapshot = responseBuffer.toString()
            val thinkingSnapshot = thinkingBuffer.toString()
            // text 更新与 scroll 合并到同一个 svMessages.post，消除帧延迟
            binding.svMessages.post {
                // 答案首次出现：自动切到 ANSWERING 阶段 → 思考区折叠、divider 显示
                if (snapshot.isNotEmpty() && aiViews.phase != AiMessageViews.PHASE_ANSWERING) {
                    aiViews.phase = AiMessageViews.PHASE_ANSWERING
                }
                renderThinkingText(thinkingSnapshot, aiViews)
                if (snapshot.isNotEmpty()) {
                    renderStreamedMarkdown(snapshot, aiViews.answer)
                }
                val bottom = binding.llMessages.bottom
                if (bottom > binding.svMessages.height) {
                    binding.svMessages.scrollTo(0, bottom - binding.svMessages.height)
                }
            }
        }

        lifecycleScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val openai = clientHolder.get()
                    val request = clientHolder.buildChatRequest(config, sessionHistory.toList())
                    openai.chatCompletions(request).collect { chunk ->
                        chunk.choices.forEach { choice ->
                            val delta = choice.delta
                            // 思考内容：仅当模型真的返回 reasoning_content 时才累积。
                            // 非推理模型 / 未开启思考模式时此字段始终为 null，buffer 保持空，
                            // renderThinkingText 会自动保持思考块 GONE，无副作用。
                            val reasoningPiece = delta?.reasoningContent
                            if (!reasoningPiece.isNullOrEmpty()) {
                                thinkingBuffer.append(reasoningPiece)
                            }
                            // 最终答案
                            val piece = delta?.content
                            if (!piece.isNullOrEmpty()) {
                                if (firstChunk) {
                                    // 首个有效 chunk 到来时清掉「正在思考…」占位
                                    responseBuffer.setLength(0)
                                    firstChunk = false
                                }
                                responseBuffer.append(piece)
                            }
                            // 任一通道有更新才调度一次，避免空跑 Runnable
                            if (!reasoningPiece.isNullOrEmpty() || !piece.isNullOrEmpty()) {
                                // 取消上次 pending 更新，重新调度（debounce 50ms）
                                uiHandler.removeCallbacks(uiUpdateRunnable)
                                uiHandler.postDelayed(uiUpdateRunnable, 50L)
                            }
                        }
                    }
                }
            }
            // 流结束 → 取消 pending 节流，立即用 Markdown 渲染最终文本
            uiHandler.removeCallbacks(uiUpdateRunnable)
            val finalText = responseBuffer.toString()
            val finalThinking = thinkingBuffer.toString()
            // 思考内容：直接覆盖为最终累积值，确保收尾完整（即便用户在收尾时折叠也不丢字符）
            if (finalThinking.isNotEmpty()) {
                binding.svMessages.post {
                    aiViews.thinkingContent.text = finalThinking
                }
            }
            // 流结束统一切到 ANSWERING：保证答案区可见（真实内容 / 空占位 / 错误信息都能呈现），
            // 思考区在 ANSWERING 默认折叠，符合用户「思考结束就收回」的要求。
            binding.svMessages.post {
                aiViews.phase = AiMessageViews.PHASE_ANSWERING
            }
            if (finalText.isNotEmpty()) {
                binding.svMessages.post {
                    markwon.setMarkdown(aiViews.answer, finalText)
                    val bottom = binding.llMessages.bottom
                    if (bottom > binding.svMessages.height) {
                        binding.svMessages.scrollTo(0, bottom - binding.svMessages.height)
                    }
                }
            }

            sending = false
            binding.btnSend.isEnabled = true
            outcome.fold(
                onSuccess = {
                    if (finalText.isNotEmpty()) {
                        sessionHistory.add(
                            ChatMessage(role = ChatRole.Assistant, content = finalText)
                        )
                        // 推理内容：空串时存 null，持久化端走 .takeIf { it.isNotEmpty() } 省略字段
                        sessionReasoning.add(
                            finalThinking.takeIf { it.isNotEmpty() }
                        )
                        saveCurrentConversation()
                    } else {
                        aiViews.answer.text = getString(R.string.ai_msg_empty)
                    }
                    scrollMessagesToBottom()
                },
                onFailure = { e ->
                    val msg = e.message?.take(120) ?: e::class.java.simpleName
                    aiViews.answer.text = getString(R.string.ai_error_send_fail, msg)
                    scrollMessagesToBottom()
                },
            )
        }
    }

    private fun appendUserMessage(text: String) {
        showMessagesArea()
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_ai_msg_user, binding.llMessages, false)
        view.findViewById<TextView>(R.id.tvMsgContent).text = text
        binding.llMessages.addView(view)
        scrollMessagesToBottom()
    }

    /**
     * 一次 AI 气泡内需要联动控制的 View 集合 + 思考/答案状态机。
     *
     * 三段式状态机（由 [phase] 持有）：
     * - [PHASE_PENDING]：初始（无 reasoning、无 answer），思考区 GONE，答案区显示"正在思考…"
     * - [PHASE_THINKING]：收到 reasoning_content（无 answer），思考区展开（"▾ 思考过程" + 正文），
     *   答案区 GONE（"正在思考…"占位被推理内容替代）
     * - [PHASE_ANSWERING]：收到 answer（首段 content），思考区自动折叠（"▸ 思考过程"），
     *   divider 出现把思考/答案分开；用户可点击 header 翻转 [userExpanded] 重新展开查看历史推理
     *
     * 触发：onSendClicked 流式收集里由 [renderThinkingText] 切到 THINKING，
     * [uiUpdateRunnable] 检测到 answer buffer 非空时切到 ANSWERING。
     * 历史消息 onHistoryClicked 不切（停留在 PENDING，无思考区、无 divider）。
     */
    private class AiMessageViews(
        context: Context,
        val thinkingSection: View,
        val thinkingHeader: TextView,
        val thinkingContent: TextView,
        val divider: View,
        val answer: TextView,
    ) {
        /** 用户手动展开思考内容；仅在 ANSWERING 阶段生效（THINKING 阶段始终展开）。 */
        private var userExpanded: Boolean = false

        var phase: Int = PHASE_PENDING
            set(value) {
                if (field == value) return
                field = value
                applyPhase()
            }

        init {
            thinkingHeader.setOnClickListener {
                // 仅在答案阶段允许用户主动折叠/展开（思考阶段始终展开，避免"没答案就看不到思考"）
                if (phase == PHASE_ANSWERING) {
                    userExpanded = !userExpanded
                    applyPhase()
                }
            }
            applyPhase()
        }

        private fun applyPhase() {
            when (phase) {
                PHASE_PENDING -> {
                    thinkingSection.visibility = View.GONE
                    thinkingContent.visibility = View.GONE
                    divider.visibility = View.GONE
                    answer.visibility = View.VISIBLE
                }
                PHASE_THINKING -> {
                    thinkingSection.visibility = View.VISIBLE
                    thinkingContent.visibility = View.VISIBLE
                    divider.visibility = View.GONE
                    answer.visibility = View.GONE  // 思考阶段：推理内容本身证明"在思考"
                    thinkingHeader.setText(R.string.ai_thinking_header_expanded)
                }
                PHASE_ANSWERING -> {
                    thinkingSection.visibility = View.VISIBLE
                    thinkingContent.visibility = if (userExpanded) View.VISIBLE else View.GONE
                    divider.visibility = View.VISIBLE
                    answer.visibility = View.VISIBLE
                    thinkingHeader.setText(
                        if (userExpanded) R.string.ai_thinking_header_expanded
                        else R.string.ai_thinking_header_collapsed
                    )
                }
            }
        }

        companion object {
            const val PHASE_PENDING = 0
            const val PHASE_THINKING = 1
            const val PHASE_ANSWERING = 2
        }
    }

    private fun appendAiMessage(answerText: String): AiMessageViews {
        showMessagesArea()
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_ai_msg_ai, binding.llMessages, false)
        val views = AiMessageViews(
            context = this,
            thinkingSection = view.findViewById(R.id.llThinkingSection),
            thinkingHeader = view.findViewById(R.id.tvThinkingHeader),
            thinkingContent = view.findViewById(R.id.tvThinkingContent),
            divider = view.findViewById(R.id.vDivider),
            answer = view.findViewById(R.id.tvMsgContent),
        )
        views.answer.text = answerText
        // 双击答案区 → 全屏查看当前文本
        attachDoubleTapToFullscreen(views.answer)
        binding.llMessages.addView(view)
        scrollMessagesToBottom()
        return views
    }

    /**
     * 给 AI 答案 TextView 绑定双击跳转全屏。
     * - 用 GestureDetector 仅消费 double tap，其它手势（单击 / 长按 / 滑动选择）一律 return false
     *   转发回 TextView 自身，保证 textIsSelectable 长按选择、单链上下文菜单等行为不受影响。
     * - 取触发瞬间的 view.text 而非 buffer：语义就是"看到啥看啥"，流式中段也允许跳转。
     * - 每次 appendAiMessage 调用一次：每个 AI 气泡独立一个 GestureDetector 闭包，Activity 结束随 View 一起回收。
     */
    private fun attachDoubleTapToFullscreen(target: TextView) {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val text = target.text
                if (text.isNullOrBlank()) return true
                AiMessageFullscreenActivity.start(
                    this@AskAiActivity,
                    text,
                    currentConversationTitle(),
                )
                return true
            }
        })
        target.setOnTouchListener { _, ev ->
            detector.onTouchEvent(ev)
            false
        }
    }

    /**
     * 当前对话的标题（用于全屏页 toolbar 显示）。
     * - 来源：[history] 列表里与 [currentConversationId] 匹配的 [ChatHistoryItem.title]
     * - fallback：null（[AiMessageFullscreenActivity] 内部回退到 "AI 回答" 字符串）
     *
     * 调用时机：用户双击 AI 回答气泡时。此时 [currentConversationId] 一定非空
     * （由 onSendClicked 首次发消息时设置 / onHistoryClicked 加载历史时设置），
     * 找得到 title 就传，找不到就 null 走 fallback。
     */
    private fun currentConversationTitle(): String? {
        val id = currentConversationId ?: return null
        return history.find { it.id == id }?.title
    }

    /**
     * 流式 Markdown 渲染。已在 UI 线程调用。
     * - 仅当 buffer 中出现「新的完整行」（最后一个 '\n' 位置比上次靠右）时，才把整段前缀丢给
     *   [renderExecutor] 做 Markdown 解析；解析完成后通过 `generation` 校验，把渲染结果与
     *   「未闭合的尾行」纯文本拼回 TextView，丢弃过时的结果。
     * - 还没有换行符的纯文本追加（partial 尾行）走 `TextView.append`，避免每 chunk 触发解析。
     */
    private fun renderStreamedMarkdown(buffer: String, target: TextView) {
        if (buffer.isEmpty()) return
        val lastNewline = buffer.lastIndexOf('\n')
        if (lastNewline > lastRenderedNewlinePos) {
            val toRender = buffer.substring(0, lastNewline + 1)
            val tail = buffer.substring(lastNewline + 1)
            lastRenderedNewlinePos = lastNewline
            lastRenderedLength = buffer.length
            val gen = ++renderGeneration
            renderExecutor.execute {
                val rendered = markwon.toMarkdown(toRender)
                target.post {
                    if (gen != renderGeneration) return@post
                    val sb = SpannableStringBuilder(rendered)
                    if (tail.isNotEmpty()) sb.append(tail)
                    target.text = sb
                }
            }
        } else if (buffer.length > lastRenderedLength) {
            // 还没攒到第一个换行符：纯文本追加，避免每 chunk 都做 Markdown 解析
            val append = buffer.substring(lastRenderedLength)
            lastRenderedLength = buffer.length
            target.append(append)
        }
    }

    /**
     * 流式追加思考内容。已在 UI 线程调用。
     * - 思考内容是纯文本，不做 Markdown 解析，直接按增量 append 到 [views.thinkingContent]。
     * - 首次出现非空快照时，把 phase 切到 THINKING（展开思考区、隐藏"正在思考…"占位）。
     * - 答案阶段持续 append 不会让用户看到（content 默认 GONE），用户点击 header 重新展开时可看到全部。
     * - 收尾由 onSendClicked 用 [AiMessageViews.thinkingContent] 直接 `text = finalThinking` 覆盖，
     *   本方法只负责中间过程的增量更新。
     */
    private fun renderThinkingText(snapshot: String, views: AiMessageViews) {
        if (snapshot.isEmpty()) return
        if (views.phase == AiMessageViews.PHASE_PENDING) {
            views.phase = AiMessageViews.PHASE_THINKING
        }
        if (snapshot.length > lastRenderedThinkingLength) {
            val append = snapshot.substring(lastRenderedThinkingLength)
            lastRenderedThinkingLength = snapshot.length
            views.thinkingContent.append(append)
        }
    }

    private fun showMessagesArea() {
        // 消息区始终可见，无需切换
    }

    private fun scrollMessagesToBottom() {
        binding.svMessages.post {
            val bottom = binding.llMessages.bottom
            if (bottom > binding.svMessages.height) {
                binding.svMessages.scrollTo(0, bottom - binding.svMessages.height)
            }
        }
    }

    private fun clearInput() {
        binding.etInput.setText("")
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etInput.windowToken, 0)
    }

    private fun loadHistoryFromStore() {
        lifecycleScope.launch {
            val conversations = withContext(Dispatchers.IO) {
                conversationStore.listConversations()
            }
            history.clear()
            nextHistoryId = 1L
            conversations.forEach { conv ->
                history.add(ChatHistoryItem(id = conv.id, title = conv.title, createdAt = conv.createdAt))
                if (conv.id >= nextHistoryId) nextHistoryId = conv.id + 1
            }
            historyAdapter.submit(history)
        }
    }

    private fun saveCurrentConversation() {
        val convId = currentConversationId ?: return
        if (sessionHistory.isEmpty()) return
        // 同步捕获数据，避免调用方（如 startNewChat）随后 clear sessionHistory 导致竞态
        val messages = sessionHistory.mapIndexed { idx, msg ->
            val roleStr = when (msg.role) {
                ChatRole.User -> "user"
                ChatRole.Assistant -> "assistant"
                ChatRole.System -> "system"
                else -> "user"
            }
            // reasoning 与 sessionReasoning 同下标；越界时回退 null（理论上不会发生）
            MessageRecord(
                role = roleStr,
                content = msg.content ?: "",
                reasoning = sessionReasoning.getOrNull(idx),
            )
        }
        val title = history.find { it.id == convId }?.title ?: ""
        val createdAt = history.find { it.id == convId }?.createdAt ?: convId
        val conversation = Conversation(id = convId, title = title, createdAt = createdAt, messages = messages)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                conversationStore.saveConversation(conversation)
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

    /**
     * 注册拍照 launcher 与选文件 launcher。
     * 必须在 onCreate 完成、super.onCreate 之后调用，回调里把 IO 工作下放到 [Dispatchers.IO]。
     * 取消选择（uri == null / success == false）= 无副作用（仅清理临时文件）。
     */
    private fun registerAttachmentPickers() {
        takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = pendingPhotoUri
            val file = pendingPhotoFile
            pendingPhotoUri = null
            pendingPhotoFile = null
            if (success && uri != null) {
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { loadImageAttachment(uri) }
                    // 不论成功失败都删临时文件，避免 cache 累积
                    file?.takeIf { it.exists() }?.delete()
                    if (result != null) {
                        attachments.add(result)
                        renderAttachmentPreview()
                    } else {
                        Toast.makeText(
                            this@AskAiActivity,
                            R.string.ai_attach_too_large,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            } else {
                // 拍照失败 / 用户取消 → 静默清理
                file?.takeIf { it.exists() }?.delete()
                if (!success) {
                    Toast.makeText(
                        this@AskAiActivity,
                        R.string.ai_attach_capture_fail,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
        pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { loadTextAttachment(uri) }
                if (result == null) {
                    Toast.makeText(
                        this@AskAiActivity,
                        R.string.ai_attach_not_text,
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    val wasTruncated = result.sizeBytes > attachmentMaxBytes
                    attachments.add(result)
                    renderAttachmentPreview()
                    if (wasTruncated) {
                        Toast.makeText(
                            this@AskAiActivity,
                            R.string.ai_attach_truncated,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * + 按钮：弹出选择面板（拍照 / 文件），二选一走对应 launcher。
     * 用 AlertDialog 列表实现，避免引入 BottomSheetDialog（包体更小）。
     */
    private fun onAddClicked() {
        val options = arrayOf(
            getString(R.string.ai_attach_pick_camera),
            getString(R.string.ai_attach_pick_file),
        )
        AlertDialog.Builder(this, R.style.Theme_ScanPenApp_AlertDialog_Dark)
            .setTitle(R.string.ai_attach_pick_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchTakePicture()
                    1 -> pickFile.launch("*/*")
                }
            }
            .show()
    }

    /**
     * 准备一个 [FileProvider] 暴露的临时文件 Uri 给系统相机写入。
     * - 路径：cacheDir/captures/img_<timestamp>.jpg（与 res/xml/file_paths.xml 的 cache-path 配套）。
     * - authority：$applicationId.fileprovider（与 AndroidManifest.xml 的 provider 配套）。
     * - 失败（IO 异常 / FileProvider 不可用）返回 null，由调用方 Toast 提示。
     */
    private fun launchTakePicture() {
        val capturesDir = File(cacheDir, "captures").apply { mkdirs() }
        val file = File(capturesDir, "img_${System.currentTimeMillis()}.jpg")
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.ai_attach_capture_fail, Toast.LENGTH_SHORT).show()
            return
        }
        pendingPhotoUri = uri
        pendingPhotoFile = file
        takePicture.launch(uri)
    }

    /**
     * 三级降级读取图片：
     * - 1024px / JPEG 80 → base64
     * - 仍 > 1MB → 512px / 70
     * - 仍 > 1MB → 256px / 50
     * 任何一级返回成功的 [ImageAttachment] 即停止；三级仍超 / 解码失败 → null。
     * 全程跑在 [Dispatchers.IO]；OOM 由调用方保护。
     */
    private fun loadImageAttachment(uri: Uri): Attachment.ImageAttachment? {
        val tiers = arrayOf(
            Tier(1024, 80),
            Tier(512, 70),
            Tier(256, 50),
        )
        for (tier in tiers) {
            val result = runCatching { compressToBase64(uri, tier) }.getOrNull() ?: continue
            if (result.base64Jpeg.length <= attachmentImageMaxBase64Bytes) return result
        }
        return null
    }

    /**
     * 单级降级：读 URI → 解码原图 → 缩到 [tier.maxEdge]px → JPEG [tier.quality] → base64。
     * 任一步骤失败 / OOM / 返回 null bitmap → 返回 null。
     */
    private fun compressToBase64(uri: Uri, tier: Tier): Attachment.ImageAttachment? {
        // 1) inJustDecodeBounds=true 取原图宽高，避免一次 decode 大图
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        // 2) 二次 decode 按 sampleSize 缩小内存峰值
        val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, tier.maxEdge)
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val sampled: Bitmap? = contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        }
        if (sampled == null) return null
        // 3) 精确缩放到目标最长边
        val scale = tier.maxEdge.toFloat() / longest
        val targetW: Int
        val targetH: Int
        if (scale >= 1f) {
            targetW = bounds.outWidth
            targetH = bounds.outHeight
        } else {
            targetW = (bounds.outWidth * scale).toInt().coerceAtLeast(1)
            targetH = (bounds.outHeight * scale).toInt().coerceAtLeast(1)
        }
        val scaled: Bitmap = Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
        if (scaled !== sampled) sampled.recycle()
        // 4) JPEG 压缩 → base64
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, tier.quality, baos)
        scaled.recycle()
        val bytes = baos.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return Attachment.ImageAttachment(
            base64Jpeg = base64,
            width = targetW,
            height = targetH,
        )
    }

    /** 标准 2 的幂降采样：保证 inSampleSize ≥ 1。 */
    private fun calculateInSampleSize(width: Int, height: Int, reqEdge: Int): Int {
        var sample = 1
        val longest = maxOf(width, height)
        while (longest / (sample * 2) >= reqEdge) {
            sample *= 2
        }
        return sample
    }

    private data class Tier(val maxEdge: Int, val quality: Int)

    /**
     * 读文本文件：UTF-8 + 最多 50KB，超过截断并在末尾追加 `[已截断，原文共 X.X KB]`。
     * - 非 UTF-8（[MalformedInputException]）→ 返回 null。
     * - 文件元信息 [OpenableColumns.DISPLAY_NAME] / [OpenableColumns.SIZE] 拿不到时给默认值。
     */
    private fun loadTextAttachment(uri: Uri): Attachment.TextAttachment? {
        // 1) 元信息（displayName / sizeBytes），query 可能 null（部分 provider 不支持）
        var displayName: String? = null
        var sizeBytes: Long = -1L
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
                }
            }
        }
        val finalName = displayName?.takeIf { it.isNotBlank() } ?: "未命名.txt"
        // 2) 字节流 + 严格 UTF-8 解码
        val stream = contentResolver.openInputStream(uri) ?: return null
        val decoder = StandardCharsets.UTF_8.newDecoder().apply {
            onMalformedInput(CodingErrorAction.REPORT)
            onUnmappableCharacter(CodingErrorAction.REPORT)
        }
        val sb = StringBuilder()
        var readBytes = 0L
        var truncated = false
        val maxBytes = attachmentMaxBytes
        try {
            stream.use { input ->
                val reader = BufferedReader(InputStreamReader(input, decoder))
                val buf = CharArray(4 * 1024)
                while (true) {
                    if (readBytes >= maxBytes) {
                        truncated = true
                        break
                    }
                    val n = reader.read(buf)
                    if (n <= 0) break
                    val chunk = String(buf, 0, n)
                    val chunkBytes = chunk.toByteArray(StandardCharsets.UTF_8).size
                    if (readBytes + chunkBytes > maxBytes) {
                        // 末段按字节截断后用 UTF-8 解码，避免把多字节字符截成一半
                        val remain = (maxBytes - readBytes).toInt().coerceAtLeast(0)
                        if (remain > 0) {
                            val full = chunk.toByteArray(StandardCharsets.UTF_8)
                            val safeEnd = findUtf8SafeEnd(full, remain)
                            sb.append(full, 0, safeEnd, StandardCharsets.UTF_8)
                            readBytes += safeEnd
                        }
                        truncated = true
                        break
                    }
                    sb.append(chunk)
                    readBytes += chunkBytes
                }
            }
        } catch (_: MalformedInputException) {
            return null
        } catch (_: java.nio.charset.UnmappableCharacterException) {
            return null
        }
        if (truncated) {
            // 实际 sizeBytes 用底层提供的（更接近「原文大小」），缺失时回退实际读到的字节数
            val reportedSize = if (sizeBytes > 0) sizeBytes else readBytes
            sb.append("\n[已截断，原文共 ")
            sb.append(String.format(Locale.ROOT, "%.1f", reportedSize / 1024.0))
            sb.append(" KB]")
            // 同步把 sizeBytes 标成「原文大小」便于 chip 上展示
            sizeBytes = reportedSize
        } else if (sizeBytes <= 0) {
            // 完整读完且 provider 未提供大小：回退为读到的字节数
            sizeBytes = readBytes
        }
        return Attachment.TextAttachment(
            displayName = finalName,
            content = sb.toString(),
            sizeBytes = sizeBytes,
        )
    }

    /**
     * 在 UTF-8 字节数组中找到 ≤ [limit] 处的安全边界，保证不切碎多字节字符。
     * 直接返回 ≤ limit 且字符边界的位置。
     */
    private fun findUtf8SafeEnd(bytes: ByteArray, limit: Int): Int {
        var end = limit.coerceAtMost(bytes.size)
        // 向前回退最多 3 个字节（UTF-8 多字节序列最多 4 字节，但首字节已计入时回退 3 即可）
        var i = 0
        while (i < 3 && end > 0 && end < bytes.size) {
            val b = bytes[end].toInt() and 0xFF
            // 10xxxxxx = 后续字节；遇到 0xxxxxxx / 11xxxxxx 视为字符边界
            if (b and 0xC0 != 0x80) return end
            end--
            i++
        }
        return end
    }

    /**
     * 按当前 [attachments] 重新渲染 chip 行：
     * - 0 个 → 整个 HorizontalScrollView GONE
     * - N 个 → VISIBLE，按顺序 inflate [item_attachment_chip.xml]
     *
     * 注意：× 按钮回调里用 [container].indexOfChild 重新定位 index，
     * 避免 forEachIndexed 的闭包在并发场景下取到过期 index。
     */
    private fun renderAttachmentPreview() {
        val container = binding.attachmentChipContainer
        container.removeAllViews()
        if (attachments.isEmpty()) {
            binding.attachmentPreview.visibility = View.GONE
            return
        }
        binding.attachmentPreview.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        attachments.forEach { att ->
            val chip = inflater.inflate(R.layout.item_attachment_chip, container, false)
            when (att) {
                is Attachment.ImageAttachment -> {
                    val iv = chip.findViewById<ImageView>(R.id.ivThumb)
                    val bytes = Base64.decode(att.base64Jpeg, Base64.DEFAULT)
                    val bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        iv.setImageBitmap(bmp)
                        iv.visibility = View.VISIBLE
                    } else {
                        // 极端情况（内存紧张/数据损坏）：占位一个空 ImageView
                        iv.visibility = View.VISIBLE
                    }
                }
                is Attachment.TextAttachment -> {
                    val name = chip.findViewById<TextView>(R.id.tvName)
                    val size = chip.findViewById<TextView>(R.id.tvSize)
                    val ll = chip.findViewById<View>(R.id.llText)
                    name.text = if (att.displayName.length > 12) {
                        att.displayName.take(12) + "…"
                    } else {
                        att.displayName
                    }
                    size.text = Attachment.formatSize(att.sizeBytes)
                    ll.visibility = View.VISIBLE
                }
            }
            val remove = chip.findViewById<View>(R.id.btnRemove)
            remove.setOnClickListener {
                val currentIndex = container.indexOfChild(chip)
                if (currentIndex >= 0 && currentIndex < attachments.size) {
                    attachments.removeAt(currentIndex)
                }
                renderAttachmentPreview()
            }
            container.addView(chip)
        }
    }

    /**
     * 构造 user 消息：
     * - 0 附件 → 纯文本 [userMessage]
     * - N 附件 → 多模态 [ChatMessage]（1 TextPart + N ImagePart）
     *
     * 文本附件合并到一个 TextPart 中（带 `[文件: name]\n<content>\n---\n` 前缀）。
     * 图片附件走 data URL `data:image/jpeg;base64,...`，detail = "auto" 让模型自适应分辨率。
     *
     * 注意：调用方在发送后**不**清空 [attachments]，同一 session 后续消息可继续复用。
     */
    private fun buildUserMessage(text: String): ChatMessage {
        if (attachments.isEmpty()) return userMessage(text)
        val parts = mutableListOf<ContentPart>()
        val sb = StringBuilder()
        sb.append(text).append("\n\n")
        attachments.forEach { att ->
            when (att) {
                is Attachment.TextAttachment -> {
                    sb.append("[文件: ").append(att.displayName).append("]\n")
                        .append(att.content).append("\n---\n")
                }
                is Attachment.ImageAttachment -> {
                    // 直接用 ImagePart(url, detail) 副构造（ImageURL 嵌在 ImagePart 里）
                    parts.add(
                        ImagePart(
                            url = "data:image/jpeg;base64,${att.base64Jpeg}",
                            detail = "auto",
                        )
                    )
                }
            }
        }
        parts.add(0, TextPart(sb.toString()))
        return ChatMessage(role = ChatRole.User, content = parts)
    }
}
