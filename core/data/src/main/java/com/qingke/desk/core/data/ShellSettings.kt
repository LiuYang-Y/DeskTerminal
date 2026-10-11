package com.qingke.desk.core.data

import android.content.Context

/**
 * 侧栏在屏幕的哪一侧。
 *
 * 之所以做成可切换：同一台机器可能左手或右手更顺，也可能被摆在车里某个固定朝向
 * （那时"靠近驾驶员的一侧"取决于装法，写死一侧就是错的）。
 */
enum class SidebarSide {
    LEFT,
    RIGHT,
    ;

    /** 切到另一侧。 */
    fun flipped(): SidebarSide = if (this == LEFT) RIGHT else LEFT
}

/**
 * 壳层的持久设置。目前只有"侧栏在哪一侧"一项。
 *
 * 用 SharedPreferences 而不是 DataStore：全工程只有这一个开关要存，
 * 为它引入一条协程 + Proto 的链路不划算（:hosting 的 ShizukuBootPrompt、
 * :map 的 AMapPrivacy 也都是这个口径，`apply()` 即可，不阻塞主线程写盘）。
 *
 * 注意这里用 [Context.getApplicationContext] 之外的 context 也安全 ——
 * 取的是 applicationContext 的 prefs，不持有 Activity。
 */
object ShellSettings {

    private const val PREFS_NAME = "desk_shell"
    private const val KEY_SIDEBAR_SIDE = "sidebar_side"

    /** 默认在左：基准机横屏时挖孔在左侧，侧栏贴左能把挖孔条一起吃掉。 */
    private val DEFAULT_SIDE = SidebarSide.LEFT

    fun sidebarSide(context: Context): SidebarSide {
        val raw = prefs(context).getString(KEY_SIDEBAR_SIDE, null) ?: return DEFAULT_SIDE
        return runCatching { SidebarSide.valueOf(raw) }.getOrDefault(DEFAULT_SIDE)
    }

    /**
     * 写入并**立刻**返回新值，调用方拿它更新 state，避免"存了但界面没变"。
     *
     * 用 commit() 而不是 apply()：切侧栏是低频动作，但紧接着就要读 ——
     * apply() 异步落盘，万一进程被杀（用户切完就熄屏）这次选择会丢。
     */
    fun setSidebarSide(context: Context, side: SidebarSide): SidebarSide {
        prefs(context).edit().putString(KEY_SIDEBAR_SIDE, side.name).commit()
        return side
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
