package com.qingke.desk.hosting

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态的观察点。
 *
 * 存在的理由很实际：**壳层（:shell）不该直接碰 Shizuku 的 API**。
 * rikka 那套类（[Shizuku]、授权结果监听器）只在 :hosting 里出现，
 * 壳层拿到的只是一个 StateFlow —— 这样"谁懂 Shizuku"这件事有唯一归属，
 * 换掉 Shizuku 实现时改动不会漫到壳层去。
 *
 * 状态一律现查、不落盘：用户随时可能在别处把服务启停。
 * 唯一被记住的是"本次开机的提示是否已经给过"（见 [ShizukuBootPrompt]）。
 */
class ShizukuStatusMonitor(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(ShizukuGate.status(appContext))
    val state: StateFlow<ShizukuStatus> = _state.asStateFlow()

    /**
     * 授权弹窗的结果。用户在系统弹窗里点完「允许」才会回调到这里 ——
     * 没有它，界面会一直停在"未授权"，即使授权其实已经给了。
     */
    private val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        HostLog.i("shizuku permission result: code=$requestCode granted=$grantResult")
        refresh()
    }

    fun start() {
        Shizuku.addRequestPermissionResultListener(listener)
        refresh()
    }

    fun stop() {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    /** 重新现查一次。回到前台、点「重新检测」、授权结果回来都要调它。 */
    fun refresh() {
        _state.value = ShizukuGate.status(appContext)
    }

    /**
     * 「重启后第一次打开应用」的一次性提示闸门。调用即记账，
     * 同一个开机周期内第二次调用恒为 false。
     */
    fun consumeBootPrompt(): Boolean = ShizukuBootPrompt.consume(appContext, _state.value)
}
