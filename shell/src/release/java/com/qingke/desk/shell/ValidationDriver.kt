package com.qingke.desk.shell

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * release 构建里的验证驱动：**什么都不做**。
 *
 * 真实的实现只在 :shell 的 debug 源码集（`src/debug/java/com/qingke/desk/shell/ValidationDriver.kt`）：
 * 它注册一个广播接收器，让 `adb shell am broadcast` 能驱动界面做状态验证。
 *
 * 这个文件存在的唯一理由，是让 [DeskRoot] 里那句 `ValidationDriver.register(context)`
 * 在 release 变体下也能编译通过。同包同名的两份实现分属**不同的 buildType 源码集**，
 * 任一变体只会看到其中一个，所以不存在重复类。
 *
 * 里面**没有任何字符串、没有任何接收器** —— 不是"注册了但不响应"，
 * 而是这条链路在 release 产物里根本不存在。可以用一行命令核实：
 * ```
 * unzip -p app-release-unsigned.apk classes.dex | strings | grep com.qingke.desk.VALIDATION
 * ```
 * debug 包能搜到、release 包搜不到（2026-10-12 实测，见
 * docs/2026-10-12-debug-only-audit.md）。
 */
object ValidationDriver {

    /** 永远不发射任何命令。类型与 debug 版一致，所以 [DeskRoot] 的收集代码两边通用。 */
    val commands: SharedFlow<String> = MutableSharedFlow<String>().asSharedFlow()

    @Suppress("UNUSED_PARAMETER")
    fun register(context: Context) = Unit
}
