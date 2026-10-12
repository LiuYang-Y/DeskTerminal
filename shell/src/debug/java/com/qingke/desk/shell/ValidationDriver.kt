package com.qingke.desk.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * **debug 构建专用**的壳层验证驱动。
 *
 * 这个文件位于 `src/debug`，只在 debug 变体参与编译 —— release 变体看到的是
 * `src/release` 里同名同包的另一个 [ValidationDriver]（空实现）。
 * 两个源码集不会同时编译，所以不存在重复类的问题。
 *
 * ## 为什么需要它（2026-10-12 实测）
 *
 * 这台基准机是 production 构建，
 * `adb shell input tap/swipe/keyevent` 一律被系统拒绝：
 * ```
 * java.lang.SecurityException: Injecting to another application requires INJECT_EVENTS permission
 * ```
 * 而 `adb root` 也拿不到（`adbd cannot run as root in production builds`）。
 * 于是"翻页 20 次""首页↔地图页切 10 次""撤回同意再同意""切侧栏""注入昼夜"这类
 * **状态驱动**的验证没法用注入完成。
 *
 * 做法：注册一个广播接收器，把命令通过 Flow 交给 [DeskRoot]，
 * DeskRoot 用与真实点击**同一批回调**去改状态（`screen = MAP`、滚 Pager、
 * `AMapPrivacy.revoke`、`Daylight.publish`…）。所以它验证的是真实的状态机与
 * 地图生命周期，唯一验不到的是"手指落在屏幕上时手势归谁" —— 那一条交给
 * `app/src/androidTest` 的 Compose UI 测试（进程内注入，不受 INJECT_EVENTS 限制）。
 *
 * ## 为什么把接收器整份搬到 src/debug，而不是留一句 `if (!BuildConfig.DEBUG) return`
 *
 * 那种写法在 release 包里**仍然带着完整的接收器实现**（只是不执行）：反编译出来
 * 有 `BroadcastReceiver`、有那条 `com.qingke.desk.VALIDATION` action 字符串。
 * "验证驱动只在 debug 存在"就变成了"它存在但没跑" —— 不好核实，也不好向别人交代。
 * 拆源码集之后 release 的 dex 里**根本没有那句 action**，可以用字符串直接证明。
 *
 * ## 用法
 *
 * ```
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd page:1
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd screen:map
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd agree
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd revoke
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd side:right
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd settings:open
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd daylight:night
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd daylight:day
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd daylight:auto
 * ```
 *
 * `daylight:*` 是**昼夜样式的探针注入**：改的是 `Daylight` 的日出日落，不是系统时间 ——
 * 改系统时间会连带弄坏地图 SDK 的 TLS 校时（证书校验依赖正确时间），
 * 结果"验夜间样式"变成"验地图白屏"（2026-10-12 踩过）。
 */
object ValidationDriver {

    /**
     * 广播 action。**private** 而不是 public：它只在这个文件里用，
     * 而任何 public 常量都会连同字符串一起出现在最终产物里。
     * （release 版本里连这个常量都没有 —— 见 src/release 的同名文件。）
     */
    private const val ACTION = "com.qingke.desk.VALIDATION"

    private const val TAG = "DeskShell"

    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val commands: SharedFlow<String> = _commands.asSharedFlow()

    @Volatile
    private var receiver: BroadcastReceiver? = null

    fun register(context: Context) {
        if (receiver != null) return
        val target = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: return
                Log.i(TAG, "validation cmd=$cmd")
                _commands.tryEmit(cmd)
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 必须 EXPORTED：命令是 `adb shell am broadcast` 发来的，属于外部 uid。
            // 这只存在于 debug 构建 —— release 变体里没有这个接收器可以注册。
            context.registerReceiver(target, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(target, filter)
        }
        receiver = target
        Log.i(TAG, "validation driver registered")
    }
}
