package com.qingke.desk.hosting

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * Shizuku 的四种状态。**这四种在界面上是四套不同的提示**，不能合并成"不可用"一句话：
 * 用户能做的事完全不同（装 App / 去启动 / 点授权 / 直接用）。
 */
enum class ShizukuStatus {
    /** 设备上根本没装 Shizuku。 */
    NOT_INSTALLED,

    /** 装了，但服务没跑起来 —— 非 root 设备**每次重启手机后都会回到这个状态**。 */
    NOT_RUNNING,

    /** 服务在跑，但本应用没被授权。需要用户在弹窗里点"允许"。 */
    NOT_AUTHORIZED,

    /** 可用。 */
    READY,
}

/**
 * Shizuku 状态与授权的唯一切入口。
 *
 * 设计上刻意**不替用户做任何决定**：
 *   · 不自动调 requestPermission（弹窗必须由用户自己看、自己点）；
 *   · 不自动跳 Shizuku App（只在提示页里给按钮和步骤说明）；
 *   · 每次读取都是现查，不缓存结论 —— 用户随时可能在别处把服务启起来。
 */
object ShizukuGate {

    const val SHIZUKU_PACKAGE: String = "moe.shizuku.privileged.api"

    /** 任意自定义值即可，只用于区分多个请求。 */
    const val PERMISSION_REQUEST_CODE: Int = 8801

    @Suppress("DEPRECATION")
    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun status(context: Context): ShizukuStatus {
        if (!isInstalled(context)) return ShizukuStatus.NOT_INSTALLED

        // pingBinder() 是"服务端活着吗"的唯一判据：它检查的是 binder 有没有断，
        // 跟授权与否无关。binder 没到（服务没启动）时返回 false。
        val alive = runCatching { Shizuku.pingBinder() }.onFailure {
            HostLog.w("pingBinder threw ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
        if (!alive) return ShizukuStatus.NOT_RUNNING

        // 注意：未授权时 checkSelfPermission() 会抛 SecurityException 而不是返回 DENIED，
        // 所以要包 runCatching，不能只比返回值。
        val granted = runCatching { Shizuku.checkSelfPermission() }
            .getOrDefault(PackageManager.PERMISSION_DENIED)
        return if (granted == PackageManager.PERMISSION_GRANTED) {
            ShizukuStatus.READY
        } else {
            ShizukuStatus.NOT_AUTHORIZED
        }
    }

    /**
     * 发起授权请求。**只负责把弹窗推出来**，结果由调用方通过
     * [Shizuku.addRequestPermissionResultListener] 收到 —— 弹窗里的按钮用户自己点。
     *
     * 未启动 / 未安装时调它没有任何意义（也不会弹窗），所以调用方必须先查 [status]。
     */
    fun requestPermission() {
        runCatching {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        }.onFailure {
            HostLog.e("requestPermission failed", it)
        }
    }

    /**
     * 服务端身份。只是给提示页用的展示信息 —— adb 与 root 两种模式的
     * 权限集合和"重启后要不要重新激活"不一样，值得让用户看见。
     */
    fun describeIdentity(): String = runCatching {
        val uid = Shizuku.getUid()
        when {
            uid == 0 -> "root"
            uid == 2000 -> "adb（无线调试）"
            else -> "uid=$uid"
        }
    }.getOrDefault("未知")
}
