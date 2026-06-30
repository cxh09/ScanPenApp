package com.cxh09.scanpenapp.ai

/**
 * 单条 AI 模型配置。可由用户在 [com.cxh09.scanpenapp.ai.AiSettingsActivity] 增删改，
 * 由 [ModelConfigStore] 统一持久化。
 *
 * - [id] 全局唯一（UUID 字符串），切换/删除/编辑都按 id 寻址。
 * - [name] 用户可改的别名（如「GPT-4o」「DeepSeek」），仅用于 UI 展示。
 * - [apiKey] / [baseUrl] / [model] 与 [ApiConfig] 3 字段一一对应，
 *   通过 [toApiConfig] 转成旧接口继续给 [OpenAiClientHolder] 使用，避免改动发送路径。
 */
data class ModelConfig(
    val id: String,
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
) {
    val isComplete: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank()

    /** 转为旧的 [ApiConfig] 结构，供 [OpenAiClientHolder] / 发送路径使用。 */
    fun toApiConfig(): ApiConfig = ApiConfig(
        apiKey = apiKey,
        baseUrl = baseUrl,
        model = model,
    )
}
