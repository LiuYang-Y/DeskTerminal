package com.qingke.desk.shell

/**
 * 侧栏条目 id。集中定义，避免 :shell 各处散落字符串常量。
 *
 * 2026-10-12 由 DockIds 改名：底栏已经立起来做成侧栏。
 * 前缀仍是 "dock:" —— 它是**持久化/日志里的稳定标识**，改名会让旧的调试命令失效，
 * 收益却只是好看，不值当。
 */
object ShellIds {
    const val HOME: String = "dock:home"
    const val CLOCK: String = "dock:clock"
    const val MUSIC: String = "dock:music"

    /**
     * 智能家居。**2026-10-12 从默认条目里暂时去掉**，但 id 与处理分支保留：
     * 侧栏竖排 5 项 × 64dp 才能塞进 361dp 屏幕高（6 项需要 384dp，放不下），
     * 与其把 6 项都压到 48~54dp、自损"手臂距离外的大触控"这条核心观感，
     * 不如砍掉一个**尚未实现**的入口。等它真的做出来，再决定是加回 6 项
     * （那时就得重新讨论触控尺寸）还是替换掉别的一项。
     */
    const val SMART_HOME: String = "dock:smarthome"

    const val PROJECTION: String = "dock:projection"

    /**
     * 地图。走 :map 的高德 SDK（MapView 直接挂进 Compose），
     * 不再通过 :hosting 的虚拟屏把高德 App 整个搬过来。
     */
    const val MAP: String = "dock:hosting"
}
