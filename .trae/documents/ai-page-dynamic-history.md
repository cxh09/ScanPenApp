# AI 页动态历史 Plan

## Summary

移除 `AskAiActivity` 中由 `buildSampleHistory()` 捏造的 8 条静态历史对话，改为运行期由用户**发出第一条消息时**创建新历史项、标题为当前时间（`HH:mm`）。沿用微聊页同样的「数据可变 + 时间标题」思路，保持两类页面的交互一致性。

## Current State Analysis

- [AskAiActivity.kt](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt)
  - L69-78 `setupHistoryList()` 在初始化时调用 `historyAdapter.submit(buildSampleHistory())` 注入 8 条假数据。
  - L127-133 `startNewChat()` 只调用 `sessionHistory.clear()` + 清空 `llMessages` + 显示 `tvGreeting`，没有向历史列表追加任何条目。
  - L135-139 `onHistoryClicked()` 是占位实现（把标题塞回输入框），本次不改动。
  - L283-302 `buildSampleHistory()` 是要删除的源头。
- [strings.xml](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/res/values/strings.xml) L20-26 存放 8 条 `ai_chat_*` 占位字符串，仅供 `buildSampleHistory()` 消费，删除后不会影响其它模块。
- [ChatHistoryItem.kt](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/ChatHistoryItem.kt) 已经是 `data class`，无需修改。
- [ChatHistoryAdapter.kt](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/ChatHistoryAdapter.kt) 通过 `submit(items)` 注入数据，可直接复用。
- [activity_ask_ai.xml](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/res/layout/activity_ask_ai.xml) 布局无需改动（`lvHistory` 列表为空时自动不渲染条目即可）。

## Proposed Changes

### 1. `AskAiActivity.kt` — 把数据源从静态切换为可变结构

新增字段：

```kotlin
private val history: MutableList<ChatHistoryItem> = mutableListOf()
private var nextHistoryId: Long = 1L
private var currentSessionRecorded: Boolean = false
private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
```

具体改动：

1. 顶部 `import` 区域补 `java.text.SimpleDateFormat`、`java.util.Date`、`java.util.Locale`。
2. `setupHistoryList()` 末尾改为 `historyAdapter.submit(history)`，删除对 `buildSampleHistory()` 的调用。
3. `startNewChat()` 在末尾追加 `currentSessionRecorded = false`，确保下一次发消息会创建新历史项。
4. `onSendClicked()` 在现有「消息合法性 + config 校验」通过后、`appendUserMessage(text)` 之前判断：
   - 若 `!currentSessionRecorded`：用 `timeFormatter.format(Date())` 生成标题 → `history.add(ChatHistoryItem(id = nextHistoryId++, title = now))` → `historyAdapter.submit(history)` → `currentSessionRecorded = true`。
5. 删除 `buildSampleHistory()` 方法整段。
6. 保留 `onHistoryClicked()` 的占位实现不动（用户未要求改），避免在本次计划中扩大范围。

### 2. `strings.xml` — 清理占位字符串

删除以下 8 条（与 `buildSampleHistory()` 一一对应，删后无任何其它引用）：

- `ai_chat_main`
- `ai_chat_breakup`
- `ai_chat_flutter`
- `ai_chat_scanpen`
- `ai_chat_opencode`
- `ai_chat_zip`
- `ai_chat_pwd`
- `ai_chat_electron`

保留 `ai_chat_main` 同名以外的字符串：`ai_new_chat`、`ai_history_title`、`ai_greeting`、`ai_input_hint` 等功能性文案。

### 3. 不改动的文件

- `ChatHistoryItem.kt`、`ChatHistoryAdapter.kt`、布局文件 — 数据结构与渲染层已支持可变数据源。
- `AndroidManifest.xml` — 不需要新增权限或调整屏幕方向（已 `landscape`）。
- 不新增任何依赖。

## Assumptions & Decisions

1. **添加时机 = 第一次发消息**：按用户在「添加时机」问题中选择的方案。点击「新对话」按钮只清空当前区域，**不**在历史列表产生空条目；只有真的开始聊天才生成一条 `HH:mm` 的历史项。
2. **标题格式 = `HH:mm`**：与微聊页 `MicroChatActivity.timeFormatter`（`HH:mm`）保持一致，统一体感；不在本次引入日期前缀，避免越界改动。若后续需要跨天区分，再追加 `MM/dd HH:mm`。
3. **`isStarred` 字段维持 `false`**：本次不引入收藏交互，所有新建条目默认未收藏。
4. **历史数据不持久化**：当前微聊页同样不持久化（仅内存态）。本次保持一致，避免引入 SharedPreferences / Room 改动。Activity 销毁后历史会清空。
5. **不新增空状态提示**：「历史列表为空」在 ListView 上是自然空态（什么都不渲染），当前 `tvGreeting` 已提供右侧引导，不强加额外 UI。
6. **范围最小化**：`onHistoryClicked()`、收藏星标、点击历史项加载会话内容等都不在本次计划中。

## Verification

1. 编译：`./gradlew :app:assembleDebug`（应通过，无未使用资源警告）。
2. 启动 → 进入「问 AI」页：
   - 左侧历史区域应**为空**（不出现 8 条假条目）。
3. 直接发一条消息（不点「新对话」）：
   - 右侧出现该消息；
   - 左侧历史列表末尾追加一项，标题为发送时刻的 `HH:mm`（如 `14:32`）。
4. 点「新对话」再发一条消息：
   - 右侧重置后再现新消息；
   - 左侧历史列表再追加一项，新标题为新的 `HH:mm`。
5. 点「新对话」后**不**发消息、直接返回：
   - 左侧历史列表**不应**出现新的空条目。
6. 连续发多条消息（同一会话内）：
   - 左侧历史列表**只**有一条对应项（不会为每条消息都追加）。
7. 检查 `R.string` 中 `ai_chat_*` 占位资源已全部删除（Android Studio 搜不到引用）。
8. 横屏运行无白屏 / 闪烁；ListView 滚动不卡顿（数据量小，未触发性能问题）。

## Files Touched

| 路径 | 变更 |
| --- | --- |
| `app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt` | 删除 `buildSampleHistory()`；新增 `history` / `nextHistoryId` / `currentSessionRecorded` / `timeFormatter`；改造 `setupHistoryList` / `startNewChat` / `onSendClicked` |
| `app/src/main/res/values/strings.xml` | 删除 8 条 `ai_chat_*` 占位字符串 |
