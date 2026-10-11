package com.qingke.desk

import android.app.Application
import com.qingke.desk.map.AMapBootstrap

/**
 * 应用入口。目前只持有 DI 图，不在这里做初始化副作用
 * （网络锁、前台服务等都要等连接真正建立时再拉起）。
 *
 * 例外是 [AMapBootstrap]：它必须在这里跑，而且**只做两件不碰渲染的事** ——
 * 打开 debug 的文件日志、把用户上次的隐私同意补报给高德 SDK。
 * 地图引擎本身仍然要等用户同意之后、由 MapPane 挂 MapView 时才初始化，
 * 提前到 Application 里做就等于绕过了隐私闸门。
 */
class DeskApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // bind() 内部会碰到 DeskGraph 对象，顺带完成图的初始化 —— 不需要再单独 touch 一次。
        DeskGraph.bind(this)
        AMapBootstrap.init(this)
    }
}
