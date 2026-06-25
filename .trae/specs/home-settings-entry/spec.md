# 主页新增「设置」入口 Spec

## Why
当前主页 (`MainActivity`) 只有两个功能入口：「问 AI」与「微聊」。产品需要补充第三个入口「设置」，让用户能进入应用级设置页面（设备相关、显示、声音、关于等），使用提供的 `settinglogo.webp` 作为图标；设置页仅做 demo 占位（点击不落地真实功能），整体采用深色设计，与项目深色基调一致。

## What Changes
- 将 `settinglogo.webp` 从工程根目录迁移到 `app/src/main/res/drawable/ic_settings.webp`（去除文件名中的 `settinglogo` 前缀，与项目 `ic_xxx` 命名一致）
- 修改 `activity_main.xml`：在水平 `LinearLayout` 中新增 `btnSettings` 入口（图标 + 文字），位置在「微聊」之后
- 修改 `MainActivity.kt`：为 `btnSettings` 绑定点击事件，启动新的 `SettingsActivity`
- 新增 `SettingsActivity`：演示用的设置列表页面，深色主题，包含若干占位项（WiFi、蓝牙、亮度、声音、语言、关于）
- `AndroidManifest.xml` 注册 `SettingsActivity`，`screenOrientation="landscape"`
- 新增 `strings.xml` 文案：设置入口、设置页标题、各设置项名称
- 新增 `colors.xml` 深色设置页色板（与现有 AI 页面深色色板风格统一）

## Impact
- Affected specs: 无（独立新功能）
- Affected code:
  - `app/src/main/res/drawable/ic_settings.webp`（新增，从根目录迁移）
  - `app/src/main/res/layout/activity_main.xml`（新增 `btnSettings`）
  - `app/src/main/res/layout/activity_settings.xml`（新增）
  - `app/src/main/res/values/strings.xml`（新增设置相关文案）
  - `app/src/main/res/values/colors.xml`（新增设置页色板）
  - `app/src/main/java/com/cxh09/scanpenapp/MainActivity.kt`（绑定点击事件）
  - `app/src/main/java/com/cxh09/scanpenapp/SettingsActivity.kt`（新增）
  - `app/src/main/AndroidManifest.xml`（注册新 Activity）

## ADDED Requirements

### Requirement: 主页第三个入口「设置」
`activity_main.xml` SHALL 在原有 `btnAskAi`、`btnMicroChat` 之后增加第三个 `btnSettings` 入口：
- 同样为垂直 `LinearLayout`，包含 `ImageView`（72dp，使用 `@drawable/ic_settings`）与 `TextView`（「设置」）
- 与前两个入口保持等距横向排列
- 全部在水平 `LinearLayout` 内，`layout_gravity="center"`，整体居中

#### Scenario: 主页渲染
- **WHEN** 用户启动应用进入主页
- **THEN** 看到三个图标横向居中排列：问 AI、微聊、设置
- **AND** 「设置」图标使用提供的蓝色齿轮样式（蓝底白齿轮）

### Requirement: 设置图标资源
工程根目录的 `settinglogo.webp` SHALL 移动到 `app/src/main/res/drawable/ic_settings.webp`：
- 保持原 `webp` 格式（低内存设备要求）
- 文件名改为 `ic_settings.webp` 与项目已有 `ic_xxx` 命名一致
- 引用路径使用 `@drawable/ic_settings`

### Requirement: 设置页面入口跳转
`MainActivity` SHALL 为 `binding.btnSettings` 绑定 `setOnClickListener`：
- 点击后通过 `Intent` 启动 `SettingsActivity`
- 不传递任何参数（设置页 demo 暂不需要外部数据）

### Requirement: 设置页面（demo 占位）
系统 SHALL 提供 `SettingsActivity`，作为应用级设置的占位页面：
- 顶部栏：左返回 + 中部标题「设置」 + 右侧留空
- 下方为 `RecyclerView` 列表，展示设置项
- 每项为一行：左侧 `ImageView`（简单占位图标，使用系统内置 vector 或现有 `ic_xxx`） + 中部标题 + 右侧 `chevron` 箭头
- 列表项至少包含：WiFi、蓝牙、亮度、声音、语言、关于（本机）
- 点击任意项 SHALL 不做实际跳转/操作（demo），可显示一个简短 Toast 提示「该功能为占位」

#### Scenario: 进入设置页
- **WHEN** 用户从主页点击「设置」图标
- **THEN** 跳转到 `SettingsActivity`
- **AND** 顶部栏显示「设置」标题
- **AND** 列表显示上述设置项

#### Scenario: 返回主页
- **WHEN** 用户在设置页点击左上角「← 返回」
- **THEN** 关闭 `SettingsActivity`，回到主页

#### Scenario: 点击设置项
- **WHEN** 用户点击任意设置项
- **THEN** 弹出一个简短 Toast 提示（`settings_item_placeholder`）
- **AND** 不做实际跳转或修改

### Requirement: 设置页深色设计
`SettingsActivity` SHALL 使用深色主题，与主页深色风格一致：
- 背景色：`#000000`（`ai_chat_bg`）
- 顶部栏背景：透明
- 顶部栏标题文字：`@color/ai_text_primary`（`#E8E8ED`）
- 列表项背景：`#0A0A0A`（与 sidebar 一致）
- 列表项间分隔线：`#1A1A1A`（`ai_sidebar_divider`）
- 列表项文字：`@color/ai_text_primary`
- 列表项右侧箭头：`@color/ai_text_tertiary`（`#5A5A5E`）

#### Scenario: 视觉一致
- **WHEN** 用户从主页（纯黑背景）进入设置页
- **THEN** 视觉风格连续、不出现浅色闪烁
- **AND** 文字在户外强光下可读

### Requirement: 设置 Activity 在 Manifest 注册
`AndroidManifest.xml` SHALL 注册 `SettingsActivity`：
- `android:name=".SettingsActivity"`
- `android:exported="false"`
- `android:label="@string/title_settings"`
- `android:screenOrientation="landscape"`（强制横屏，与项目规范一致）

## MODIFIED Requirements
无（新增独立功能，未修改已有需求）

## REMOVED Requirements
无
