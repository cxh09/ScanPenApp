# 修复 AI 聊天页面长文本滚动卡顿 / 跳跃

## 问题描述

当 AI 返回长文本时，消息输出框会出现视觉跳跃：

* 有时输出框顶部突然跳到聊天页面顶部（即 ScrollView 滚到顶部）

* 然后下一帧又恢复正常（滚到底部）

* 这种交替跳跃导致阅读体验很差

## 根因分析（基于代码探索）

### 1. 双层 `post` 导致的帧延迟

流式更新时，每个 chunk 触发：

```
IO 线程收到 chunk
  → thinkingBubble.post {                  // post #1 → 下一帧执行
        thinkingBubble.text = snapshot
        scrollMessagesToBottom()            // 内部又调用了 svMessages.post { fullScroll }
    }
```

`scrollMessagesToBottom()` 内部是：

```kotlin
binding.svMessages.post {                   // post #2 → 再下一帧执行
    binding.svMessages.fullScroll(View.FOCUS_DOWN)
}
```

**后果**：

* 第 N 帧：设置 text（触发布局请求）

* 第 N+1 帧：布局完成，但 `fullScroll` 才刚被 post

* 第 N+2 帧：`fullScroll` 执行，滚动到底部

用户看到的是：第 N+1 帧时，新内容已经显示但 scroll 还没到位 → 视觉上内容在错误位置 → 第 N+2 帧突然跳回底部。

### 2. 没有节流（throttle）

每个 chunk（即使只有 1-2 个 token）都触发一次 `post` + text 更新 + scroll。当流式返回速度很快时，主线程被大量 Runnable 淹没，UI 更新频繁触发 layout pass，加剧卡顿。

### 3. `fillViewport="true"` + `wrap_content` 的相互作用

[activity\_ask\_ai.xml](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/res/layout/activity_ask_ai.xml#L245-L262) 中 `svMessages` 设置了 `fillViewport="true"`，但内部的 `llMessages` 是 `wrap_content` 高度。当消息内容高度变化时，`fillViewport` 会影响 ScrollView 的滚动范围计算，在快速更新时可能产生错误的 scroll 位置。

## 修改方案

### 修改 1：流式更新添加节流（核心修复）

[AskAiActivity.kt](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt)

**目标**：不要每收到一个 chunk 就更新 UI，改为以固定间隔（\~50ms）批量刷新。

改动点：

* 在 `onSendClicked()` 的流式协程中引入一个 `Channel`（或 `MutableStateFlow` + 定时器）来缓冲 chunk

* 或者更简单的方案：用一个 `Handler` + `postDelayed` 的定时任务，每隔 \~50ms 取一次 `responseBuffer` 的快照更新 UI

* 取消旧的定时任务再重新 post，实现 debounce 效果

具体实现方式（二选一）：

**方案 A（推荐，改动小）**：引入 `Handler` + `Runnable` 做节流

```kotlin
val uiHandler = Handler(Looper.getMainLooper())
val uiUpdateRunnable = Runnable {
    val snapshot = responseBuffer.toString()
    thinkingBubble.text = snapshot
    // 使用 smoothScrollTo 替代 fullScroll，更平滑
    binding.svMessages.smoothScrollTo(0, binding.llMessages.bottom)
}
```

在每次收到 chunk 时，取消之前的 post 并重新 postDelayed(this, 50L)。这样只有最后一个 post 会执行，减少 UI 更新频率。

**方案 B**：用 `kotlinx.coroutines.flow` 做节流，但引入新依赖不符合项目规范（低依赖原则），所以不采用。

### 修改 2：修复 scroll 机制

[AskAiActivity.kt](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt)

**目标**：将 text 更新和 scroll 合并到同一个帧，消除中间的错误位置。

改动点：

* 修改 `scrollMessagesToBottom()` 方法，不再使用嵌套的 `svMessages.post`

* 改为直接在 UI 线程计算 scroll 位置并设置

```kotlin
private fun scrollMessagesToBottom() {
    binding.svMessages.post {
        binding.svMessages.fullScroll(View.FOCUS_DOWN)
    }
}
```

改为更可靠的方式：

```kotlin
private fun scrollMessagesToBottom() {
    binding.svMessages.post {
        val bottom = binding.llMessages.bottom
        if (bottom > binding.svMessages.height) {
            binding.svMessages.scrollTo(0, bottom - binding.svMessages.height)
        }
    }
}
```

这样即使在 layout 还没完全完成时，也能确保 scroll 到内容的底部位置。

而在流式更新的 Runnable 中，text 更新和 scroll 放到同一个 post 里：

```kotlin
binding.svMessages.post {
    thinkingBubble.text = snapshot
    val bottom = binding.llMessages.bottom
    if (bottom > binding.svMessages.height) {
        binding.svMessages.scrollTo(0, bottom - binding.svMessages.height)
    }
}
```

注意：这里用 `binding.svMessages.post` 替代 `thinkingBubble.post`，因为 svMessages 的 post 能保证在其自身的 layout pass 之后执行。text 更新触发的 layout 会在同一帧完成（因为已经在 UI 线程），然后 `scrollTo` 立即生效。

### 修改 3：移除 dead code

[AskAiActivity.kt](file:///c:/Users/Administrator/AndroidStudioProjects/ScanPenApp/app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt#L233-L236)

删除未使用的 `updateAiBubble()` 方法，避免混淆。

## 涉及文件

| 文件                                                        | 操作 | 说明                               |
| --------------------------------------------------------- | -- | -------------------------------- |
| `app/src/main/java/com/cxh09/scanpenapp/AskAiActivity.kt` | 修改 | 节流流式更新、修复 scroll 机制、移除 dead code |

## 验证方式

1. 发送一条长文本查询（如「Flutter 在 1GB 设备上的优化方案」），观察流式输出时是否还有跳跃
2. 快速发送多条短文本，观察每次响应是否平滑滚到底部
3. 在低端设备或模拟器上测试（降低 CPU 频率模拟词典笔环境）
4. 检查 Android Studio Profiler 中主线程消息队列长度是否改善

