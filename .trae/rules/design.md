# ScanPenApp UI 设计规范

> 适用设备：词典笔（窄长横屏 1 : 4）
> 屏幕方向：强制 landscape
> 单位：dp / sp

---

## 1. 配色

### 1.1 基础色板

| Token             | Hex      | 用途                       |
| ----------------- | -------- | -------------------------- |
| `bg/base`         | `#000000` | 屏幕底色                   |
| `bg/elevated-1`   | `#0A0A0A` | 极弱层级                   |
| `surface/level-1` | `#1A1A1A` | 一级表面（按钮、列表项）   |
| `surface/level-2` | `#2A2A2A` | 二级表面（选中、抽屉）     |
| `surface/level-3` | `#3A3A3A` | 三级表面（主按钮、激活态） |
| `surface/level-4` | `#4A4A4A` | 按下态                     |
| `divider/subtle`  | `#1A1A1A` | 默认分隔线                 |
| `divider/strong`  | `#2A2A2A` | 强调分隔线                 |

### 1.2 文字色

| Token            | Hex      | 用途       |
| ---------------- | -------- | ---------- |
| `text/primary`   | `#FFFFFF` | 标题、按钮 |
| `text/secondary` | `#E0E0E0` | 正文       |
| `text/tertiary`  | `#A0A0A0` | 辅助说明   |
| `text/disabled`  | `#606060` | 禁用 / 占位 |

### 1.3 状态色（克制使用，仅文字或 ≤ 8dp 圆点）

| Token           | Hex      |
| --------------- | -------- |
| `state/success` | `#3A8C5C` |
| `state/warning` | `#A07A2A` |
| `state/error`   | `#A8443A` |

### 1.4 按下态规则

- level-1 → 按下 level-2
- level-2 → 按下 level-3
- level-3 → 按下 level-4
- 选中态 = 按下态；禁用态 = level-1 + `text/disabled`，不响应按下
- **禁止** alpha 叠加，必须用 `StateListDrawable` 写死色值

### 1.5 Android 资源映射（`res/values/colors.xml`）

```xml
<resources>
    <color name="bg_base">#FF000000</color>
    <color name="bg_elevated_1">#FF0A0A0A</color>
    <color name="surface_level_1">#FF1A1A1A</color>
    <color name="surface_level_2">#FF2A2A2A</color>
    <color name="surface_level_3">#FF3A3A3A</color>
    <color name="surface_level_4">#FF4A4A4A</color>
    <color name="divider_subtle">#FF1A1A1A</color>
    <color name="divider_strong">#FF2A2A2A</color>
    <color name="text_primary">#FFFFFFFF</color>
    <color name="text_secondary">#FFE0E0E0</color>
    <color name="text_tertiary">#FFA0A0A0</color>
    <color name="text_disabled">#FF606060</color>
    <color name="state_success">#FF3A8C5C</color>
    <color name="state_warning">#FFA07A2A</color>
    <color name="state_error">#FFA8443A</color>
</resources>
```

> 严禁硬编码十六进制颜色，必须 `@color/*`。

---

## 2. 文字

- 标题 20sp Bold；副标题 17sp Medium；正文 15sp Regular（≥ 14sp）
- 辅助 13sp / 时间 14sp Medium / 按钮文案 15sp Medium
- 字体：系统 `sans-serif`；数字可启用 `tnum` 等宽

---

## 3. 间距与圆角

- 基础栅格 8dp：`4 / 8 / 12 / 16 / 20 / 24 / 32`
- 内边距：列表项 12dp、卡片 16dp、按钮 16dp
- 安全区：左右各 8dp、顶部 4dp、底部 8dp
- 圆角统一 **12dp**；头像 / 圆点用全圆

---

## 4. 1 : 4 布局

### 4.1 全局三段式

```
┌────────────────────────────────────────────────────────────┐
│ TopBar  32dp  bg_base                                      │
├──────────┬─────────────────────────────────────────────────┤
│ Sidebar  │   Content                                       │
│  ≈ 25%   │   ≈ 75%                                         │
│  ≥ 200dp │                                                 │
├──────────┴─────────────────────────────────────────────────┤
│ InputBar  56dp  surface/level-1                            │
└────────────────────────────────────────────────────────────┘
```

