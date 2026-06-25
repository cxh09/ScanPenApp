# 微聊动态历史 Spec

## Why
当前微聊页面用 8 条静态占位数据演示排版，违背「不要捏造占位」的要求。需要把联系人列表改为可变的：用户主动新建对话时，用当前时间（HH:mm）作为标题加入历史列表；发送消息后该条目的预览/时间随之更新。

## What Changes
- 移除 `MicroChatActivity` 中的 `buildSampleContacts()` / `buildSampleConversations()` 静态构造与示例会话数据
- 移除 `strings.xml` 中所有 `chat_group_*` / `chat_contact_*` / `chat_today*` / `chat_date_*` / `chat_time_*` / `chat_msg_*` / `chat_sender_*` 等占位字符串
- 联系人列表改为 Activity 持有的 `MutableList<ChatContact>`，会话内容改为 `MutableMap<Long, MutableList<ChatMessage>>`
- 顶部「+」按钮改造为「新建对话」：点击后以 `HH:mm` 格式当前时间作为标题生成新会话，加入列表末尾并自动选中
- `onSendClicked()` 改为真正把消息写入当前会话的列表，更新右侧 ListView 与左侧对应条目的 `preview` / `time`
- 初始进入时左侧列表为空、右侧显示空状态文案

## Impact
- Affected specs: 无（首个微聊 Spec）
- Affected code:
  - `app/src/main/java/com/cxh09/scanpenapp/MicroChatActivity.kt`
  - `app/src/main/java/com/cxh09/scanpenapp/ChatContact.kt`（保持数据类形态不变）
  - `app/src/main/res/values/strings.xml`
  - `app/src/main/res/layout/activity_micro_chat.xml`（可能需新增空状态占位 view）

## ADDED Requirements

### Requirement: 新建对话
系统 SHALL 在用户点击右上角「+」按钮时创建一个新对话：
- 标题 = 当前系统时间，格式 `HH:mm`
- 加入左侧历史列表末尾
- 自动切换为当前选中会话
- 右侧聊天区清空，等待用户输入

#### Scenario: 首次进入
- **WHEN** 用户从主页点击「微聊」图标进入页面
- **THEN** 左侧联系人列表为空，右侧显示空状态文案（如 `chat_empty_hint`）
- **AND** 「+」按钮可点击

#### Scenario: 创建第一个对话
- **WHEN** 用户在空状态下点击「+」
- **THEN** 左侧出现一项，时间为当前 `HH:mm` 且被选中（绿色高亮）
- **AND** 右侧标题更新为该时间，消息列表为空

#### Scenario: 在已有对话中再次点击「+」
- **WHEN** 用户在已存在 N 个对话时点击「+」
- **THEN** 左侧追加第 N+1 项，标题为新的 `HH:mm`
- **AND** 旧对话的选中态被取消，新对话变为选中

### Requirement: 发送消息
系统 SHALL 在当前选中对话中允许用户发送文本消息：
- 消息追加到当前会话的消息列表末尾
- 消息的 `isMine = true`，头像使用默认色
- 左侧对应条目的 `preview` 更新为该消息内容
- 左侧对应条目的 `time` 更新为消息发送时间
- 消息列表自动滚到底部

#### Scenario: 发送文本
- **WHEN** 用户在输入框输入文字后点击「发送」或按 IME 动作键
- **THEN** 消息出现在右侧聊天记录底部
- **AND** 左侧对应条目预览更新为该文字（截断/省略处理后）
- **AND** 输入框清空

#### Scenario: 空文本不发送
- **WHEN** 输入框为空或仅含空白
- **THEN** 不会添加新消息

### Requirement: 空状态展示
系统 SHALL 在没有选中任何对话时在右侧展示提示：
- 显示一段文案（如 `chat_empty_hint`）
- 标题栏（`tvChatTitle`）保持空或显示「请选择/新建对话」

## REMOVED Requirements

### Requirement: 静态占位联系人列表
**Reason**: 不再使用 8 条假联系人/群组演示
**Migration**:
- 删除 `buildSampleContacts()` 与 `buildSampleConversations()`
- 删除 `strings.xml` 中 `chat_group_*`、`chat_contact_*`、`chat_today*`、`chat_date_*`、`chat_time_*`、`chat_msg_*`、`chat_sender_*` 等仅供占位使用的资源
- 保留 `chat_search_hint`、`chat_action_*`、`chat_input_hint`、`chat_action_send` 等功能性字符串

### Requirement: 首次进入自动选中第一个对话
**Reason**: 静态数据移除后没有"第一个"概念
**Migration**: 进入时若无任何对话则保持空状态，直到用户新建
