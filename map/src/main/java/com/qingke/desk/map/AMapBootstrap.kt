package com.qingke.desk.map

import android.content.Context

/**
 * :map 的初始化入口。**应用启动时调用一次**（见 DeskApplication）。
 *
 * 只做两件事，且都不碰 SDK 的渲染：
 *   1. 打开 debug 构建的文件日志（release 构建里整段被剔除）；
 *   2. 如果用户上次已经同意过隐私政策，把合规声明**补报给 SDK**
 *      —— updatePrivacy* 的状态只活在进程内，冷启动不补报的话第一帧地图是白的。
 *
 * 这里刻意不初始化地图引擎（MapsInitializer.initialize）：
 * 那一步必须在用户同意之后，由 [MapPane] 在挂上 MapView 时顺带完成。
 * 在 Application 里提前初始化，等于绕过隐私闸门。
 */
object AMapBootstrap {

    @Volatile
    private var done = false

    fun init(context: Context) {
        if (done) return
        done = true
        MapLog.init(context)
        AMapPrivacy.applyIfAgreed(context)
        MapLog.i("bootstrap: privacy agreed=${AMapPrivacy.isAgreed(context)}")
    }
}
