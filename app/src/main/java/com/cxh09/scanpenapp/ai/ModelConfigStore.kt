package com.cxh09.scanpenapp.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 多套 AI 模型配置的持久化。复用 [ApiConfigStore] 同一份 SharedPreferences
 * （"ai_api_config"），通过两个新 key 维护：
 *
 * - `models_json`    —— JSON 数组，元素为 [ModelConfig] 全字段。
 * - `current_model_id` —— 当前选中配置的 [ModelConfig.id]。
 *
 * ### 旧数据迁移
 * 首次启动时若 `models_json` 不存在，会从旧的扁平 key
 * （`api_key / base_url / model / thinking_mode`）读出 4 个字段，合成一条名为「默认」的配置；
 * 迁移完成后 `editor.remove(...)` 旧 key，确保后续读路径只走新结构。
 *
 * ### 边界保护
 * - 列表始终至少保留 1 条配置（删完兜底新建占位），避免 UI 与发送路径出现 0 选中的状态。
 * - [current] 找不到 currentId 时回退到第一条；列表为空时返回带默认 model 的占位。
 */
class ModelConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // 触发一次迁移：旧扁平 key → 新 JSON 结构
        migrateLegacyIfNeeded()
    }

    /** 全量读取（含迁移兜底），保证返回非空。 */
    fun loadAll(): List<ModelConfig> {
        val raw = prefs.getString(KEY_MODELS_JSON, null)
        if (raw.isNullOrBlank()) {
            // 极端情况：被外部清掉 prefs 但旧 key 也没了 → 主动生成一条默认占位
            return listOf(placeholder()).also { saveAll(it, it.first().id) }
        }
        val list = parseList(raw)
        if (list.isEmpty()) {
            return listOf(placeholder()).also { saveAll(it, it.first().id) }
        }
        return list
    }

    fun currentId(): String {
        val id = prefs.getString(KEY_CURRENT_MODEL_ID, null)
        if (!id.isNullOrBlank() && loadAll().any { it.id == id }) return id
        // id 找不到 / 为空 → 回退到第一条
        val first = loadAll().first()
        // 静默修正 current_id，避免下次还是找不到
        prefs.edit().putString(KEY_CURRENT_MODEL_ID, first.id).apply()
        return first.id
    }

    fun current(): ModelConfig {
        val list = loadAll()
        val id = prefs.getString(KEY_CURRENT_MODEL_ID, null)
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun setCurrent(id: String) {
        val list = loadAll()
        if (list.none { it.id == id }) return
        prefs.edit().putString(KEY_CURRENT_MODEL_ID, id).apply()
    }

    /** 新增一条配置并自动选中新配置。返回新配置的 id。 */
    fun add(config: ModelConfig): String {
        val list = loadAll().toMutableList()
        list.add(0, config)
        saveAll(list, config.id)
        return config.id
    }

    /** 删除一条配置。保证列表至少留 1 条。 */
    fun delete(id: String) {
        val list = loadAll().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (!removed) return
        if (list.isEmpty()) {
            // 兜底：补一条占位
            val ph = placeholder()
            list.add(ph)
            saveAll(list, ph.id)
        } else {
            val newCurrent = if (currentId() == id) list.first().id else currentId()
            saveAll(list, newCurrent)
        }
    }

    /**
     * 把 [block] 作用于当前选中配置后回写全量列表。
     * 当前选中找不到时静默跳过（理论上不会发生，因为 [loadAll] 保证非空）。
     */
    fun updateCurrent(block: (ModelConfig) -> ModelConfig) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == currentId() }
        if (idx < 0) return
        list[idx] = block(list[idx])
        saveAll(list, list[idx].id)
    }

    /**
     * 把 [block] 作用于指定 id 的配置后回写全量列表。
     * 找不到该 id 时静默跳过。
     */
    fun updateById(id: String, block: (ModelConfig) -> ModelConfig) {
        val list = loadAll().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = block(list[idx])
        saveAll(list, currentId())
    }

    private fun saveAll(list: List<ModelConfig>, currentId: String) {
        val json = JSONArray().apply {
            list.forEach { put(toJson(it)) }
        }
        prefs.edit()
            .putString(KEY_MODELS_JSON, json.toString())
            .putString(KEY_CURRENT_MODEL_ID, currentId)
            .apply()
    }

    // ---------------- 迁移 ----------------

    /**
     * 首次启动或旧版本升级时，把旧的 `api_key / base_url / model / thinking_mode`
     * 合成一条名为「默认」的配置，迁移完成后删除旧 key。
     */
    private fun migrateLegacyIfNeeded() {
        if (!prefs.getString(KEY_MODELS_JSON, null).isNullOrBlank()) {
            return // 已迁移过
        }
        val oldKey = prefs.getString(LEGACY_KEY_API_KEY, null)
        val oldUrl = prefs.getString(LEGACY_KEY_BASE_URL, null)
        val oldModel = prefs.getString(LEGACY_KEY_MODEL, null)
        val oldThinking = prefs.getBoolean(LEGACY_KEY_THINKING_MODE, false)
        val hasLegacy = !oldKey.isNullOrBlank() || !oldModel.isNullOrBlank()
        if (!hasLegacy) {
            // 全新安装：直接落一条默认占位
            val ph = placeholder()
            saveAll(listOf(ph), ph.id)
            return
        }
        val migrated = ModelConfig(
            id = LEGACY_ID,
            name = DEFAULT_NAME,
            apiKey = oldKey.orEmpty(),
            baseUrl = oldUrl.orEmpty(),
            model = oldModel?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini",
            thinkingMode = oldThinking,
        )
        saveAll(listOf(migrated), migrated.id)
        // 清理旧 key，避免后续代码误读
        prefs.edit()
            .remove(LEGACY_KEY_API_KEY)
            .remove(LEGACY_KEY_BASE_URL)
            .remove(LEGACY_KEY_MODEL)
            .remove(LEGACY_KEY_THINKING_MODE)
            .apply()
    }

    // ---------------- 工具 ----------------

    private fun placeholder(): ModelConfig = ModelConfig(
        id = UUID.randomUUID().toString(),
        name = DEFAULT_NAME,
        apiKey = "",
        baseUrl = "",
        model = "gpt-4o-mini",
        thinkingMode = false,
    )

    private fun toJson(c: ModelConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("apiKey", c.apiKey)
        put("baseUrl", c.baseUrl)
        put("model", c.model)
        put("thinkingMode", c.thinkingMode)
    }

    private fun fromJson(o: JSONObject): ModelConfig? = runCatching {
        ModelConfig(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { DEFAULT_NAME },
            apiKey = o.optString("apiKey"),
            baseUrl = o.optString("baseUrl"),
            model = o.optString("model"),
            thinkingMode = o.optBoolean("thinkingMode", false),
        )
    }.getOrNull()

    private fun parseList(raw: String): List<ModelConfig> {
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ModelConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            fromJson(o)?.let(out::add)
        }
        return out
    }

    private companion object {
        const val PREFS_NAME = "ai_api_config"
        const val KEY_MODELS_JSON = "models_json"
        const val KEY_CURRENT_MODEL_ID = "current_model_id"

        // 旧 key：迁移完成后会被删除
        const val LEGACY_KEY_API_KEY = "api_key"
        const val LEGACY_KEY_BASE_URL = "base_url"
        const val LEGACY_KEY_MODEL = "model"
        const val LEGACY_KEY_THINKING_MODE = "thinking_mode"
        const val LEGACY_ID = "legacy-default"

        const val DEFAULT_NAME = "默认"
    }
}
