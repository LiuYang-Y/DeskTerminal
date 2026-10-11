package com.qingke.desk.hosting

/**
 * 托管流程的状态。**每个状态在界面上都对应一种确定的画面**：
 * 要么能看到被托管的应用，要么看到一个说清楚"为什么看不到、该怎么办"的提示页。
 * 不允许出现"黑屏但没有任何说明"。
 */
sealed interface HostState {

    /** 还没开始（用户没点过地图入口，或刚关闭）。 */
    data object Idle : HostState

    /** 前置条件不满足，被挡住了。reason 决定提示页的内容。 */
    data class Blocked(val reason: BlockReason) : HostState

    /** 正在建屏 / 拉起应用。attempt 从 1 开始。 */
    data class Starting(val displayId: Int? = null, val attempt: Int = 1) : HostState

    /** 正常运行。displayId 是当前虚拟屏的 DisplayManager id。 */
    data class Running(val displayId: Int, val attempt: Int = 1) : HostState

    /** 重试耗尽，彻底失败。界面显示原因后回首页。 */
    data class Failed(val reason: String) : HostState
}

/**
 * 被挡住的原因。
 *
 * 分成这么细是因为**用户能做的补救动作完全不同**：
 * 装 App、去启动服务、点授权弹窗 —— 混成一句"Shizuku 不可用"等于没说。
 */
sealed interface BlockReason {
    data object ShizukuNotInstalled : BlockReason
    data object ShizukuNotRunning : BlockReason
    data object ShizukuNotAuthorized : BlockReason
    data class TargetMissing(val label: String, val packageName: String) : BlockReason
}
