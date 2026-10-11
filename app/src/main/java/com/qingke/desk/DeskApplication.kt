package com.qingke.desk

import android.app.Application

/**
 * 应用入口。目前只持有 DI 图，不在这里做初始化副作用
 * （网络锁、前台服务等都要等连接真正建立时再拉起）。
 */
class DeskApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // bind() 内部会碰到 DeskGraph 对象，顺带完成图的初始化 —— 不需要再单独 touch 一次。
        DeskGraph.bind(this)
    }
}
