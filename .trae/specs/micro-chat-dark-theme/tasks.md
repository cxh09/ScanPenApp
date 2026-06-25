# Tasks

- [x] Task 1: 重写 `values/colors.xml` 中的 `chat_*` 色值
  - [x] SubTask 1.1: 把 `chat_bg` / `chat_sidebar_bg` / `chat_sidebar_divider` 改为深色（`#000000` / `#0A0A0A` / `#1A1A1A`）
  - [x] SubTask 1.2: 把 `chat_sidebar_active` / `chat_sidebar_active_text` 改为深灰高亮 + 浅色文字（`#2A2A2A` / `#E8E8ED`），去掉微信绿
  - [x] SubTask 1.3: 把 `chat_bubble_mine` / `chat_bubble_other` / `chat_bubble_text` 改为深色气泡 + 浅色文字（`#2A2A2E` / `#1C1C1E` / `#E8E8ED`）
  - [x] SubTask 1.4: 把 `chat_text_primary` / `chat_text_secondary` / `chat_text_tertiary` 改为浅色三档（`#E8E8ED` / `#8E8E93` / `#5A5A5E`）
  - [x] SubTask 1.5: 把 `chat_input_bg` / `chat_input_border` 改为深色（`#1C1C1E` / `#2C2C2E`）
  - [x] SubTask 1.6: 把 `chat_send_bg` / `chat_send_text` 改为浅底深字（`#E8E8ED` / `#000000`），去掉微信绿

- [x] Task 2: 在 `activity_micro_chat.xml` 给搜索与「+」图标加 `app:tint`
  - [x] SubTask 2.1: 给 `searchBox` 里的 `ic_search` ImageView 添加 `android:tint="@color/chat_text_secondary"`（或 `app:tint`，按现有命名风格统一）
  - [x] SubTask 2.2: 给 `btnNewChat` 的 `ic_plus` ImageView 添加 `android:tint="@color/chat_text_primary"`
  - [x] SubTask 2.3: 不改其它结构、padding、constraint、id、文案

- [x] Task 3: 调整 `bg_contact_item.xml` 的 ripple 配色
  - [x] SubTask 3.1: 把 `state_pressed` 项的 `<solid android:color>` 从 `@color/chat_sidebar_active` 切到 `@color/chat_sidebar_hover`，避免与新选中态深灰高亮撞色
  - [x] SubTask 3.2: 保持 `state_activated` 项仍用 `chat_sidebar_active`（深灰高亮）

- [x] Task 4: 验证深色主题渲染
  - [x] SubTask 4.1: 跑 `gradle assembleDebug`，确保资源编译无报错
  - [x] SubTask 4.2: 肉眼/截图检查：进入微聊页面后整体为深色，无残留浅色元素
  - [x] SubTask 4.3: 检查选中态、按下态、发送按钮、气泡颜色均按色值映射生效

# Task Dependencies
- Task 2 依赖 Task 1（先有色值才能在 layout 里 tint 引用）
- Task 3 依赖 Task 1（ripple 颜色引用 `chat_sidebar_hover`）
- Task 4 依赖 Task 1 / 2 / 3
