# Checklist

- [x] `settinglogo.webp` 已迁移到 `app/src/main/res/drawable/ic_settings.webp`
- [x] `activity_main.xml` 包含三个入口：`btnAskAi`、`btnMicroChat`、`btnSettings`，横向居中排列
- [x] `MainActivity` 为 `btnSettings` 绑定点击事件，跳转 `SettingsActivity`
- [x] `SettingsActivity` 已注册到 `AndroidManifest.xml`，`screenOrientation="landscape"`，`exported="false"`
- [x] `activity_settings.xml` 顶部栏有「← 返回」按钮与「设置」标题
- [x] `SettingsActivity` 实现 `btnBack` 返回主页
- [x] 列表项包含至少 6 条：WiFi、蓝牙、亮度、声音、语言、关于
- [x] 每条列表项有点击 Toast 占位提示（`settings_item_placeholder`）
- [x] 列表使用 `RecyclerView` + `ViewHolder` 复用（项目规范 2.2）
- [x] 全部文字走 `strings.xml`，无硬编码中文字符串
- [x] 设置页背景为纯黑（`#000000` 或 `ai_chat_bg`），与主页深色基调一致
- [x] 不引入 WebView、大型第三方 UI 库（项目规范 6）
- [x] 构建 `assembleDebug` 通过
