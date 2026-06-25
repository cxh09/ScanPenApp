package com.cxh09.scanpenapp.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * [ApiConfig] 的轻量持久化。基于平台 [SharedPreferences]，不引第三方库。
 *
 * - API Key 写入设备本地 SharedPreferences（词典笔为个人设备，未做额外加密）。
 * - 数据读取在主线程进行（仅少量字段），写入在调用方所在线程完成。
 */
class ApiConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ApiConfig {
        val key = prefs.getString(KEY_API_KEY, "").orEmpty()
        val host = prefs.getString(KEY_BASE_URL, "").orEmpty()
        val model = prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
        return ApiConfig(apiKey = key, baseUrl = host, model = model)
    }

    fun save(config: ApiConfig) {
        prefs.edit()
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_MODEL, config.model.trim())
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "ai_api_config"
        const val KEY_API_KEY = "api_key"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val DEFAULT_MODEL = "gpt-4o-mini"
    }
}
