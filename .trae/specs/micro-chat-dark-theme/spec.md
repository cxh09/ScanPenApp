# 微聊深色主题 Spec

## Why
当前微聊页面使用浅色（白/灰）配色 + 微信绿，与「问 AI」页面的深色风格不一致，用户体验上出现「同一应用两种风格」的割裂感。需要把微聊页面统一为深色视觉，对齐 `ai_*` 调色板的设计语言（纯黑背景 + 中性灰交互 + 浅色文字）。

## What Changes
- 重写 `values/colors.xml` 中 `chat_*` 系列颜色的色值，把所有 `chat_*` 资源改为深色值（与 `ai_*` 系列保持一致的中性灰阶）
- 在 `activity_micro_chat.xml` 中给搜索图标与「+」图标 ImageView 显式添加 `app:tint`，让它们在深色背景上保持可见
- 调整 `bg_contact_item.xml` 中 `state_pressed` 态的 ripple 颜色，使其在深色背景上仍可感知
- 不改 Kotlin / Java 代码：所有颜色都已通过 `@color/chat_*` / `R.color.chat_*` 引用，重写色值即生效
- 不改 drawable 形状文件本身（`bg_chat_bubble_*.xml` 等已经引用颜色资源，会自动跟随）
- 不引入新依赖、不动主题文件、不动横屏约束

## Impact
- Affected specs：无（首个微聊深色主题 Spec）
- Affected code：
  - `app/src/main/res/values/colors.xml`（重写 `chat_*` 15 个色值）
  - `app/src/main/res/layout/activity_micro_chat.xml`（仅加 `app:tint`，不改结构）
  - `app/src/main/res/drawable/bg_contact_item.xml`（ripple 颜色从 `chat_sidebar_active` 切到 `chat_sidebar_hover`）

## 色值映射（浅 → 深，对齐 `ai_*` 系列）

| 颜色资源 | 旧值 | 新值 | 说明 |
|---|---|---|---|
| `chat_bg` | `#EDEDED` | `#000000` | 主区背景，纯黑 |
| `chat_sidebar_bg` | `#F5F5F7` | `#0A0A0A` | 左侧联系人列表背景 |
| `chat_sidebar_divider` | `#E5E5EA` | `#1A1A1A` | 侧边栏与主区之间 1dp 细分隔线、顶部标题栏下分割线 |
| `chat_sidebar_active` | `#07C160` | `#2A2A2A` | 当前选中联系人的高亮背景（去掉微信绿，改为中性深灰，与 `ai_history_selected` 一致） |
| `chat_sidebar_active_text` | `#FFFFFF` | `#E8E8ED` | 选中态联系人文字色（浅色） |
| `chat_bubble_mine` | `#95EC69` | `#2A2A2E` | 「我」发送的消息气泡背景（与 `ai_msg_user_bg` 一致） |
| `chat_bubble_other` | `#FFFFFF` | `#1C1C1E` | 「他人」消息气泡背景（与 `ai_msg_ai_bg` 一致） |
| `chat_text_primary` | `#1A1A1A` | `#E8E8ED` | 主文字（标题、姓名） |
| `chat_text_secondary` | `#6B6B6B` | `#8E8E93` | 次级文字（预览） |
| `chat_text_tertiary` | `#9A9AA0` | `#5A5A5E` | 三级文字（时间戳、占位、empty hint） |
| `chat_input_bg` | `#FFFFFF` | `#1C1C1E` | 输入框背景 |
| `chat_input_border` | `#E5E5EA` | `#2C2C2E` | 输入框边框 |
| `chat_send_bg` | `#07C160` | `#E8E8ED` | 发送按钮背景（去掉微信绿，改为浅色填充，与 `ai_send_bg` 一致） |
| `chat_send_text` | `#FFFFFF` | `#000000` | 发送按钮文字色（黑字配浅底） |
| `chat_bubble_text` | `#1A1A1A` | `#E8E8ED` | 气泡内文字（深底配浅字） |

## ADDED Requirements

### Requirement: 微聊页面整体深色化
系统 SHALL 把微聊页面所有视觉元素的颜色从浅色方案切换为深色方案，颜色资源名保持 `chat_*` 不变，色值与 `ai_*` 系列对齐。

