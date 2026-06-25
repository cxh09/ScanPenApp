package com.cxh09.scanpenapp

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivityMicroChatBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微聊页面。
 *
 * 左侧：动态维护的会话/联系人列表；
 * 右侧：当前会话标题、聊天记录、底部输入栏。
 *
 * 数据全部由用户在运行时创建——不再有静态占位会话。
 */
class MicroChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMicroChatBinding
    private lateinit var contactAdapter: ChatContactAdapter
    private lateinit var messageAdapter: ChatMessageAdapter

    /** 会话数据：id → 消息列表。可在运行中追加消息。 */
    private val conversations: MutableMap<Long, MutableList<ChatMessage>> = mutableMapOf()

    /** 联系人/会话列表，按时间顺序追加。 */
    private val contacts: MutableList<ChatContact> = mutableListOf()

    /** 当前选中的 contactId，-1 表示无选中。 */
    private var activeContactId: Long = -1L

    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMicroChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        // 先初始化 messageAdapter，再初始化 contactAdapter（联系人选中时需要渲染消息）。
        setupMessageList()
        setupContactList()
        setupInputBar()
        renderEmptyState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    // ============================ 列表 ============================

    private fun setupContactList() {
        contactAdapter = ChatContactAdapter(this)
        contactAdapter.submit(contacts)
        binding.lvContacts.adapter = contactAdapter
        binding.lvContacts.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, position, _ ->
                switchConversation(position)
            }
    }

    private fun setupMessageList() {
        messageAdapter = ChatMessageAdapter(this)
        messageAdapter.submit(emptyList())
        binding.lvMessages.adapter = messageAdapter
    }

    private fun switchConversation(position: Int) {
        val contact = contactAdapter.getItem(position) ?: return
        activeContactId = contact.id
        contactAdapter.setSelection(contact.id)
        binding.lvContacts.setSelection(position)
        renderConversation(contact)
    }

    private fun renderConversation(contact: ChatContact) {
        binding.tvChatTitle.text = contact.name
        val msgs = conversations[contact.id].orEmpty()
        messageAdapter.submit(msgs)
        scrollMessagesToBottom()
    }

    private fun renderEmptyState() {
        binding.tvChatTitle.text = ""
        messageAdapter.submit(emptyList())
        binding.tvEmptyHint.visibility = if (contacts.isEmpty()) View.VISIBLE else View.GONE
    }

    // ============================ 新建对话 ============================

    private fun createNewConversation(): Long {
        val now = timeFormatter.format(Date())
        val id = nextContactId()
        val contact = ChatContact(
            id = id,
            name = now,
            preview = "",
            time = "",
            avatarColor = 0xFF7DB9E8.toInt(),
        )
        contacts.add(contact)
        conversations[id] = mutableListOf()
        activeContactId = id
        contactAdapter.setSelection(id)
        contactAdapter.notifyDataSetChanged()
        // 滚到最底部，让用户看到新条目
        binding.lvContacts.post {
            val position = contacts.indexOfFirst { it.id == id }
            if (position >= 0) {
                binding.lvContacts.setSelection(position)
            }
        }
        binding.tvChatTitle.text = now
        binding.tvEmptyHint.visibility = View.GONE
        return id
    }

    private fun nextContactId(): Long {
        val ids = contacts.map { it.id }
        val maxId = ids.maxOrNull() ?: 0L
        return maxOf(maxId + 1L, 1L)
    }

    // ============================ 发送消息 ============================

    private fun setupInputBar() {
        binding.btnNewChat.setOnClickListener { createNewConversation() }
        binding.etChatInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                onSendClicked()
                true
            } else {
                false
            }
        }
        binding.btnSend.setOnClickListener { onSendClicked() }
    }

    private fun onSendClicked() {
        val text = binding.etChatInput.text?.toString().orEmpty().trim()
        if (text.isEmpty()) return

        if (activeContactId < 0L) {
            // 没有选中任何对话时，自动开一个新对话再发送
            createNewConversation()
        }
        val now = timeFormatter.format(Date())
        val message = ChatMessage(
            id = System.currentTimeMillis(),
            sender = "",
            content = text,
            isMine = true,
            time = now,
            avatarColor = 0xFF7DB9E8.toInt(),
        )
        conversations.getOrPut(activeContactId) { mutableListOf() }.add(message)
        // 更新左侧当前会话的预览与时间
        val idx = contacts.indexOfFirst { it.id == activeContactId }
        if (idx >= 0) {
            val current = contacts[idx]
            contacts[idx] = current.copy(
                name = current.name,
                preview = truncatePreview(text),
                time = now,
            )
            contactAdapter.notifyDataSetChanged()
        }
        messageAdapter.submit(conversations[activeContactId].orEmpty())
        // 新建会话后，第一次需要把标题刷到 tvChatTitle
        if (binding.tvChatTitle.text.isNullOrEmpty()) {
            val current = contacts.firstOrNull { it.id == activeContactId }
            if (current != null) binding.tvChatTitle.text = current.name
        }
        scrollMessagesToBottom()
        binding.etChatInput.setText("")
    }

    private fun truncatePreview(text: String): String =
        if (text.length <= 24) text else text.take(23) + "…"

    private fun scrollMessagesToBottom() {
        val count = messageAdapter.count
        if (count > 0) {
            binding.lvMessages.post { binding.lvMessages.setSelection(count - 1) }
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
