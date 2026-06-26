# AI 设置页改造为多模型配置 + 问 AI 模型选择

> 状态：待执行（用户已确认三项关键决策）
> 关联文件：AI 设置（`AiSettingsActivity` / `activity_ai_settings.xml` / `ApiConfigStore` / `ApiConfig`）+ 问 AI 输入栏（`activity_ask_ai.xml` / `AskAiActivity`）+ 主设置页（`SettingsActivity` / `SettingItem` / `SettingType` / `SettingsAdapter` / `item_setting.xml`）

## 用户确认的关键决策

1. **多套完整配置**：每条配置独立包含 `name / apiKey / baseUrl / model / thinkingMode`，可增删改、命名（如「GPT-4o」「DeepSeek」）。
2. **保留两个入口**：主设置页 + 问 AI 左下角，都进同一个新页面。
3. **侧边抽屉**：问 AI 输入框的模型选择按钮点击后从屏幕右侧滑出抽屉展示所有已配置模型（用户主动选择，词典笔横屏窄长比例可接受）。

## 目标产物

1. 主设置页多一个「AI 服务」项 → 点击进入新 `AiSettingsActivity`。
2. 新 `AiSettingsActivity` 布局：左 ~220dp 列表（每个配置一行，显示名称 + 选中态高亮 + 长按删除/「+ 新建」底部入口），右 0dp 主区（顶部栏 + 选中配置的所有字段 + 预设行 + 测试按钮）。复用 `bg_sidebar` / `bg_history_normal` / `bg_history_selected` / `bg_ai_settings_field` 等已有资源。
3. 问 AI 页面左下角「API 设置」入口保留，文案改为「AI 服务」，点击进入新页面。
4. 问 AI 输入框「思考」按钮右侧加一个「模型选择按钮」（`btnModel`），显示当前模型名缩写；点击从屏幕右侧滑出抽屉（`DrawerLayout` 右侧 `end`），列出所有已配置模型 + 当前选中项打勾，点击切换并落库。
5. 数据模型：多套配置 + 一个"当前选中 id"统一持久化在 `SharedPreferences("ai_api_config")` 里（单 JSON 字符串，键 `models_json` / `current_model_id`）。首启动迁移旧的扁平 key（`api_key / base_url / model / thinking_mode`）→ 一条名为"默认"的配置；迁移完成后删除旧 key。

## 当前状态分析（Phase 1 探索结论）

### 已有可复用

- `bg_sidebar.xml` / `bg_history_normal.xml` / `bg_history_selected.xml`：可作为新设置页左栏背景与列表项背景。
- `bg_ai_settings_field.xml`：可作为新右侧输入框背景。
- `bg_btn_new_chat.xml`：可作为新左栏底部「+ 新建」按钮背景。
- `ChatHistoryAdapter` / `ChatHistoryItem`：可作为新左栏列表的"形状"参考（独立 Adapter，因为 item 布局不同）。
- `ai_chat_bg / ai_sidebar_bg / ai_text_primary / ai_text_secondary / ai_text_tertiary / ai_sidebar_divider / ai_input_focus_border / ai_status_warn` 等颜色：与新页面风格统一。
- `ic_key` / `ic_plus` / `ic_chevron_right` / `ic_search` / `ic_settings`：可作为新页面图标。

### 现有问题（必须一并修复）

1. **`activity_ask_ai.xml` 中 `tvSettingsStatus` 已被删除**，但 `AskAiActivity.kt` 中：
   - 行 129 `setupSettingsRow()` 后仍有 `updateSettingsStatus()` 调用
   - 行 283 `onResume()` 中仍有 `updateSettingsStatus()` 调用
   - 行 287 仍有 `private fun updateSettingsStatus() { binding.tvSettingsStatus.text = ... }` 函数定义
   - 现状：**编译会失败**（`tvSettingsStatus` 找不到）。
   - 处理：在本次改造的"数据层重构"步骤里彻底删除 `updateSettingsStatus()` 函数及其两处调用，统一改为「`loadAndApplyModels()` 读多套配置 + 刷新抽屉 UI」。
2. `activity_ai_settings.xml` 是单条表单，改造后整张布局文件基本推翻重写。

### 数据层现状

