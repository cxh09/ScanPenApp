# Tasks

- [x] Task 1: 新增对话数据模型 `Conversation` 和 `MessageRecord`
  - [x] SubTask 1.1: 在 `ai/` 包下新建 `Conversation.kt`，包含 `MessageRecord(role: String, content: String)` 和 `Conversation(id: Long, title: String, createdAt: Long, messages: List<MessageRecord>)` 数据类

- [x] Task 2: 新增 `ConversationStore` 持久化存储层
  - [x] SubTask 2.1: 在 `ai/` 包下新建 `ConversationStore.kt`，构造函数接收 `Context`，内部使用 `filesDir/conversations/` 目录
  - [x] SubTask 2.2: 实现 `saveConversation(conv: Conversation)` — 用 `org.json.JSONObject` 序列化，写入 `{id}.json`
  - [x] SubTask 2.3: 实现 `loadConversation(id: Long): Conversation?` — 读取并反序列化 JSON 文件
  - [x] SubTask 2.4: 实现 `listConversations(): List<Conversation>` — 扫描目录，返回所有会话（不含 messages 详情），按 `createdAt` 倒序
  - [x] SubTask 2.5: 实现 `deleteConversation(id: Long)` — 删除对应 JSON 文件

- [x] Task 3: 扩展 `ChatHistoryItem` 数据类
  - [x] SubTask 3.1: 给 `ChatHistoryItem` 增加 `createdAt: Long` 字段（默认值 0L）

- [x] Task 4: 修改 `ChatHistoryAdapter` 支持选中态
  - [x] SubTask 4.1: 增加 `selectedId: Long?` 属性和 `setSelected(id: Long?)` 方法
  - [x] SubTask 4.2: `getView` 中根据 `selectedId` 切换背景高亮

- [x] Task 5: 修改 `AskAiActivity` — 启动加载与自动保存
  - [x] SubTask 5.1: 新增 `ConversationStore` 实例和 `currentConversationId: Long?` 字段
  - [x] SubTask 5.2: `onCreate` 中调用 `ConversationStore.listConversations()` 加载历史列表，填充 `history` 并展示
  - [x] SubTask 5.3: 发送消息后 AI 回复完成时（`onSuccess` 分支），将当前会话保存到 `ConversationStore`
  - [x] SubTask 5.4: 首次创建会话时生成 `currentConversationId`（用 `System.currentTimeMillis()`），创建 `Conversation` 对象并保存

- [x] Task 6: 修改 `AskAiActivity` — 点击历史加载对话并继续聊天
  - [x] SubTask 6.1: 重写 `onHistoryClicked`：加载完整 `Conversation`，清空右侧，遍历 `messages` 重建气泡（用户消息 / AI 回复用 Markwon 渲染）
  - [x] SubTask 6.2: 填充 `sessionHistory` 为加载的消息列表，设置 `currentSessionRecorded = true`
  - [x] SubTask 6.3: 更新 `historyAdapter.setSelected(id)` 高亮选中项
  - [x] SubTask 6.4: 设置 `currentConversationId` 为目标会话 ID

- [x] Task 7: 修改 `AskAiActivity` — 新建对话逻辑调整
  - [x] SubTask 7.1: `startNewChat()` 中清除 `currentConversationId`，重置 `currentSessionRecorded = false`
  - [x] SubTask 7.2: 取消历史列表选中态
  - [x] SubTask 7.3: 保留已有会话数据不动（已持久化），仅清空 UI

- [x] Task 8: 新增字符串资源
  - [x] SubTask 8.1: 在 `strings.xml` 中新增标题格式所需的日期格式化字符串，`colors.xml` 中新增选中高亮色

# Task Dependencies
- Task 2 依赖 Task 1（存储层需要数据模型）
- Task 3 无依赖，可与 Task 1/2 并行
- Task 4 依赖 Task 3（Adapter 需要 `createdAt` 字段来标识选中）
- Task 5 依赖 Task 1、2、3（Activity 需要数据模型 + 存储层 + 扩展字段）
- Task 6 依赖 Task 5（在加载历史列表基础上实现点击加载）
- Task 7 依赖 Task 5（新建对话逻辑需配合持久化状态）
- Task 8 可与其他任务并行
