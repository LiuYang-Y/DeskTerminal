package com.qingke.desk.shell

/**
 * 底栏条目 id。集中定义，避免 :shell 各处散落字符串常量。
 */
object DockIds {
    const val HOME: String = "dock:home"
    const val CLOCK: String = "dock:clock"
    const val MUSIC: String = "dock:music"
    const val SMART_HOME: String = "dock:smarthome"
    const val PROJECTION: String = "dock:projection"
    /**
     * 应用托管（:hosting）。把第三方应用画到自建虚拟屏上，再显示进主区域。
     * 本轮只托管高德地图。
     */
    const val HOSTING: String = "dock:hosting"
}
