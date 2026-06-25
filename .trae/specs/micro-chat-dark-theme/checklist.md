# Checklist

## 色值映射生效
- [x] `chat_bg` 渲染为 `#000000`
- [x] `chat_sidebar_bg` 渲染为 `#0A0A0A`
- [x] `chat_sidebar_divider` 渲染为 `#1A1A1A`
- [x] `chat_sidebar_active` 渲染为 `#2A2A2A`（深灰高亮，无绿色）
- [x] `chat_sidebar_active_text` 渲染为 `#E8E8ED`
- [x] `chat_bubble_mine` 渲染为 `#2A2A2E`（深灰气泡）
- [x] `chat_bubble_other` 渲染为 `#1C1C1E`（深灰气泡）
- [x] `chat_bubble_text` 渲染为 `#E8E8ED`
- [x] `chat_text_primary` / `chat_text_secondary` / `chat_text_tertiary` 分别渲染为 `#E8E8ED` / `#8E8E93` / `#5A5A5E`
- [x] `chat_input_bg` / `chat_input_border` 分别渲染为 `#1C1C1E` / `#2C2C2E`
- [x] `chat_send_bg` / `chat_send_text` 分别渲染为 `#E8E8ED` / `#000000`（无绿色）

## 图标可读性
- [x] `searchBox` 中 `ic_search` 在深色背景上可清晰看见（实际色 = `chat_text_secondary`）
- [x] `btnNewChat` 中 `ic_plus` 在深色背景上可清晰看见（实际色 = `chat_text_primary`）
- [x] 顶部标题栏 `btnEmoji` / `btnCall` / `btnMore` 图标（已在 drawable 内 tint 到 `chat_text_*`，自动跟随）
- [x] 底部输入栏 `btnInputEmoji` / `btnInputFile` / `btnInputImage` / `btnInputScissors` / `btnInputMic` 图标（已在 drawable 内 tint，自动跟随）

## 交互态
- [x] 选中联系人：背景变为 `#2A2A2A` 高亮，文字反白为 `#E8E8ED`
- [x] 按下未选中联系人：出现 ripple 反馈（色 = `chat_sidebar_hover` = `#1A1A1A`），不与选中态撞色
- [x] 发送按钮：浅色底 `#E8E8ED` + 黑色文字 `#000000`

## 业务功能未回归
- [x] 仍能从主页点击「微聊」图标进入页面
- [x] 仍能点击右上角「+」创建新对话（标题仍为 `HH:mm`）
- [x] 仍能在选中对话中发送消息，气泡自动滚到底部
- [x] 空状态仍能展示「点击右上角 + 开始新对话」提示
- [x] 仍保持横屏（`screenOrientation="landscape"`）约束

## 工程规范
- [x] 未引入新依赖、未修改 `libs.versions.toml`
- [x] 未修改 `AndroidManifest.xml` 方向、权限、launcher
- [x] 未在 Kotlin / Java 中硬编码任何颜色，全部走 `@color/chat_*` 资源
- [x] `viewBinding` 仍然可用，没有出现 `findViewById` 回退
- [x] `gradle assembleDebug` 构建通过，资源无报错
- [x] 改动文件清单 ≤ 3 个：`colors.xml` + `activity_micro_chat.xml` + `bg_contact_item.xml`