- **TopBar**（32dp）：左侧标题，右侧 1-2 个图标；`divider/strong` 1dp 下边线
- **Sidebar**（≈ 25% 宽，min 200dp）：纵向列表，每项 56dp；选中 level-2，未选 `bg_base`
- **Content**（≈ 75% 宽）：可滚动，padding 16dp
- **InputBar**（56dp）：底部固定，顶部圆角 12dp；附件 + 输入 + 发送

### 4.2 关键页面骨架

- **主页**：TopBar + 2×4 图标网格（120×72dp，level-1 → 按下 level-2）
- **AI 对话**：TopBar + 消息气泡列表 + InputBar；左抽屉「历史」右抽屉「模型」（各 ≈ 30% 宽）
- **设置**：TopBar（返回 + 标题）+ 列表（每项 56dp，bg_base → 按下 level-1）

### 4.3 布局性能

- 嵌套 ≤ 4 层，复杂页用 `ConstraintLayout`
- 列表用 `RecyclerView` + `ViewHolder`；`onBindViewHolder` 中不分配对象 / 解码图片

---

## 5. 关键组件

### 5.1 按钮（圆角 12dp）

| 变体      | 高度  | default | pressed | 文字            |
| --------- | ----- | ------- | ------- | --------------- |
| Primary   | 56dp  | level-3 | level-4 | `text/primary`  |
| Secondary | 56dp  | level-1 | level-2 | `text/primary`  |
| Tertiary  | 40dp  | bg_base | level-1 | `text/secondary`|
| Icon      | 40×40 | bg_base | level-1 | 图标同文字      |
| Disabled  | 56dp  | level-1 | —       | `text/disabled` |

- 最小可点击 48×48dp；图标按钮 hitRect 可外扩 8dp

### 5.2 输入框

- 高度 48dp（多行最大 96dp）；背景 level-1，聚焦 level-2
- 文字 15sp `text/secondary`；占位 `text/tertiary`；圆角 12dp；内边距 12dp

### 5.3 列表项 / 卡片

- 行高 56dp（单行）/ 72dp（双行）；左右 padding 16dp
- 列表项：bg_base → 按下 level-1 → 选中 level-2
- 卡片：level-1 → 按下 level-2

### 5.4 消息气泡

- AI：level-1；用户：level-2；圆角 12dp；padding 12dp；间距 12dp
- AI 左对齐，用户右对齐

### 5.5 抽屉 / 对话框

- 背景 level-2；抽屉宽 ≈ 屏宽 30%（≤ 320dp）；圆角仅朝向屏幕中心
- 模态对话框：level-3，圆角 12dp，居中，最大 280dp

### 5.6 图标 / 分割线

- 图标 WebP 单色，20/24/16dp；通过 `tint` 着色
- 分割线 1dp，subtle / strong 两档；列表项内左边距 16dp

---

## 6. 主题

`res/values/themes.xml`（values-night 与 values 内容完全相同）：

```xml
<style name="Theme.ScanPenApp" parent="Theme.MaterialComponents.NoActionBar">
    <item name="android:windowBackground">@color/bg_base</item>
    <item name="android:statusBarColor">@color/bg_base</item>
    <item name="android:navigationBarColor">@color/bg_base</item>
    <item name="colorPrimary">@color/surface_level_3</item>
    <item name="colorOnPrimary">@color/text_primary</item>
    <item name="colorSurface">@color/surface_level_1</item>
    <item name="android:textColorPrimary">@color/text_primary</item>
    <item name="android:textColorSecondary">@color/text_secondary</item>
    <item name="android:textColorHint">@color/text_tertiary</item>
</style>
```

---

## 7. 检查清单

- [ ] 背景统一 `@color/bg_base`，无硬编码 `#000000`
- [ ] 按下态「更亮一档」
- [ ] 正文 ≥ 14sp，文字最低 `text/secondary`
- [ ] 可点击 ≥ 48×48dp
- [ ] 主 / 次按钮 56dp，圆角 12dp
- [ ] 间距走 8dp 栅格，嵌套 ≤ 4 层
- [ ] 颜色全部 `@color/*`、字符串全部 `@string/*`
- [ ] 列表用 `RecyclerView` + ViewHolder
- [ ] 主线程无 IO / JSON / 图片解码 / 网络
- [ ] 全站仅 `Theme.ScanPenApp`，无浅色分支

---

## 8. 版本

| 版本 | 日期       | 变更                         |
| ---- | ---------- | ---------------------------- |
| 1.0  | 2026-06-27 | 初版                         |
| 1.1  | 2026-06-27 | 精简内容，聚焦配色与布局     |
