# Checklist

## 数据结构
- [x] `MicroChatActivity` 中 `contacts` / `messages` / `nextContactId` 字段已存在且类型可变
- [x] `buildSampleContacts()` / `buildSampleConversations()` 已删除
- [x] `strings.xml` 中的占位字符串已清理

## 新建对话
- [x] 点击右上角「+」可以创建一个新对话
- [x] 新对话标题格式为 `HH:mm`
- [x] 新对话出现在列表末尾并被自动选中
- [x] 多次点击「+」会持续追加

## 消息发送
- [x] 在选中对话中输入文本并点击「发送」，消息出现在右侧
- [x] 消息列表自动滚到底部
- [x] 左侧对应条目预览更新为该消息内容
- [x] 左侧对应条目时间更新为发送时间
- [x] 空文本不会触发发送

## 空状态
- [x] 首次进入（无历史对话）时左侧列表为空
- [x] 右侧显示「点击右上角 + 开始新对话」之类的提示
- [x] 新建第一个对话后空状态消失
- [x] 切换到不同对话时右侧标题与消息正确

## 工程规范
- [x] 所有用户可见的字符串在 `strings.xml` 中声明，无硬编码
- [x] 没有引入额外的第三方库
- [x] Activity 启动阶段没有新增耗时操作（时间格式化为轻量操作）
- [x] `viewBinding` 正常使用，未引入 `findViewById`
- [x] 仍然保持横屏（`screenOrientation="landscape"`）无需新增权限
