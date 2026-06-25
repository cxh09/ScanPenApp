# Tasks

- [x] Task 1: 把数据源从静态示例改为可变结构
  - [x] SubTask 1.1: 在 `MicroChatActivity` 中新增 `private val contacts = mutableListOf<ChatContact>()` 与 `private val messages = mutableMapOf<Long, MutableList<ChatMessage>>()` 与 `private var nextContactId()` 自增函数
  - [x] SubTask 1.2: 引入 `SimpleDateFormat("HH:mm", Locale.getDefault())` 作为时间格式化器

- [x] Task 2: 清理静态占位数据
  - [x] SubTask 2.1: 删除 `MicroChatActivity.buildSampleContacts()` 与 `buildSampleConversations()`
  - [x] SubTask 2.2: 删除 `strings.xml` 中所有 `chat_group_*` / `chat_contact_*` / `chat_today*` / `chat_date_*` / `chat_time_*` / `chat_msg_*` / `chat_sender_*` 字符串
  - [x] SubTask 2.3: 调整 `setupContactList` / `setupMessageList`：仅创建 Adapter，不再注入数据
  - [x] SubTask 2.4: 调整 `onCreate` 顺序，使 messageAdapter 在 contactAdapter 之前初始化

- [x] Task 3: 新增「+」按钮的新建对话交互
  - [x] SubTask 3.1: `activity_micro_chat.xml` 把 `btnAddContact` 改名为 `btnNewChat`（更贴合语义），`contentDescription` 改用 `chat_action_new_chat`
  - [x] SubTask 3.2: `MicroChatActivity.setupInputBar` 中绑定 `btnNewChat` 的 click 事件
  - [x] SubTask 3.3: 实现 `createNewConversation()`：以当前 `HH:mm` 作为 name，分配 id，`preview=""`，`time=""`，加入 `contacts`，写入空消息列表，notify，设为选中
  - [x] SubTask 3.4: 切换到新对话的渲染逻辑复用现有 `switchConversation`

- [x] Task 4: 完善发送消息 + 列表预览更新
  - [x] SubTask 4.1: `onSendClicked()` 中把消息 append 到 `messages[activeId]`，并 `messageAdapter.submit(...)` + `lvMessages.setSelection(...)`
  - [x] SubTask 4.2: 同步更新 `contacts[activeIndex]` 的 `preview`（截断到 24 字符以内）与 `time`（`HH:mm`）
  - [x] SubTask 4.3: `contactAdapter.notifyDataSetChanged()` 触发左侧更新

- [x] Task 5: 空状态与边界处理
  - [x] SubTask 5.1: `renderEmptyState` 在无选中会话时清空 `tvChatTitle` 并显示空状态
  - [x] SubTask 5.2: 在 `activity_micro_chat.xml` 中新增一个 `tvEmptyHint` TextView，居中显示 `chat_empty_hint`，默认 `visibility="gone"`
  - [x] SubTask 5.3: 联系人列表为空时 `tvEmptyHint` 显示「点击右上角 + 开始新对话」

# Task Dependencies
- Task 2 依赖 Task 1（要先有可变结构再删除静态构造，否则编译失败）
- Task 3 依赖 Task 2（占位数据移除后才能接入"新建"逻辑）
- Task 4 依赖 Task 3（必须存在"当前会话"才能发送消息）
- Task 5 依赖 Task 2、Task 3（空状态与选中态耦合）