- `ApiConfig` 仍是单条 4 字段结构。需扩展为 `ModelConfig(id, name, apiKey, baseUrl, model, thinkingMode)` + `ModelConfigStore(list, currentId)`。
- 旧的 `ApiConfigStore.load() / save()` 是所有调用方的契约（`AskAiActivity`、`AiSettingsActivity`、`OpenAiClientHolder` lambda）。保留 `ApiConfig` 作为内部模型（每条 `ModelConfig` 仍含此 4 字段），新增 `ModelConfigStore`，**同时保留 `ApiConfigStore.current()` 返回"当前选中"那一条**，避免动 `OpenAiClientHolder` 与 `AskAiActivity.onSendClicked` 的核心路径（最小侵入原则）。
- `OpenAiClientHolder` 缓存键仍是整 `ApiConfig ==`。在多配置 + 切换模型时，`ApiConfig.current()` 内容（key/url/model）会变，`cached.first == current` 自然不命中 → 自动重建。无需改 `OpenAiClientHolder`。
- `toggleThinkingMode` 持久化 `configStore.save(current.copy(thinkingMode = ...))` 的写法需调整：切换到 `modelConfigStore.updateCurrent { it.copy(thinkingMode = ...) }`。

## 实施步骤

> 注：所有步骤前先跑 `git status` 确认无残留改动；本计划内的任何步骤都不动 `MainActivity.kt` / 词典笔硬件相关代码。

### Step 1 — 新建数据模型与持久化

**新增文件：**
- `app/src/main/java/com/cxh09/scanpenapp/ai/ModelConfig.kt`
  ```kotlin
  data class ModelConfig(
      val id: String,                  // UUID 字符串
      val name: String,                // 用户可改的别名，默认 "GPT-4o" / "DeepSeek" / ...
      val apiKey: String,
      val baseUrl: String,
      val model: String,
      val thinkingMode: Boolean = false,
  ) {
      val isComplete: Boolean get() = apiKey.isNotBlank() && model.isNotBlank()
  }
  ```
- `app/src/main/java/com/cxh09/scanpenapp/ai/ModelConfigStore.kt`
  - 内部 `SharedPreferences("ai_api_config")` 不变。
  - 读：键 `models_json`（JSON 数组字符串）→ `List<ModelConfig>`；`current_model_id`（String）→ 当前选中 id；首次启动若 `models_json` 不存在，**从旧的 `api_key / base_url / model / thinking_mode` 迁移成一条 id = `legacy-default`、name = "默认" 的配置**；迁移完 `editor.remove(...)` 旧 key。
  - 写：`saveAll(list: List<ModelConfig>, currentId: String)` 一次写两个 key。
  - 暴露 `current(): ModelConfig`（找不到时返回第一条；列表空时返回带默认值的占位）保证调用方不崩。
  - `updateCurrent(block: (ModelConfig) -> ModelConfig)`：找到当前 id 跑 block 后回写全量 list（最多 10 条左右，全量回写成本可忽略）。
  - `add(config: ModelConfig)` / `delete(id: String)` / `setCurrent(id: String)`。
  - 序列化用手写 `org.json.JSONArray / JSONObject`（项目已有依赖、不引 Gson/Moshi）。

**保留 `ApiConfig.kt` + `ApiConfigStore.kt`**：继续给 `OpenAiClientHolder` / 旧调用方用，**但删除其写入入口**（`save()` / `clear()` 改为 `@Deprecated` 但仍可写）—— 实际上更稳妥的方案是让 `ApiConfigStore` 改为薄壳：`load()` 委托给 `ModelConfigStore.current().toApiConfig()`，旧 `save()` 改为转发到 `ModelConfigStore.updateCurrent`。这样改动面最小。

### Step 2 — 字符串资源

**修改 `app/src/main/res/values/strings.xml`：**
- 新增：
  - `ai_settings_models_title` = "AI 服务"
  - `ai_settings_add_model` = "+ 新建配置"
  - `ai_settings_model_default_name` = "默认"
  - `ai_settings_model_untitled` = "未命名"
  - `ai_settings_model_delete_title` = "删除配置"
  - `ai_settings_model_delete_message` = "确定要删除「%1$s」吗？"
  - `ai_settings_model_delete_confirm` = "删除"
  - `ai_settings_model_delete_cancel` = "取消"
  - `ai_settings_model_unconfigured` = "（未配置）"
  - `ai_model_select_title` = "选择模型"
  - `ai_model_select_empty` = "还没有配置任何模型，请前往 AI 服务"
  - `ai_model_select_goto_settings` = "去设置"
