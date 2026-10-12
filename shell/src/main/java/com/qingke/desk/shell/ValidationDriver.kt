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
 * **只用于 debug 验证**的壳层驱动。
 *
 * 为什么需要它（2026-10-12 实测）：这台基准机是 production 构建，
 * `adb shell input tap/swipe/keyevent` 一律被系统拒绝 ——
 *   java.lang.SecurityException: Injecting to another application requires INJECT_EVENTS permission
 * 而 `adb root` 也拿不到（`adbd cannot run as root in production builds`）。
 * 于是"翻页 20 次""首页↔地图页切 10 次""撤回同意再同意""切侧栏"这类
 * **状态驱动**的验证没法用注入完成。
 *
 * 做法：注册一个广播接收器，把命令通过 Flow 交给 [DeskRoot]，
 * DeskRoot 用与真实点击**同一批回调**去改状态（`screen = MAP`、滚 Pager、
 * `AMapPrivacy.revoke`…）。所以它验证的是真实的状态机与地图生命周期，
 * 唯一验不到的是"手指落在屏幕上时手势归谁" —— 那一条交给
 * `app/src/androidTest` 的 Compose UI 测试（进程内注入，不受 INJECT_EVENTS 限制）。
 *
 * 用法：
 * ```
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd page:1
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd screen:map
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd agree
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd revoke
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd side:right
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd daylight:night
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd daylight:day
 * adb shell am broadcast -a com.qingke.desk.VALIDATION --es cmd daylight:auto
 * ```
 *
 * `daylight:*` 是**昼夜样式的探针注入**：改的是 `Daylight` 的日出日落，不是系统时间 ——
 * 改系统时间会连带弄坏地图 SDK 的 TLS 校时（证书校验依赖正确时间），
 * 结果"验夜间样式"变成"验地图白屏"（2026-10-12 踩过）。
 *
 * release 构建里 [register] 直接返回：**接收器根本不注册**，
 * 外部无法从任何渠道驱动 release 包。
 */
object ValidationDriver {

    const val ACTION = "com.qingke.desk.VALIDATION"
    private const val TAG = "DeskShell"

    private val _commands = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val commands: SharedFlow<String> = _commands.asSharedFlow()

    @Volatile
    private var registered = false

    fun register(context: Context) {
        if (!BuildConfig.DEBUG) return
        if (registered) return
        registered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val cmd = intent.getStringExtra("cmd") ?: return
                Log.i(TAG, "validation cmd=$cmd")
                _commands.tryEmit(cmd)
            }
        }
        val filter = IntentFilter(ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 必须 EXPORTED：命令是 `adb shell am broadcast` 发来的，属于外部 uid。
            // 只在 debug 构建注册，所以这不构成 release 面的暴露。
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        Log.i(TAG, "validation driver registered")
    }
}

/**
 * Pager 翻页请求。带 [seq] 是为了让"连按两次同一页"也是两个不同的事件 ——
 * 只传页码的话，第二次请求与前一次的 state 相等，`LaunchedEffect` 不会重启。
 */
data class PageRequest(val page: Int, val seq: Int)

/** 全屏地图页容器的测试标签（androidTest 用它断言"真的进到全屏页了"）。 */
internal const val FULLSCREEN_MAP_TAG = "fullscreen-map"
