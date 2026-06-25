# Checklist

## 数据模型
- [x] `Conversation` 和 `MessageRecord` 数据类已创建，字段完整
- [x] `ChatHistoryItem` 已增加 `createdAt: Long` 字段

## 持久化存储
- [x] `ConversationStore` 使用 `filesDir/conversations/` 目录存储 JSON 文件
- [x] `saveConversation` 正确序列化并写入文件
- [x] `loadConversation` 正确反序列化 JSON 文件
- [x] `listConversations` 返回按 `createdAt` 倒序排列的会话列表
- [x] `deleteConversation` 正确删除文件
- [x] 所有 IO 操作在 `Dispatchers.IO` 执行

## 启动加载
- [x] 进入问AI页面时左侧历史列表从本地加载已有会话
- [x] 历史列表按创建时间倒序排列（最新在顶部）
- [x] 无选中会话时右侧显示欢迎语

## 自动保存
- [x] AI 回复完成后自动保存当前会话到本地
- [x] 首次发送消息时创建会话条目并出现在历史列表顶部
- [x] 继续已有对话时更新已保存的文件

## 历史对话加载与继续聊天
- [x] 点击历史条目后右侧加载该会话的所有消息
- [x] 用户消息使用用户气泡样式展示
- [x] AI 回复使用 Markwon 渲染 Markdown
- [x] `sessionHistory` 正确填充，后续消息可携带完整上下文
- [x] 选中的历史条目在左侧高亮

## 新建对话
- [x] 点击「新建对话」后右侧清空并显示欢迎语
- [x] 旧对话数据已保存，历史列表保持不变
- [x] 旧对话仍可点击恢复

## 工程规范
- [x] 所有用户可见字符串在 `strings.xml` 中声明
- [x] 未引入新的第三方库（使用平台 `org.json`）
- [x] 无 `findViewById`，使用 ViewBinding
- [x] 保持横屏，无新权限
- [x] 主线程无 IO 操作
