package com.qingke.desk.core.platform

import android.view.Window
import android.view.WindowManager

/**
 * 屏幕能力封装。
 *
 * 只用"窗口级"亮度，不碰 Settings.System —— 不 root 拿不到 WRITE_SETTINGS，
 * 窗口级亮度对常驻桌面的应用已经够用，且不需要任何权限。
 */
interface DisplayController {

    /** 常亮。用 FLAG_KEEP_SCREEN_ON 而不是 WakeLock，系统会在 Activity 不可见时自动释放。 */
    fun keepScreenOn(enabled: Boolean)

    /** 覆盖当前窗口亮度，0f..1f；传 null 恢复跟随系统。 */
    fun setWindowBrightness(value: Float?)

    /** 查询系统当前亮度，用于分时段策略的基线。0f..1f，读不到返回 null。 */
    fun systemBrightness(): Float?
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

    override fun setWindowBrightness(value: Float?) {
        val params = window.attributes
        params.screenBrightness =
            value?.coerceIn(0f, 1f) ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = params
    }

    override fun systemBrightness(): Float? {
        val brightness = window.context.contentResolver?.let {
            android.provider.Settings.System.getInt(
                it,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                -1,
            )
        } ?: -1
        return if (brightness in 0..255) brightness / 255f else null
    }
}
