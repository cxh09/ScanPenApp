package com.cxh09.scanpenapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.cxh09.scanpenapp.databinding.ActivitySkillAboutBinding

/**
 * 技能「关于」Activity：
 * - 由三个技能 Activity（Lively / Translate / Writing）侧边栏的「关于」按钮启动
 * - 展示：技能 logo + 技能名 + 技能介绍 + 作者「cxh09」
 * - 强制横屏、全屏沉浸式（与三个技能页保持一致）
 *
 * 数据来源：intent.putExtra(EXTRA_SKILL_KEY, ...)，KEY 取值为
 *   [KEY_LIVELY] / [KEY_TRANSLATE] / [KEY_WRITING]
 * 无效 key 直接 finish，避免脏数据进来。
 */
class SkillAboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySkillAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySkillAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyFullscreen()

        val key = intent.getStringExtra(EXTRA_SKILL_KEY)
        val info = key?.let(::lookupSkill)
        if (info == null) {
            // key 缺失或非法：直接退出，避免显示空内容
            finish()
            return
        }

        binding.btnBack.setOnClickListener { finish() }
        binding.ivLogo.setImageResource(info.logoRes)
        binding.tvName.text = info.name
        binding.tvDesc.text = info.desc
        // 作者固定为 cxh09（layout 里直接引用 @string/skill_about_author）
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
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
     * 把 skill key 映射到展示数据。
     * 单一来源在代码内，避免散落在三个调用方 Activity 里。
     */
    private fun lookupSkill(key: String): SkillInfo? = when (key) {
        KEY_LIVELY -> SkillInfo(
            name = getString(R.string.ai_skill_lively_name),
            desc = getString(R.string.ai_skill_lively_desc),
            logoRes = R.drawable.ic_skill_lively,
        )
        KEY_TRANSLATE -> SkillInfo(
            name = getString(R.string.ai_skill_translate_name),
            desc = getString(R.string.ai_skill_translate_desc),
            logoRes = R.drawable.ic_skill_translate,
        )
        KEY_WRITING -> SkillInfo(
            name = getString(R.string.ai_skill_writing_name),
            desc = getString(R.string.ai_skill_writing_desc),
            logoRes = R.drawable.ic_skill_writing,
        )
        else -> null
    }

    private data class SkillInfo(
        val name: String,
        val desc: String,
        val logoRes: Int,
    )

    companion object {
        const val EXTRA_SKILL_KEY = "extra_skill_key"
        const val KEY_LIVELY = "lively"
        const val KEY_TRANSLATE = "translate"
        const val KEY_WRITING = "writing"

        fun start(context: Context, skillKey: String) {
            context.startActivity(
                Intent(context, SkillAboutActivity::class.java).apply {
                    putExtra(EXTRA_SKILL_KEY, skillKey)
                }
            )
        }
    }
}
