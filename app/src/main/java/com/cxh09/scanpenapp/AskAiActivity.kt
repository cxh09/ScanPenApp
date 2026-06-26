package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.cxh09.scanpenapp.ai.AiSettingsActivity
import com.cxh09.scanpenapp.ai.ApiConfig
import com.cxh09.scanpenapp.ai.ApiConfigStore
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

    /** 当前会话的消息历史（仅用于本轮 New Chat 内的多轮上下文）。 */
    private val sessionHistory: MutableList<ChatMessage> = mutableListOf()
    private var sending: Boolean = false
    /**
     * 思考模式本地镜像：初值在 onCreate 末尾从 [configStore] 同步，并随 [toggleThinkingMode]
     * 持久化回 store。buildChatRequest 读的是 store 里的 [ApiConfig.thinkingMode]，
     * 这里是 UI 状态源，保证重启 Activity 后按钮高亮与上次设置一致。
     */
    private var thinkingMode: Boolean = false

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
        binding.btnGotoSettings.setOnClickListener {
            binding.drawerRoot.closeDrawer(GravityCompat.END)
            openSettings()
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
        binding.llMessages.removeAllViews()
        currentSessionRecorded = false
        currentConversationId = null
        // 重置流式渲染状态，避免上一轮的尾部被新一轮误判为「已渲染」
        renderGeneration++
        lastRenderedNewlinePos = -1
        lastRenderedLength = 0
        clearInput()

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
                        val bubble = appendAiMessage("")
                        markwon.setMarkdown(bubble, msg.content)
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
        sessionHistory.add(userMessage(text))
        clearInput()

        val thinkingBubble = appendAiMessage(getString(R.string.ai_msg_thinking))
        // 累积缓冲区：只在 IO 线程 append；UI 端通过节流 Handler 读取快照。
        val responseBuffer = StringBuilder()
        var firstChunk = true
        // 每个 AI 气泡：重置流式渲染状态
        renderGeneration++
        lastRenderedNewlinePos = -1
        lastRenderedLength = 0

        // 流式 UI 节流：~50ms debounce，避免每 chunk 都触发 layout + scroll + markdown 解析
        val uiHandler = Handler(Looper.getMainLooper())
        val uiUpdateRunnable = Runnable {
            val snapshot = responseBuffer.toString()
            // text 更新与 scroll 合并到同一个 svMessages.post，消除帧延迟
            binding.svMessages.post {
                renderStreamedMarkdown(snapshot, thinkingBubble)
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
                            val piece = choice.delta?.content
                            if (!piece.isNullOrEmpty()) {
                                if (firstChunk) {
                                    // 首个有效 chunk 到来时清掉「正在思考…」占位
                                    responseBuffer.setLength(0)
                                    firstChunk = false
                                }
                                responseBuffer.append(piece)
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
            if (finalText.isNotEmpty()) {
                binding.svMessages.post {
                    markwon.setMarkdown(thinkingBubble, finalText)
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
                        saveCurrentConversation()
                    } else {
                        thinkingBubble.text = getString(R.string.ai_msg_empty)
                    }
                    scrollMessagesToBottom()
                },
                onFailure = { e ->
                    val msg = e.message?.take(120) ?: e::class.java.simpleName
                    thinkingBubble.text = getString(R.string.ai_error_send_fail, msg)
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

    private fun appendAiMessage(text: String): TextView {
        showMessagesArea()
        val view = LayoutInflater.from(this)
            .inflate(R.layout.item_ai_msg_ai, binding.llMessages, false)
        val bubble = view.findViewById<TextView>(R.id.tvMsgContent)
        bubble.text = text
        binding.llMessages.addView(view)
        scrollMessagesToBottom()
        return bubble
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
        val messages = sessionHistory.map { msg ->
            val roleStr = when (msg.role) {
                ChatRole.User -> "user"
                ChatRole.Assistant -> "assistant"
                ChatRole.System -> "system"
                else -> "user"
            }
            MessageRecord(role = roleStr, content = msg.content ?: "")
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
}
