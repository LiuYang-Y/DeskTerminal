package com.qingke.desk.hosting

import android.content.Context
import com.qingke.desk.hosting.BuildConfig

/**
 * **只服务于 debug 验收**的状态覆盖。发布构建里它是完全的空操作。
 *
 * 为什么需要它：这台设备上 Shizuku 是以无线调试方式在跑的，而"把服务停下来"
 * 这一步只能由用户在 Shizuku 应用里点「启动／停止」完成；一旦停掉，
 * 重新激活又要走一次配对，代价太大 —— 真机验收不能靠真的把它弄停。
 * 所以留一个只认 debug 的开关，让"未运行 / 未授权"这两条分支能在真机上
 * 被真正走到、被截图，而不是只停留在代码里没跑过。
 *
 * 值存在 SharedPreferences 而不是进程内存里，是为了**活过 force-stop**：
 * 验收"重启手机后第一次打开应用会提示一次"这条路径时必须冷启动，
 * 进程内的变量在一次冷启动后就没了。
 *
 * 发布构建里 [BuildConfig.DEBUG] 恒为 false，[read] 直接返回 null、[write] 直接返回，
 * 连 prefs 文件都不会被创建。
 */
object ShizukuDebugOverride {

    private const val PREFS = "desk_shizuku_debug"
    private const val KEY_FORCED = "forced_status"

    /**
     * 写入（[status] 为 null 表示清除）。非 debug 构建直接忽略。
     */
    fun write(context: Context, status: ShizukuStatus?) {
        if (!BuildConfig.DEBUG) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (status == null) {
            prefs.edit().remove(KEY_FORCED).apply()
        } else {
            prefs.edit().putString(KEY_FORCED, status.name).apply()
        }
        HostLog.i("debug shizuku override -> ${status?.name ?: "off"}")
    }

    /** 读取。非 debug 构建恒为 null。 */
    fun read(context: Context): ShizukuStatus? {
        if (!BuildConfig.DEBUG) return null
        val name = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FORCED, null) ?: return null
        return runCatching { ShizukuStatus.valueOf(name) }.getOrNull()
    }
}