- 保留 `ai_settings_entry`（= "API 设置"），不动。
- 删除/保留待定：`ai_settings_status_unset` / `ai_settings_status_set`（已不再使用，可删，但删字符串风险低，可一并清掉）。

### Step 3 — 重建 `activity_ai_settings.xml`（左右分栏）

**文件**：`app/src/main/res/layout/activity_ai_settings.xml`

- 根 `ConstraintLayout`，背景 `@color/ai_chat_bg`。
- **左栏 `sidebar`**：宽 220dp，背景 `@drawable/bg_sidebar`，含：
  - 顶部栏（48dp 高，左侧「← 返回」`btnBack`，中间标题 `AI 服务`）。
  - 「+ 新建配置」按钮 `btnAddModel`（复用 `bg_btn_new_chat` 风格，居中文字 + 加号，48dp 高）。
  - 分隔线 1dp。
  - `BounceListView`（新建 `com.cxh09.scanpenapp.ui.BounceListView`）id `lvModels` —— 等等，项目里目前**没有**这个类（Phase 1 探索确认）。先用 `ListView`，id 仍叫 `lvModels`，**留 TODO**：后续如果 Bounce 库就绪再替换。`overScrollMode="ifContentScrolls"`。
  - 列表项布局新建 `item_model_config.xml`（48dp 高，左侧 name TextView + 右侧未配置时灰色「（未配置）」标记 + 选中态用 `bg_history_selected`）。
- **中间分隔线 `sidebarDivider`**：1dp 宽。
- **右栏 `mainArea`**：0dp，含：
  - 顶部栏（48dp 高，左 `btnBack2` 显示当前选中配置名；中间标题 `编辑配置`；右侧 `btnSave`）。
  - `ScrollView`，内嵌垂直 `LinearLayout`：
    - 名称输入 `etName`（`imeOptions=actionNext`，`inputType=text`，`maxLines=1`）。
    - API Key 输入 `etApiKey`（同原样式）。
    - Base URL 输入 `etBaseUrl` + 预设行（`btnPresetDeepseek / btnPresetOpencode / btnPresetGlm`，与原页面一致）。
    - Model 输入 `etModel`。
    - 测试行 `testRow`（`btnTest` + `tvTestStatus`，与原页面一致）。
  - 底部「删除配置」按钮 `btnDelete`（仅在非默认时显示，默认配置可保留但禁止删除；或者无配置时整张右栏显示空态）。

**新建 `item_model_config.xml`**：48dp 高，左 name（14sp 粗体）+ 副标 model（12sp 灰色，可选），右侧选中态用 selector 背景。

**新建 `ModelConfigAdapter`**：参考 `ChatHistoryAdapter` 的结构；提供 `submit(list: List<ModelConfig>)` 与 `setSelected(id: String?)`。

### Step 4 — 改写 `AiSettingsActivity`

**文件**：`app/src/main/java/com/cxh09/scanpenapp/ai/AiSettingsActivity.kt`

- 删除旧逻辑：`loadConfig()` / `validateAndSave()` / `applyScannedJson()` / `runTest()` 中对单条表单的直接引用。
- 改用 `ModelConfigStore`：
  - 初始化时 `loadAll()` → 列表为空时 `add(ModelConfig(...默认, "默认", "gpt-4o-mini"))` 并 `setCurrent`。
  - 选中项变更 → 渲染右栏；右栏 `etName / etApiKey / etBaseUrl / etModel / thinkingMode` 同步填充；改任意字段 → 不立即写 store，右栏顶部 `btnSave` 触发 `validateAndSave()`：校验非空 → `updateCurrent { it.copy(...) }` → `finish()` 不再调用，保持在页内可继续切换。
  - `btnAddModel` → `add(ModelConfig(name="配置 N", model="gpt-4o-mini"))` + 自动 `setCurrent`。
  - `btnDelete` → 弹 `AlertDialog` 确认 → `delete(currentId)`；删完列表至少留 1 条（兜底新建一条占位）。
  - `btnTest` → 用当前右栏字段构造临时 `ModelConfig`，走 `OpenAiClientHolder` 的 `get()` + `models()`，逻辑沿用旧 `runTest`。
  - 扫一扫 / 分享：扫到 JSON 后 `applyScannedJson(raw)` → 写入当前选中配置的 apiKey/baseUrl/model（不弹新配置），UI 立刻更新输入框 + 顶部提示。
