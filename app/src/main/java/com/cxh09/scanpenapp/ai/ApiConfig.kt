package com.cxh09.scanpenapp.ai

/**
 * OpenAI / OpenAI 兼容服务的连接配置。
 *
 * - [apiKey]    必填，Bearer Token。
 * - [baseUrl]   可空。空时使用 openai-kotlin 默认 host；
 *               非空时（例如自建网关 / 代理）需以 "/" 结尾，openai-kotlin 会按该前缀拼接路径。
 * - [model]     必填，模型 ID（如 "gpt-4o-mini"、"deepseek-chat"）。
 */
data class ApiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
) {
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank()
}
