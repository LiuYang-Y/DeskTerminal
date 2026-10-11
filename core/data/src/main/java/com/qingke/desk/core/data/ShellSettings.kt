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
 * 壳层的持久设置。两项：侧栏在哪一侧、屏幕是否强制常亮。
 *
 * 用 SharedPreferences 而不是 DataStore：全工程只有这几个开关要存，
 * 为它引入一条协程 + Proto 的链路不划算（:hosting 的 ShizukuBootPrompt、
 * :map 的 AMapPrivacy 也都是这个口径，`apply()` 即可，不阻塞主线程写盘）。
 *
 * 注意这里用 [Context.getApplicationContext] 之外的 context 也安全 ——
 * 取的是 applicationContext 的 prefs，不持有 Activity。
 */
object ShellSettings {

    private const val PREFS_NAME = "desk_shell"
    private const val KEY_SIDEBAR_SIDE = "sidebar_side"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    /** 默认在左：基准机横屏时挖孔在左侧，侧栏贴左能把挖孔条一起吃掉。 */
    private val DEFAULT_SIDE = SidebarSide.LEFT

    /**
     * 默认**常亮**。
     *
     * 桌面终端的核心用法就是"抬眼就能看"，时钟、地图、卡片 24 小时在线；
     * 一旦跟随系统息屏，每次看时间都要先点亮（有锁屏密码还得解锁），体验是断崖式的。
     * 代价是屏幕长期点亮带来的 OLED 老化与耗电 —— 这正是保留防烧屏微位移
     * （[com.qingke.desk.core.platform.BurnInGuard]）的理由：它只对亮着的画面有用。
     *
     * 做成开关而不是写死：省不省电、怕不怕烧屏是**用户自己的取舍**，
     * 该由用户在设置面板里选，不该由我们替他决定。
     */
    private const val DEFAULT_KEEP_SCREEN_ON = true

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

    /**
     * 屏幕是否强制常亮（[android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]）。
     *
     * 关掉之后屏幕跟随系统休眠设置自己灭 —— 休眠时长由用户在系统设置里定，
     * 本应用改不了（也**不应该**改：那等于绕开用户的选择）。
     */
    fun keepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)

    /**
     * 写入并**立刻**返回新值。
     *
     * 这里用 `commit()` 而不是 `apply()`，理由和 [setSidebarSide] 不同：
     * 常亮状态存在窗口 flag 上，**进程被杀后 flag 随之消失**，下次启动会重新读设置，
     * 所以异步落盘丢一次不会造成"界面与设置不一致"。但紧跟着就要把它应用到窗口上，
     * 同步写完能保证"设置页显示的值"与"窗口实际生效的值"是同一个。
     */
    fun setKeepScreenOn(context: Context, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).commit()
        return enabled
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