- 移除 `applyFullscreen` 改动（保持与现有其他页面一致的 `setDecorFitsSystemWindows(false)` 即可）。

### Step 5 — 问 AI 页面加「模型选择按钮」+ 右侧抽屉

**修改 `app/src/main/res/layout/activity_ask_ai.xml`：**
- 根 `ConstraintLayout` 包一层 `androidx.drawerlayout.widget.DrawerLayout`（id `drawerRoot`），把现有根 `ConstraintLayout` 作为 `content`，新增 `LinearLayout`(id `drawerModels`) 作为 `layout_gravity="end"` 的抽屉：
  - 抽屉宽 280dp，背景 `@color/ai_sidebar_bg`。
  - 顶部 48dp 标题栏：「选择模型」+ 右上角 `X`（`btnCloseDrawer`）关闭。
  - 列表 `ListView`（id `lvModelsInDrawer`）展示所有配置：每项 `item_model_drawer.xml`（左侧 name + model 副标，右侧选中态用 `bg_history_selected` 背景 + 末尾打勾 `ImageView`，未配置时灰色「（未配置）」提示）。
  - 底部 56dp 一行 `LinearLayout`：`btnGotoSettings`（文字「前往 AI 服务」+ `ic_key` 图标，全宽背景 `@color/ai_input_bg`）。
- 现有 `inputBar` 中 `btnQuick` 右侧加一个 `btnModel`（同 `btnQuick` 风格，32dp 高，背景 `bg_ai_thinking_toggle`）：
  - 文字显示当前 `ModelConfig.name`（如 "GPT-4o"），无配置时显示 "选择模型"。
  - `drawableStart` 用 `ic_bulb` 复用即可（保持视觉一致），或者新建一个 `ic_model`（小方块+小圆圈）—— 优先用 `ic_bulb` 减少新增资源；若用户后续反馈再换。

**修改 `app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt`：**
- 删除 `updateSettingsStatus()` 函数定义、行 129 / 行 283 两处调用（修编译错误）。
- 新增 `private lateinit var modelStore: ModelConfigStore`。
- 新增 `private var modelDrawerAdapter: ModelConfigAdapter`。
- `onCreate` 中：
  - `modelStore = ModelConfigStore(this)`（迁移在 store 构造里完成）。
  - `setupModelDrawer()`：初始化抽屉列表、点击项 → `modelStore.setCurrent(id)` → `drawerRoot.closeDrawer(GravityCompat.END)` → `applyModelToInputBar()` → `clientHolder.rebuild()`。
  - 抽屉里 `btnGotoSettings` 点击 → `startActivity(Intent(this, AiSettingsActivity::class.java))`。
  - `btnModel.setOnClickListener { drawerRoot.openDrawer(GravityCompat.END) }`。
  - `btnCloseDrawer.setOnClickListener { drawerRoot.closeDrawer(GravityCompat.END) }`。
- `onResume` 中新增 `loadModelsIntoDrawer()`（用户从设置页返回后模型列表可能已变），并 `applyModelToInputBar()` 把 `btnModel` 文字刷新成最新 `modelStore.current().name`。
- `toggleThinkingMode()` 中持久化路径改为 `modelStore.updateCurrent { it.copy(thinkingMode = enabled) }`。
- 发送路径（`onSendClicked`）的 `val config: ApiConfig = configStore.load()` 改为 `val config: ApiConfig = modelStore.current().toApiConfig()`（`ModelConfig` 提供 `toApiConfig()` 扩展）。`isComplete` 校验沿用。
- 启动时若 `modelStore.current().isComplete == false`，仍走 `openSettings()` 弹设置页（兜底）。

### Step 6 — 主设置页加「AI 服务」项

**修改 `app/src/main/java/com/cxh09/scanpenapp/SettingItem.kt`：**
- `SettingType` 枚举新增 `AI_SERVICE`。

**修改 `app/src/main/java/com/cxh09\scanpenapp\SettingsActivity.kt`：**
- 在 `items` 列表中 `ABOUT` 之前插入一项：
  ```kotlin
  SettingItem(R.drawable.ic_key, getString(R.string.ai_settings_models_title), SettingType.AI_SERVICE)
  ```
- `onItemClick` 分发：`SettingType.AI_SERVICE -> startActivity(Intent(this, AiSettingsActivity::class.java))`。

