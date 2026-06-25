# ScanPenApp

> 面向词典笔类小硬件的智能扫描 / 随身助手 Android 应用。

词典笔的窄长横屏、低端 SoC、紧内存与电池供电形态决定了本项目的一切实现选择：所有页面强制横屏、零 Compose、零 WebView、严格控制图片与依赖体积、避免任何启动期耗时操作。

---

## ✨ 功能特性

| 模块 | 说明 |
| --- | --- |
| **问 AI** | 对接 OpenAI 兼容 API（DeepSeek / OpenCode / GLM 等预设），支持 Markdown 渲染（Markwon）、历史对话、API Key 二维码扫码导入 / 分享 / 全屏预览 |
| **微聊** | 联系人 + 聊天消息 + 历史会话列表的轻量 IM 演示界面（本地数据） |
| **设置** | WiFi / 蓝牙 / 亮度 / 声音 / 语言（占位演示）+ 关于本机 |
| **关于本机** | 厂商、型号、Android 版本、内存、存储、屏幕分辨率、应用版本 — 数据全部来自平台 API，不申请额外权限 |

---

## 🎯 目标设备

- **形态**：词典笔（窄长横屏，接近 16:5 或更窄）
- **SoC / 内存**：低端 ARM，常见 ≤ 2 GB
- **屏幕方向**：**强制 `landscape`**，所有 Activity 均在 `AndroidManifest.xml` 显式声明
- **电池**：避免任何长时 `WakeLock` / 常驻服务 / 无限制轮询

---

## 🛠 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Kotlin |
| 编译 | AGP `9.2.1`，`compileSdk = 36`（含 `minorApiLevel = 1`），`minSdk = 24`，`targetSdk = 36` |
| Java | 11（`sourceCompatibility` / `targetCompatibility`） |
| UI 体系 | 传统 View + ViewBinding（**不**引入 Compose / WebView） |
| AI 客户端 | [`com.aallam.openai:openai-client`](https://github.com/aallam/openai-kotlin) `4.1.0` + Ktor `3.0.0` |
| Markdown | Markwon `4.6.2`（core / tables / strikethrough） |
| 相机 & 扫码 | CameraX `1.4.1` + ML Kit Barcode `17.3.0` + ZXing core `3.5.3` |
| 动画 | `androidx.dynamicanimation` `1.0.0` |
| 构建 | 版本目录 `gradle/libs.versions.toml`；Release 走 `proguard-android-optimize.txt` + 项目 ProGuard 规则 |

---

## 🚀 构建与运行

```bash
# 克隆
git clone https://github.com/cxh09/ScanPenApp.git
cd ScanPenApp

# 调试构建（输出 app/build/outputs/apk/debug/app-debug.apk）
./gradlew :app:assembleDebug

# 发布构建
./gradlew :app:assembleRelease

# 安装到已连接设备
./gradlew :app:installDebug
```

> Windows 下使用 `gradlew.bat`；首次运行需联网以下载 Gradle Wrapper 与依赖。

---

## 📁 目录结构

```
app/
├── build.gradle.kts              # 模块构建脚本
├── proguard-rules.pro
└── src/
    ├── main/
    │   ├── AndroidManifest.xml   # 所有 Activity 强制 landscape
    │   ├── java/com/cxh09/scanpenapp/
    │   │   ├── MainActivity.kt           # 入口：问 AI / 微聊 / 设置
    │   │   ├── AskAiActivity.kt          # 问 AI 主界面
    │   │   ├── MicroChatActivity.kt      # 微聊主界面
    │   │   ├── SettingsActivity.kt       # 设置列表
    │   │   ├── AboutDeviceActivity.kt    # 关于本机
    │   │   └── ai/                       # AI 子模块：API 设置、QR 扫码/分享/预览、客户端持有
    │   └── res/
    │       ├── layout/                   # 全部横屏布局，优先 ConstraintLayout
    │       ├── drawable/                 # WebP 优先，PNG 大图禁止
    │       ├── values/                   # strings / colors / themes（日间）
    │       ├── values-night/themes.xml   # 夜间主题
    │       └── xml/                      # backup / data extraction 规则
    ├── test/                     # 单元测试（JUnit 4）
    └── androidTest/              # Instrumentation 测试（Espresso）
gradle/
└── libs.versions.toml            # 版本目录（依赖单一来源）
```

---

## ⚡ 性能与体验约束（节选自项目规范）

- **启动**：`Application.onCreate` / `MainActivity.onCreate` 不做耗时操作（网络、IO、JSON、反射、图片解码全部禁止）
- **主线程**：禁止网络 / IO / 数据库查询 / JSON 解析 / `Bitmap` 解码；协程统一 `viewModelScope` / `lifecycleScope`，**不要用 `GlobalScope`**
- **图片**：优先 WebP；`Bitmap` 必须复用 + 及时 `recycle()`；`RecyclerView` 强制 ViewHolder 复用，`onBindViewHolder` 内**禁止**对象分配与图片解码
- **布局**：层级 ≤ 4 层，复杂场景用 `ConstraintLayout` 替代嵌套 `LinearLayout`；动画用 `Property Animation` / `Choreographer`
- **电量**：`WakeLock` 短时持有；后台任务统一 `WorkManager` + 约束条件
- **包体**：避免引入大型全家桶；Release 开启 `isMinifyEnabled` + 资源压缩
- **可读性**：所有字符串走 `strings.xml`（**禁止**硬编码中文到代码 / 布局），正文 ≥ 14sp，按钮高度 56–72dp，点击区域 ≥ 48dp

---

## 🤝 贡献

1. 单次提交只做一件事；commit message 写「为什么」而非「做了什么」
2. 涉及启动 / 内存 / 渲染的改动必须在 PR 描述中说明：改动点、风险点、验证方式
3. 修改 `AndroidManifest.xml` 中的方向 / 权限 / launcher 入口须显式说明原因

---

## 📄 License

未指定。如需开源许可，请联系仓库所有者添加 `LICENSE` 文件。
