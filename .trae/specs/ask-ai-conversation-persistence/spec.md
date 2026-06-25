# 问AI对话持久化 Spec

## Why
当前问AI页面（`AskAiActivity`）的所有对话数据仅保存在内存中（`sessionHistory`、`history`），退出页面或应用后所有聊天记录丢失。左侧历史列表只是当次会话的占位，点击历史条目也只是把标题填回输入框（占位逻辑）。用户需要对话能本地持久化，随时打开历史对话继续聊天。

## What Changes
- 新增 `Conversation` 数据类，包含会话 ID、标题、创建时间、消息列表（role + content）
- 新增 `ConversationStore`，使用 JSON 文件在 `filesDir/conversations/` 下持久化每个会话
- 修改 `ChatHistoryItem`，增加 `createdAt` 字段用于排序
- 修改 `AskAiActivity`：
  - 启动时从 `ConversationStore` 加载历史会话列表
  - 每次 AI 回复完成后自动保存当前会话
  - 点击历史条目时加载完整对话并展示，可继续发送消息
  - 「新建对话」时保留旧会话已保存的状态，开启全新会话
- 历史列表按创建时间倒序排列（最新在顶部）

## Impact
- Affected specs: micro-chat-dynamic-history（微聊，互不影响）
- Affected code:
  - `app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt`
  - `app/src/main/java/com/cxh09/scanpenapp/ChatHistoryItem.kt`
  - `app/src/main/java/com/cxh09/scanpenapp/ChatHistoryAdapter.kt`
  - 新增 `app/src/main/java/com/cxh09/scanpenapp/ai/Conversation.kt`
  - 新增 `app/src/main/java/com/cxh09/scanpenapp/ai/ConversationStore.kt`
  - `app/src/main/res/values/strings.xml`（新增少量提示文案）

## ADDED Requirements

### Requirement: 对话数据模型
系统 SHALL 使用 `Conversation` 数据类表示一个完整对话：
- `id: Long` — 唯一标识（时间戳生成）
- `title: String` — 会话标题（创建时的 HH:mm）
- `createdAt: Long` — 创建时间戳（毫秒）
- `messages: List<MessageRecord>` — 消息列表，每条含 `role`（user/assistant）和 `content`

### Requirement: 本地持久化存储
系统 SHALL 使用 `ConversationStore` 管理对话文件：
- 存储路径：`filesDir/conversations/`，每个会话一个 JSON 文件（`{id}.json`）
- 使用 `org.json.JSONObject` / `JSONArray`（平台自带，零依赖）序列化
- 所有 IO 操作在 `Dispatchers.IO` 执行
- 提供方法：`listConversations()`、`loadConversation(id)`、`saveConversation(conv)`、`deleteConversation(id)`

### Requirement: 启动时加载历史列表
- **WHEN** 用户进入问AI页面
- **THEN** 左侧历史列表从 `ConversationStore` 加载已有会话，按创建时间倒序排列
- **AND** 右侧显示欢迎语（无选中会话时）

### Requirement: 自动保存对话
- **WHEN** AI 回复流式完成后（`onSuccess` 分支）
- **THEN** 系统将当前会话的完整消息列表保存到本地
- **AND** 如果当前会话尚未在历史列表中，创建新条目并置顶

#### Scenario: 首次发送消息
- **WHEN** 用户在新对话中首次发送消息并收到 AI 回复
- **THEN** 会话以当前 HH:mm 为标题保存到本地
- **AND** 历史列表顶部出现该会话

#### Scenario: 继续已有对话
- **WHEN** 用户在已有历史对话中发送新消息并收到回复
- **THEN** 更新后的完整对话被保存（覆盖原文件）

### Requirement: 打开历史对话继续聊天
- **WHEN** 用户点击左侧历史列表中的某个会话
- **THEN** 右侧加载该会话的所有消息（用户消息 + AI 回复，使用 Markdown 渲染）
- **AND** `sessionHistory` 填充为该会话的消息历史，后续发送消息可正确携带上下文
- **AND** 该会话在左侧高亮为选中状态

#### Scenario: 加载并继续对话
- **WHEN** 用户点击一个历史对话
- **THEN** 右侧显示该对话的全部历史消息
- **AND** 用户可以在输入框输入新消息并发送，AI 会基于完整上下文回复
- **AND** 新消息和回复被追加到已保存的对话中

#### Scenario: 新建对话后旧对话已保存
- **WHEN** 用户在已有对话中点击「新建对话」
- **THEN** 当前对话已自动保存（之前的 AI 回复时已存）
- **AND** 右侧清空，显示欢迎语
- **AND** 左侧历史列表保持不变，旧对话仍可点击恢复

### Requirement: 历史列表标题格式
- 标题格式改为 `MM/dd HH:mm`（包含日期，方便区分不同天的对话）
- 按创建时间倒序排列

## MODIFIED Requirements

### Requirement: ChatHistoryItem 扩展
`ChatHistoryItem` 增加 `createdAt: Long` 字段，用于排序和文件关联。`isStarred` 保留但暂不使用。

### Requirement: onHistoryClicked 完整实现
原占位逻辑（仅把标题填回输入框）替换为：
1. 保存当前会话（如果有未保存的变更）
2. 从 `ConversationStore` 加载目标会话的完整消息
3. 清空右侧，重新渲染所有消息气泡
4. 填充 `sessionHistory` 以支持多轮上下文
5. 高亮选中的历史条目
