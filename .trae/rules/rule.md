# ScanPenApp 项目规范

本项目运行在词典笔类小硬件设备上，硬件资源有限（低内存、低算力、小屏幕、电池供电）。所有改动必须遵守以下规则。

## 1. 运行环境与基础约束

- 设备形态：词典笔（窄长横屏、低端 SoC、内存 ≤ 2GB 常见）
- 屏幕方向：**强制横屏**（landscape）。所有 Activity 必须在 `AndroidManifest.xml` 中显式声明 `android:screenOrientation="landscape"`，不得使用 `unspecified` 或跟随系统。
- 不得引入会触发旋转、传感器相关的 API（如 `TYPE_ROTATION_VECTOR`）造成额外功耗。
- 应用入口固定为 `MainActivity`（LAUNCHER），不要新增多个 launcher。

## 2. 性能优化（最高优先级）

### 2.1 启动与首屏
- 禁止在 `Application.onCreate` / `MainActivity.onCreate` 中执行耗时操作（网络、文件 IO、反射、JSON 解析）。
- 启动阶段只做最轻量的初始化；其余逻辑放到后台线程或懒加载。
- 避免冷启动白屏：必要时使用主题 `windowBackground` 占位。

### 2.2 内存
- 严格控制图片资源：优先使用 `WebP`，禁止使用 `PNG` 大图。
- `Bitmap` 必须复用（`inBitmap`/`BitmapFactory.Options.inSampleSize`），用完及时 `recycle()`。
- 列表（`RecyclerView`）必须使用 `ViewHolder` 复用；禁止在 `onBindViewHolder` 中做对象分配或解码图片。
- 避免内存泄漏：Activity / Fragment 持有 `Context` 时注意生命周期；Handler / 协程必须可取消。
- 使用 `LeakCanary` 仅在 debug 构建中启用。

### 2.3 CPU 与渲染
- 避免过度绘制（overdraw）。布局层级 ≤ 4 层，复杂场景使用 `ConstraintLayout` 替代嵌套 `LinearLayout`。
- 禁止在主线程执行：文件 IO、网络请求、数据库查询、`Bitmap` 解码、JSON 解析。
- 协程：`Dispatchers.IO` 处理 IO；`Dispatchers.Default` 处理 CPU；UI 必须在 `Main`。
- 动画：优先用 `Property Animation` / `Choreographer`，避免每帧 `invalidate()` 整屏。
- 避免使用 `WebView` / 复杂的 `ConstraintLayout` 动画 / 大圆角阴影等高开销效果。

### 2.4 电量
- 屏幕是最大耗电源，避免无谓的常亮。`WakeLock` 只能在使用期间短时持有。
- 后台任务使用 `WorkManager`（带约束条件：充电、空闲），严禁无限制轮询。
- 传感器 / 定位 / 蓝牙：用完立即释放；不要在 `onResume` 之外持有。

### 2.5 包体
- 控制 APK 体积：避免引入大型库（如完整版 Glide、Retrofit + Gson + OkHttp 全家桶）。能用平台 API 就别引依赖。
- 使用 `R8`/`ProGuard` 开启混淆与资源压缩（release 构建）。

## 3. 屏幕与 UI 规范

- 设备为横屏窄长比例（接近 16:5 或更窄），**禁止** 假设竖屏可用宽度。
- 文字最小可点击区域 ≥ 48dp；按钮高度建议 56–72dp。
- 字号：正文 ≥ 14sp，避免过小导致小屏阅读困难。
- 颜色与对比度要保证在户外强光下可读。
- 所有字符串写在 `strings.xml`，禁止硬编码中文字符串到代码 / 布局。
- 不引入 Material You / 动态取色等高开销特性。

## 4. 代码规范

- 语言：Kotlin（`compileSdk = 36`，`minSdk = 24`）。
- 开启 `viewBinding = true`；禁止使用 `findViewById` 与 `Kotlin Android Extensions`（已废弃）。
- 命名：类 `PascalCase`，函数/变量 `camelCase`，常量 `UPPER_SNAKE_CASE`。
- 协程：用 `viewModelScope` / `lifecycleScope`，不要在 `GlobalScope` 启动协程。
- 单例使用 `object` 或 `by lazy`，避免双重检查锁模板。
- 资源 ID 不允许在代码里写常量引用，统一使用 R 文件。
- 新增依赖前必须评估：是否能用 Android 平台 API 替代？体积影响？是否支持横屏？

## 5. 构建与依赖

- Gradle 配置：使用版本目录 `gradle/libs.versions.toml`，新依赖必须先在 catalog 声明再引用。
- 编译时开启 `isMinifyEnabled = true` 与资源压缩（release）。
- 禁止引入 `compose` 等大框架（本项目维持传统 View 体系，控制包体与渲染开销）。
- 单元测试与 instrumentation 测试可保留，但 CI/本地不要强制跑（设备资源紧张）。

## 6. 禁止项清单

- ❌ 主线程网络/IO/JSON/图片解码
- ❌ 内存泄漏（匿名内部类持有 Activity、静态 View/Context、未取消的协程）
- ❌ 竖屏布局、`portrait` 方向、`screenOrientation="unspecified"`
- ❌ `WebView`、大型第三方 UI 库
- ❌ 启动阶段打点 / 日志 / 网络握手
- ❌ 常亮 `WakeLock`、常驻后台服务
- ❌ 硬编码字符串、硬编码颜色（统一走 `strings.xml` / `colors.xml`）
- ❌ 在词典笔以外的设备（如平板）上未经适配的横屏布局就合入 main

## 7. 提交与改动

- 单次提交只做一件事；commit message 简要说明「为什么」而非「做了什么」。
- 涉及性能敏感改动（启动、内存、渲染）必须在 PR 描述中附上：改动点、风险点、如何验证。
- 修改 `AndroidManifest.xml` 中的方向 / 权限 / launcher 入口必须明确说明原因。