#### Scenario: 进入微聊页面
- **WHEN** 用户从主页点击「微聊」图标进入 `MicroChatActivity`
- **THEN** 整个页面呈深色外观：
  - 左侧联系人列表区域背景为接近纯黑（`#0A0A0A`）
  - 右侧主区背景为纯黑（`#000000`）
  - 顶部标题栏文字、列表项文字均为浅灰色
  - 搜索框、输入框为深灰底（`#1C1C1E`）+ 边框（`#2C2C2E`）
  - 搜索图标、「+」图标、底部表情/文件/图片/剪刀/麦克风图标均为浅色（在深色背景上清晰可见）
  - 发送按钮为浅色填充（`#E8E8ED`）+ 黑色文字

#### Scenario: 选中联系人
- **WHEN** 用户点击左侧某个联系人
- **THEN** 该条目背景变为深灰高亮（`#2A2A2A`），文字反白为浅色（`#E8E8ED`）
- **AND** 没有使用任何绿色作为高亮色

#### Scenario: 发送消息后渲染
- **WHEN** 用户在选中对话中发送一条文本消息
- **THEN** 右侧出现「我」气泡，背景为 `#2A2A2E` 深灰，文字为浅色
- **AND** 左侧对应条目预览文字保持浅灰色（`#8E8E93`）

#### Scenario: 空状态
- **WHEN** 用户首次进入（无任何对话）
- **THEN** 右侧中央 `tvEmptyHint` 显示「点击右上角 + 开始新对话」，文字色为三级灰（`#5A5A5E`），在深色背景上可读

### Requirement: 图标在深色背景上的可读性
系统 SHALL 保证所有出现在微聊页面的图标在深色背景上保持视觉清晰：要么在 drawable 内部已有 `android:tint` 引用 `chat_text_*` 资源（自动跟随），要么在布局里给 ImageView 显式添加 `app:tint` 属性。

#### Scenario: 搜索图标
- **WHEN** 渲染 `searchBox` 内的 `ic_search` 图标
- **THEN** 实际渲染色为 `chat_text_secondary`（`#8E8E93`）—— 通过布局 `app:tint` 强制覆盖 `?attr/colorControlNormal`

#### Scenario: 新建对话「+」图标
- **WHEN** 渲染 `btnNewChat` 的 `ic_plus` 图标
- **THEN** 实际渲染色为 `chat_text_primary`（`#E8E8ED`）—— 通过布局 `app:tint` 强制覆盖

### Requirement: 联系人按下态 ripple
系统 SHALL 在深色背景下让联系人的按下态 ripple 仍可被用户感知。

#### Scenario: 按下未选中的联系人
- **WHEN** 用户在深色侧边栏里按下某个未选中条目
- **THEN** 该条目出现一个比侧边栏背景略亮、但不与选中态混淆的 ripple 反馈
- **AND** ripple 颜色 = `chat_sidebar_hover`（`#1A1A1A`），而不是当前的 `chat_sidebar_active`（已改为深灰，避免与选中态视觉撞色）

## MODIFIED Requirements

无（之前没有微聊深色主题相关需求，本次为新增）。

## REMOVED Requirements

### Requirement: 微聊页面使用微信绿作为品牌色
**Reason**: 与「问 AI」深色风格对齐，去掉绿色强调色，全页统一为中性灰 + 浅文字
**Migration**:
- `chat_sidebar_active`（旧 `#07C160`）改为 `#2A2A2A` —— 不再用绿色表示选中
- `chat_send_bg`（旧 `#07C160`）改为 `#E8E8ED` —— 发送按钮不再用绿色背景
- `chat_bubble_mine`（旧 `#95EC69`）改为 `#2A2A2E` —— 「我」的气泡不再用绿色
- 不改 `strings.xml` 中的功能性文案（`chat_action_send`、联系人名格式等均不涉及颜色）
- 不改 `bg_avatar.xml` 的 `ai_avatar_bg` 默认填充色（运行时 `ChatContact.avatarColor` / `ChatMessage.avatarColor` 会覆盖，深色背景下浅蓝头像自然可读）
