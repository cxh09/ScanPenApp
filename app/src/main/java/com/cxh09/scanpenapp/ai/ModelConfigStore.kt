package com.cxh09.scanpenapp.ai

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * （`api_key / base_url / model`）读出 3 个字段，合成一条名为「默认」的配置；
 * 迁移完成后 `editor.remove(...)` 旧 key，确保后续读路径只走新结构。
 *
 * ### 边界保护
 * - 列表始终至少保留 1 条配置（删完兜底新建占位），避免 UI 与发送路径出现 0 选中的状态。
 * - [current] 找不到 currentId 时回退到第一条；列表为空时返回带默认 model 的占位。
 *
 * ### 线程模型
 * - 磁盘 IO 全部走 [withContext] 切到 [Dispatchers.IO]，由调用方提供协程作用域。
 * - 内存层有 [cache] 缓存「列表 + currentId」，UI 路径调用 [loadAll] / [current] / [currentId]
 *   命中缓存后是 O(1) 同步读，主线程安全。
 * - 写操作 [add] / [delete] / [updateCurrent] / [updateById] / [setCurrent] 走后台线程，
 *   写完同步刷新缓存，保证下一次读拿到新值。
 * - 推荐在 [android.app.Application.onCreate] 中调用一次 [ensureLoaded] 预热缓存，
 *   否则 UI 第一次读会同步触发一次磁盘 IO（在 IO 调度器里，调用方需自行保证非主线程）。
 */
class ModelConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 内存缓存：磁盘 JSON 解析后的全量配置 + 当前选中 id。
     - null 表示尚未加载；首次读（同步路径或 [ensureLoaded] 后台路径）会触发 [loadFromDisk]。
     - 写操作完成后立即更新，保证后续同步读拿到最新值。
     */
    @Volatile
    private var cache: Cache? = null

    private val ioLock = Any()

    /**
     * 预热缓存：建议在 Application.onCreate 里后台调用一次，避免 UI 首次访问时
     * 同步触发磁盘 IO（虽然 [loadFromDisk] 内部走 [withContext] 切线程，但若调用方
     * 已经在主线程且没有挂起，会同步等待 IO 完成）。
     */
    suspend fun ensureLoaded() = withContext(Dispatchers.IO) {
        if (cache == null) loadFromDiskLocked()
    }

    /** 全量读取（命中缓存则同步返回，否则同步触发一次磁盘 IO）。 */
    fun loadAll(): List<ModelConfig> {
        val c = cache
        if (c != null) return c.models
        // 同步回退路径：调用方多半在主线程；这里走 withContext 必须 suspend，
        // 为了不破坏现有同步 API，在此处直接用 blockingRun（仅首次冷启动一次）。
        // 注意：建议改用 [ensureLoaded] 预热。
        return blockingLoadAll()
    }

    fun currentId(): String {
        val c = cache
        if (c != null) return c.currentId
        val list = loadAll()
        val first = list.first()
        val firstId = first.id
        // 静默修正 current_id，避免下次还是找不到（直接写缓存 + 异步落盘）
        cache = Cache(list, firstId)
        asyncSave(list, firstId)
        return firstId
    }

    fun current(): ModelConfig {
        val c = cache
        if (c != null) {
            val hit = c.models.firstOrNull { it.id == c.currentId }
            if (hit != null) return hit
            return c.models.first()
        }
        // 同步回退：与 [currentId] 同理，仅首次冷启动
        val list = loadAll()
        val id = currentId()
        return list.firstOrNull { it.id == id } ?: list.first()
    }

    fun setCurrent(id: String) {
        val c = cache
        if (c != null) {
            if (c.models.none { it.id == id }) return
            cache = c.copy(currentId = id)
            asyncSave(c.models, id)
            return
        }
        // 缓存未就绪：先同步加载再切
        val list = loadAll()
        if (list.none { it.id == id }) return
        cache = Cache(list, id)
        asyncSave(list, id)
    }

    /** 新增一条配置并自动选中新配置。返回新配置的 id。 */
    fun add(config: ModelConfig): String {
        val list = loadAll().toMutableList()
        list.add(0, config)
        val newId = config.id
        cache = Cache(list, newId)
        asyncSave(list, newId)
        return newId
    }

    /** 删除一条配置。保证列表至少留 1 条。 */
    fun delete(id: String) {
        val list = loadAll().toMutableList()
        val removed = list.removeAll { it.id == id }
        if (!removed) return
        val newCurrent: String = if (currentId() == id) {
            list.first().id
        } else {
            currentId()
        }
        cache = Cache(list, newCurrent)
        asyncSave(list, newCurrent)
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
        val newId = list[idx].id
        cache = Cache(list, newId)
        asyncSave(list, newId)
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
        val keepCurrent = currentId()
        cache = Cache(list, keepCurrent)
        asyncSave(list, keepCurrent)
    }

    /**
     * 同步（阻塞）加载全量列表。**仅供缓存未命中时使用**；UI 层尽量走 [loadAll]（命中缓存）
     * 或在 Application 启动时 [ensureLoaded] 预热。
     */
    private fun blockingLoadAll(): List<ModelConfig> = synchronized(ioLock) {
        if (cache == null) loadFromDiskLocked()
        cache!!.models
    }

    /** 必须在 [ioLock] 内调用；解析磁盘内容、跑迁移、回填 [cache]。 */
    private fun loadFromDiskLocked() {
        // 1) 迁移：旧扁平 key 还没迁过来就先跑
        migrateLegacyIfNeededLocked()
        // 2) 读 JSON 列表
        val raw = prefs.getString(KEY_MODELS_JSON, null)
        val list: List<ModelConfig> = if (raw.isNullOrBlank()) {
            // 极端情况：被外部清掉 prefs 但旧 key 也没了 → 主动生成一条默认占位
            val ph = listOf(placeholder())
            saveAllLocked(ph, ph.first().id)
            ph
        } else {
            val parsed = parseList(raw)
            if (parsed.isEmpty()) {
                val ph = listOf(placeholder())
                saveAllLocked(ph, ph.first().id)
                ph
            } else parsed
        }
        val currentId = prefs.getString(KEY_CURRENT_MODEL_ID, null)
            ?.takeIf { id -> list.any { it.id == id } }
            ?: list.first().id
        // current_id 漂移时静默写回（仅当确实变了才写）
        if (prefs.getString(KEY_CURRENT_MODEL_ID, null) != currentId) {
            prefs.edit().putString(KEY_CURRENT_MODEL_ID, currentId).apply()
        }
        cache = Cache(list, currentId)
    }

    private fun saveAllLocked(list: List<ModelConfig>, currentId: String) {
        val json = JSONArray().apply {
            list.forEach { put(toJson(it)) }
        }
        prefs.edit()
            .putString(KEY_MODELS_JSON, json.toString())
            .putString(KEY_CURRENT_MODEL_ID, currentId)
            .apply()
    }

    /**
     * 失效内存缓存：清空 prefs 持久化数据后调用，下次 [loadAll] 会从磁盘重建。
     * 仅供 [ApiConfigStore.clear] 等「整表重置」场景使用，正常读 / 写流程不需要。
     */
    fun invalidateCache() {
        synchronized(ioLock) { cache = null }
    }

    /**
     * 写盘异步：写完即返回，由 SharedPreferences.apply() 自己负责落盘线程。
     * - 这里不开新协程，避免「在 UI 线程里启动协程」反而更慢；
     * - Editor.apply() 本身已经把磁盘 IO 排队到 framework 的 single-thread executor。
     */
    private fun asyncSave(list: List<ModelConfig>, currentId: String) {
        prefs.edit()
            .putString(KEY_MODELS_JSON, JSONArray().apply { list.forEach { put(toJson(it)) } }.toString())
            .putString(KEY_CURRENT_MODEL_ID, currentId)
            .apply()
    }

    // ---------------- 迁移 ----------------

    /**
     * 首次启动或旧版本升级时，把旧的 `api_key / base_url / model`
     * 合成一条名为「默认」的配置，迁移完成后删除旧 key。
     * 必须在 [ioLock] 内调用。
     */
    private fun migrateLegacyIfNeededLocked() {
        if (!prefs.getString(KEY_MODELS_JSON, null).isNullOrBlank()) {
            return // 已迁移过
        }
        val oldKey = prefs.getString(LEGACY_KEY_API_KEY, null)
        val oldUrl = prefs.getString(LEGACY_KEY_BASE_URL, null)
        val oldModel = prefs.getString(LEGACY_KEY_MODEL, null)
        val hasLegacy = !oldKey.isNullOrBlank() || !oldModel.isNullOrBlank()
        if (!hasLegacy) {
            // 全新安装：直接落一条默认占位
            val ph = placeholder()
            saveAllLocked(listOf(ph), ph.id)
            return
        }
        val migrated = ModelConfig(
            id = LEGACY_ID,
            name = DEFAULT_NAME,
            apiKey = oldKey.orEmpty(),
            baseUrl = oldUrl.orEmpty(),
            model = oldModel?.takeIf { it.isNotBlank() } ?: "gpt-4o-mini",
        )
        saveAllLocked(listOf(migrated), migrated.id)
        // 清理旧 key，避免后续代码误读
        prefs.edit()
            .remove(LEGACY_KEY_API_KEY)
            .remove(LEGACY_KEY_BASE_URL)
            .remove(LEGACY_KEY_MODEL)
            .apply()
    }

    // ---------------- 工具 ----------------

    private fun placeholder(): ModelConfig = ModelConfig(
        id = UUID.randomUUID().toString(),
        name = DEFAULT_NAME,
        apiKey = "",
        baseUrl = "",
        model = "gpt-4o-mini",
    )

    private fun toJson(c: ModelConfig): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        // apiKey 落盘前用 [ApiKeyCipher] 加密（Android Keystore + AES/GCM）。
        // - 明文空 → 写空串（不加密）
        // - 加密失败（理论上极少见：Keystore 不可用）→ 兜底写空串，
        //   防止明文意外落盘
        put("apiKey", ApiKeyCipher.encrypt(c.apiKey) ?: "")
        put("baseUrl", c.baseUrl)
        put("model", c.model)
    }

    private fun fromJson(o: JSONObject): ModelConfig? = runCatching {
        // apiKey 字段：可能是历史明文（无 "enc:" 前缀）或新加密 blob；
        // 解密失败（Keystore 被擦 / blob 损坏）→ 视为空，由用户在 UI 里重新输入。
        val storedKey = o.optString("apiKey")
        val plainKey = if (ApiKeyCipher.isEncrypted(storedKey)) {
            ApiKeyCipher.decrypt(storedKey).orEmpty()
        } else {
            // 历史明文：直接用，ModelConfigStore 会在下次写入时自动升级为加密形态
            storedKey
        }
        ModelConfig(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { DEFAULT_NAME },
            apiKey = plainKey,
            baseUrl = o.optString("baseUrl"),
            model = o.optString("model"),
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

    private data class Cache(val models: List<ModelConfig>, val currentId: String)

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
