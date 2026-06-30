package com.cxh09.scanpenapp.ai

import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.ChatRole
import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import kotlin.time.Duration.Companion.seconds

/**
 * OpenAI 客户端懒加载持有者。
 *
 * - 仅在首次调用 [get] 时构建 client，避免在 Application / 启动阶段握手。
 * - 配置变更后必须调用 [rebuild]，下次 [get] 会按新配置重建。
 * - 异常路径下若 client 尚未构建，调用方需先保证 [ApiConfig.isComplete] 为 true。
 */
class OpenAiClientHolder(private val configProvider: () -> ApiConfig) {

    @Volatile
    private var cached: Pair<ApiConfig, OpenAI>? = null

    fun rebuild() {
        cached = null
    }

    fun get(): OpenAI {
        val current = configProvider()
        require(current.isComplete) {
            "ApiConfig is incomplete: API Key and Model are required"
        }
        cached?.let { (cfg, client) ->
            if (cfg == current) return client
        }
        val fresh = buildClient(current).also { cached = current to it }
        return fresh
    }

    private fun buildClient(config: ApiConfig): OpenAI {
        val cfg = OpenAIConfig(
            token = config.apiKey.trim(),
            host = OpenAIHost(normalizeHost(config.baseUrl)),
            timeout = Timeout(socket = 60.seconds, request = 60.seconds),
        )
        return OpenAI(cfg)
    }

    private fun normalizeHost(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DEFAULT_HOST
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    /**
     * 构造一次 chat 请求（由调用方放入协程中执行）。
     * 抽到这里仅为集中管理默认参数（model / temperature），避免在 UI 层散落硬编码。
     */
    fun buildChatRequest(
        config: ApiConfig,
        history: List<ChatMessage>,
    ): ChatCompletionRequest = ChatCompletionRequest(
        model = ModelId(config.model.trim()),
        messages = history,
        temperature = 0.7,
    )

    private companion object {
        const val DEFAULT_HOST = "https://api.openai.com/v1/"
    }
}

/** 工具：把一条 user 文本包装为 [ChatMessage]。 */
fun userMessage(content: String): ChatMessage = ChatMessage(role = ChatRole.User, content = content)

/** 工具：把一条 system 文本包装为 [ChatMessage]。 */
fun systemMessage(content: String): ChatMessage = ChatMessage(role = ChatRole.System, content = content)
