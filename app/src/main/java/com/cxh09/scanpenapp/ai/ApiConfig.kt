package com.cxh09.scanpenapp.ai

/**
 * OpenAI / OpenAI 兼容服务的连接配置。
 *
 * - [apiKey]       必填，Bearer Token。
 * - [baseUrl]      可空。空时使用 openai-kotlin 默认 host；
 *                  非空时（例如自建网关 / 代理）需以 "/" 结尾，openai-kotlin 会按该前缀拼接路径。
 * - [model]        必填，模型 ID（如 "gpt-4o-mini"、"deepseek-chat"）。
 * - [thinkingMode] 开关。开启时在 chat 请求中带 `reasoning_effort = "medium"`，仅对推理类模型
 *                  （o 系列 / gpt-5 系列）有效；非推理模型会忽略或拒绝该字段，
 *                  关闭时请求不携带该字段，保持与旧版完全一致。
 */
data class ApiConfig(
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val thinkingMode: Boolean = false,
) {
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank()
}
