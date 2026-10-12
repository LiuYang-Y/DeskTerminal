package com.qingke.desk.shell

/**
 * 验证相关的**类型与常量**。它们留在 main 源码集，因为线上代码也在用：
 *  · [PageRequest] 是 `HomeScreen` 的参数类型（没有验证驱动时恒为 null）；
 *  · [FULLSCREEN_MAP_TAG] 是地图页容器上的 testTag，UI 测试按它找元素。
 *
 * **验证驱动本身（`ValidationDriver`）不在这里** —— 它按构建类型分成两份，
 * 分别在 `src/debug` 与 `src/release`（见 `ValidationDriver.kt` 的说明）。
 * 这样 release 包的 dex 里连那条广播 action 字符串都不存在。
 */

/**
 * Pager 翻页请求。带 [seq] 是为了让"连按两次同一页"也是两个不同的事件 ——
 * 只传页码的话，第二次请求与前一次的 state 相等，`LaunchedEffect` 不会重启。
 */
data class PageRequest(val page: Int, val seq: Int)

/** 全屏地图页容器的测试标签（androidTest 用它断言"真的进到全屏页了"）。 */
internal const val FULLSCREEN_MAP_TAG = "fullscreen-map"
