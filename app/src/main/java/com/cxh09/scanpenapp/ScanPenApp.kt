package com.cxh09.scanpenapp

import android.app.Application
import com.cxh09.scanpenapp.ai.FullscreenContentCache
import com.cxh09.scanpenapp.ai.ModelConfigStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口。
 *
 * 当前唯一的「启动期后台工作」是预热 [ModelConfigStore] 的内存缓存 + 清理
 * [FullscreenContentCache] 的孤儿临时文件：
 * - 词典笔内存 / 算力紧张，避免在 UI 首次访问（onCreate / onResume）才做 JSON 解析。
 * - 预热用独立 [appScope] 跑在 [Dispatchers.IO]，与 UI 生命周期解耦，进程退出时随 SupervisorJob 自动结束。
 * - 不做网络/反射/图片解码（项目规则 §2.1）。
 */
class ScanPenApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 启动期后台任务：IO 调度器，避免主线程阻塞
        appScope.launch {
            ModelConfigStore(this@ScanPenApp).ensureLoaded()
            // 清理 AI 全屏页 1 小时以上的孤儿临时文件（防磁盘占满 / 数据残留）
            FullscreenContentCache.cleanupOrphans(this@ScanPenApp)
        }
    }
}
