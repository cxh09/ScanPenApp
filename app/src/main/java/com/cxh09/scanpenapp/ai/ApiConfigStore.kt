package com.cxh09.scanpenapp.ai

import android.content.Context

/**
 * 旧 [ApiConfig] 的薄壳代理：实际数据已迁移到 [ModelConfigStore]（多套配置），
 * 旧调用方（[OpenAiClientHolder] / 发送路径 / 旧的单条设置页）通过本类继续以
 * "单条 ApiConfig" 形态读写，**始终代表当前选中那条**模型配置。
 *
 * ### 写入
 * - [save] 会把传入的 4 字段合并到当前选中的 [ModelConfig]（保留 id / name）后回写。
 * - [clear] 不建议使用；保留以兼容旧 API，内部等价于「重置为占位」。
 *
 * ### 读取
 * - [load] 直接返回当前选中那条 [ModelConfig] 转出的 [ApiConfig]。
 *
 * 不引第三方库；底层走 [ModelConfigStore]。
 */
class ApiConfigStore(context: Context) {

    private val appContext: Context = context.applicationContext
    private val modelStore: ModelConfigStore = ModelConfigStore(context)

    fun load(): ApiConfig = modelStore.current().toApiConfig()

    fun save(config: ApiConfig) {
        modelStore.updateCurrent { it.copy(
            apiKey = config.apiKey,
            baseUrl = config.baseUrl,
            model = config.model,
            thinkingMode = config.thinkingMode,
        ) }
    }

    fun clear() {
        // 清空整张表，重新触发 loadAll() 的兜底占位逻辑
        prefs().edit()
            .remove("models_json")
            .remove("current_model_id")
            .apply()
        modelStore.loadAll()
    }

    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private companion object {
        const val PREFS_NAME = "ai_api_config"
    }
}
