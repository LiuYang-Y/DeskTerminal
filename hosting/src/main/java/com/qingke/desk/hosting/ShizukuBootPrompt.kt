package com.qingke.desk.hosting

import android.content.Context
import android.os.SystemClock

/**
 * 「重启手机后第一次打开应用」的一次性提示闸门。
 *
 * 要解决的问题很具体：非 root 设备上 Shizuku 靠无线调试启动，**每次重启手机都会停**。
 * 用户重启后第一次打开本应用时应该被告知一次，否则地图/投屏会静默不可用；
 * 但同一次开机里反复弹就是骚扰。
 *
 * 判定口径：
 *   · 用 [bootId]（本次开机的时刻）当"开机周期"的 id —— 重启后它必然变化；
 *   · 每个开机周期最多提示一次，提示完就记账；
 *   · [ShizukuStatus.READY] 时不提示 —— 服务好好的没什么可提示的。
 *
 * 只记"是否已经提示过"，不记"上次状态是什么"：状态随时在变，
 * 每次读现查即可，存下来只会过期。
 */
object ShizukuBootPrompt {

    private const val PREFS = "desk_shizuku_boot"
    private const val KEY_LAST_BOOT = "last_boot_ms"
    private const val KEY_PROMPTED_BOOT = "prompted_boot_ms"
    private const val NO_BOOT = -1L

    /**
     * 本次开机的时刻（epoch ms）。
     *
     * 用 `当前时刻 - 开机至今的运行时长` 反推开机点；[SystemClock.elapsedRealtime]
     * 计入深度睡眠，所以它在一整个开机周期内是稳定的钟。
     * 系统时间被自动校时拨动时这个值会漂一点，但只影响"算不算同一个开机周期"，
     * 最坏情况是多提示或少提示一次，不值得为此引入更复杂的机制。
     */
    fun bootId(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * 判断这一刻该不该提示。**调用即记账**，同一个开机周期内第二次调用恒为 false。
     *
     * 返回 false 的三种情况：状态可用、本周期已经提示过、以及（隐含的）
     * 首次安装后还没到需要提示的状态。
     */
    fun consume(context: Context, status: ShizukuStatus): Boolean {
        if (status == ShizukuStatus.READY) return false

        val boot = bootId()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        // 一律用 commit() 而不是 apply()。
        //
        // 这不是洁癖：apply() 是"先改内存、再异步落盘"，而这两条写入发生在
        // 应用**刚冷启动**的那一刻 —— 用户完全可能看一眼就划掉，或者系统顺手回收进程，
        // 结果盘上什么都没留下，下次开机又提示一遍，"只提示一次"就失效了。
        // 每次开机只写一次、几十字节，同步落盘的代价可以忽略。
        if (prefs.getLong(KEY_LAST_BOOT, NO_BOOT) != boot) {
            prefs.edit()
                .putLong(KEY_LAST_BOOT, boot)
                .remove(KEY_PROMPTED_BOOT)
                .commit()
        }

        if (prefs.getLong(KEY_PROMPTED_BOOT, NO_BOOT) == boot) return false
        prefs.edit().putLong(KEY_PROMPTED_BOOT, boot).commit()
        return true
    }

    /**
     * 仅供 debug 预览/联调：清掉记账，好让"重启后提示"这条路径能反复走到。
     * 正式代码里没有调用点。
     */
    fun resetForDebug(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_BOOT)
            .remove(KEY_PROMPTED_BOOT)
            .commit()
    }
}