**修改 `app/src/main/res/values/strings.xml`：** 已新增 `ai_settings_models_title = "AI 服务"`，无需再改。

### Step 7 — 问 AI 左下角入口

**修改 `app/src/main/res/values/strings.xml`：** `ai_settings_entry` 保持 `"API 设置"` 不变（用户上一轮已确认），无需再改。

**修改 `app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt`：** `openSettings()` 已存在，无需改；只是触发的 Activity 现在是新版左右分栏。

### Step 8 — 验证

- `gradlew :app:assembleDebug` 跑通，无 `tvSettingsStatus` / `updateSettingsStatus` 残留编译错误。
- 模拟首次启动：清空 `SharedPreferences("ai_api_config")` → 启动问 AI → 抽屉 / 设置页左栏均出现一条「默认」配置。
- 模拟旧数据：写入 `api_key=xxx / model=deepseek-chat` → 启动问 AI → 抽屉出现「默认」配置，name="默认"、model="deepseek-chat"、key="xxx"；旧 key 被清掉。
- 问 AI：切到「DeepSeek」配置 → 点发送 → `OpenAiClientHolder` 命中重建，URL/Key/Model 与新配置一致。
- 抽屉：列表空时显示「还没有配置任何模型，请前往 AI 服务」+ 「去设置」按钮。
- 横屏窄长比例设备：抽屉不超 280dp，左栏 220dp 不挤压右侧输入栏。

## 假设与决策

- 多套配置上限不设硬限制（用户量小），UI 列表自带滚动。
- "默认"配置不可删除（避免出现 0 配置的边界态），其余可删；如需全部可删则在 `delete` 兜底新建一条占位。
- 思考模式跟随当前选中的配置（不全局共享），符合用户语义。
- 扫一扫 / 分享的 JSON 协议不变（`{key,url,model}`），落到当前选中配置。
- 不引入新依赖：`DrawerLayout` 来自 `androidx.drawerlayout`（项目已用），JSON 解析用 `org.json`（项目已用）。
- 横屏窄长屏幕下：抽屉宽 280dp + 列表项 48dp + 顶部 48dp = 不超 376dp，左栏 220dp + 抽屉 280dp 同时显示在某些横屏比例（≥1024dp 宽）会拥挤；当前词典笔典型 1280×400 左右，所以**抽屉采用 overlay 模式（默认只露主区，用户点 `btnModel` 才滑出）**，`layout_gravity="end"` + `drawerRoot` 用 `lockMode="locked"` 仅在按按钮时打开，避免常驻占位。

## 不在本次范围

- 多套配置的导入/导出（继续走扫一扫 / 分享，但只针对当前选中那条）。
- 模型自动发现（`OpenAiClientHolder.buildChatRequest` 不变，只读 `config.model` 字符串）。
- 历史对话列表的合并 / 迁移。
- 「默认」配置以外的预设模板（DeepSeek / OpenCode / GLM 按钮在右侧保留为"快速填值"，不直接创建新配置条目）。

## 涉及文件清单

新增：
- `app/src/main/java/com/cxh09/scanpenapp/ai/ModelConfig.kt`
- `app/src/main/java/com/cxh09/scanpenapp/ai/ModelConfigStore.kt`
- `app/src/main/java/com/cxh09/scanpenapp/ai/ModelConfigAdapter.kt`
- `app/src/main/res/layout/item_model_config.xml`
- `app/src/main/res/layout/item_model_drawer.xml`

修改：
- `app/src/main/res/layout/activity_ai_settings.xml`（重写）
- `app/src/main/java/com/cxh09/scanpenapp/ai/AiSettingsActivity.kt`（重写）
- `app/src/main/java/com/cxh09/scanpenapp/ai/ApiConfigStore.kt`（薄壳化，可选）
- `app/src/main/res/layout/activity_ask_ai.xml`（加 `DrawerLayout` + `btnModel` + 抽屉布局）
- `app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt`（删 `updateSettingsStatus`、接 `ModelConfigStore`、初始化抽屉）
- `app/src/main/java/com/cxh09/scanpenapp/SettingItem.kt`（加 `AI_SERVICE`）
- `app/src/main/java/com/cxh09/scanpenapp/SettingsActivity.kt`（加分发）
- `app/src/main/res/values/strings.xml`（新增若干 key）
- `app/build.gradle.kts`（可能加 `androidx.drawerlayout:drawerlayout` 依赖；需先确认是否已声明）
