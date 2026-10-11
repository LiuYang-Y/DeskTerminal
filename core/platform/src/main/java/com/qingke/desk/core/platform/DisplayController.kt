package com.qingke.desk.core.platform

import android.view.Window
import android.view.WindowManager

/**
 * 屏幕能力封装。
 *
 * 2026-10-12 收窄到只剩"常亮"一项：
 * 窗口亮度能力（setWindowBrightness / systemBrightness）随屏保一起删掉了。
 * 它们唯一的用途是"进屏保时压暗"，屏保没了就没用了；留着只会引诱后来的人
 * 再写一条"按时间自动改亮度"的策略 —— 那条路已经走过一次，真机投诉
 * "每次进入应用都把我的亮度拉走一档"（见 [BurnInGuard] 的说明）。
 * **本应用不碰用户的亮度**，这是一条定下来的口径。
 */
interface DisplayController {

    /** 常亮。用 FLAG_KEEP_SCREEN_ON 而不是 WakeLock，系统会在 Activity 不可见时自动释放。 */
    fun keepScreenOn(enabled: Boolean)
}

class WindowDisplayController(
    private val window: Window,
) : DisplayController {

    override fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
