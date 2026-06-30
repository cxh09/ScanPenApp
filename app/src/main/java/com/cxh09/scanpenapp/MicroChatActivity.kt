package com.cxh09.scanpenapp

import android.os.Bundle
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivityMicroChatBinding

/**
 * 微聊页面。
 *
 * 页面还在开发中：仅保留左侧联系人侧边栏入口，主区展示「页面还在开发中」提示，
 * 不提供新建会话 / 发送消息 / 表情 / 文件 / 通话等交互入口。
 */
class MicroChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMicroChatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMicroChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        setupContactList()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    // ============================ 列表 ============================

    private fun setupBackButton() {
        binding.btnBack.setOnClickListener { finish() }
    }

    private fun setupContactList() {
        val adapter = ChatContactAdapter(this)
        adapter.submit(emptyList())
        binding.lvContacts.adapter = adapter
        // 开发中：点击联系人暂不跳转
        binding.lvContacts.onItemClickListener =
            AdapterView.OnItemClickListener { _, _, _, _ -> }
        // 开发中：新建对话按钮也暂不响应
        binding.btnNewChat.setOnClickListener { /* no-op */ }
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
