# Tasks

- [x] Task 1: 迁移设置图标资源
  - [x] SubTask 1.1: 将 `settinglogo.webp` 从工程根目录移动到 `app/src/main/res/drawable/ic_settings.webp`
  - [x] SubTask 1.2: 验证 `ic_settings.webp` 在 R 文件中可引用

- [x] Task 2: 主页增加设置入口
  - [x] SubTask 2.1: 修改 `app/src/main/res/layout/activity_main.xml`，在水平 `LinearLayout` 中新增 `btnSettings`（图标 + 文字「设置」）
  - [x] SubTask 2.2: 修改 `app/src/main/java/com/cxh09/scanpenapp/MainActivity.kt`，为 `binding.btnSettings` 绑定点击事件，启动 `SettingsActivity`
  - [x] SubTask 2.3: 在 `app/src/main/res/values/strings.xml` 新增 `btn_settings = "设置"`、`title_settings = "设置"`、`settings_item_placeholder = "该功能为占位演示"`

- [x] Task 3: 创建设置页（demo 占位）
  - [x] SubTask 3.1: 新增 `app/src/main/res/layout/activity_settings.xml`（顶部栏 + RecyclerView 列表）
  - [x] SubTask 3.2: 新增 `app/src/main/java/com/cxh09/scanpenapp/SettingsActivity.kt`：实现 ViewBinding、顶部栏返回、列表渲染、点击 Toast 提示
  - [x] SubTask 3.3: 在 `AndroidManifest.xml` 注册 `SettingsActivity`（landscape、非 exported）

- [x] Task 4: 列表数据与 Adapter
  - [x] SubTask 4.1: 定义 `SettingItem` 数据类（标题 + 图标资源）
  - [x] SubTask 4.2: 定义 `SettingsAdapter`（继承 `RecyclerView.Adapter` + `ViewHolder`）
  - [x] SubTask 4.3: 新增列表项布局 `item_setting.xml`（图标 + 标题 + 箭头）
  - [x] SubTask 4.4: 在 `SettingsActivity` 中预填 6 条占位项：WiFi、蓝牙、亮度、声音、语言、关于

- [x] Task 5: 深色样式与色板
  - [x] SubTask 5.1: 在 `colors.xml` 复用 `ai_chat_bg`、`ai_text_primary`、`ai_text_tertiary`、`ai_sidebar_divider` 等已有深色色板
  - [x] SubTask 5.2: 顶部栏返回按钮、列表项、箭头使用对应颜色，保证深色一致性

- [x] Task 6: 构建验证
  - [x] SubTask 6.1: 运行 `./gradlew :app:assembleDebug` 确认构建通过
  - [x] SubTask 6.2: 安装到设备，确认主页出现三个入口、点击设置能进入 demo 设置页、点击列表项弹出 Toast

# Task Dependencies
- Task 2 依赖 Task 1（图标资源先就位）
- Task 3 依赖 Task 2（入口跳转到 SettingsActivity）
- Task 4 依赖 Task 3（Activity 持有 Adapter）
- Task 5 依赖 Task 3、Task 4
- Task 6 依赖 Task 1–5
