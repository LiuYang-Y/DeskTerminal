package com.qingke.desk.feature.media

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat

private const val TAG = "DeskMediaPerm"

/**
 * 通知使用权的查询与引导。
 *
 * 这个权限**没有运行时授权对话框** —— 没有 requestPermissions 这条路，
 * 系统强制要求用户自己在 设置 → 通知 → 通知使用权 里勾选，应用只能把那页推到前面。
 * 这一点必须在 UI 上讲清楚，否则用户会以为点了没反应。
 */
object MediaPermission {

    /** 本应用当前是否已被授予通知使用权。 */
    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * 系统"通知使用权"设置页。给不了就返回 null，由调用方提示手动进入。
     *
     * ACTION_NOTIFICATION_LISTENER_SETTINGS 是 API 22 加的，本应用 minSdk 26，不会为 null；
     * 但仍然用 resolveActivity 兜一层 —— 实测有 ROM 会去掉这个入口页，
     * 直接 startActivity 会抛 ActivityNotFoundException 崩掉。
     */
    fun settingsIntent(context: Context): Intent? {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvable = runCatching {
            intent.resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
        return if (resolvable) intent else null
    }

    /** 跳设置页。打不开时通过 [onFailure] 交回给 UI 提示，不抛给调用方。 */
    fun openSettings(context: Context, onFailure: (String) -> Unit) {
        val intent = settingsIntent(context)
        if (intent == null) {
            Log.w(TAG, "notification listener settings page unavailable")
            onFailure("这台设备没有提供通知使用权设置页，请在系统设置里搜索「通知使用权」手动开启")
            return
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            Log.w(TAG, "open settings failed: ${it.javaClass.simpleName}")
            onFailure("没能打开通知使用权设置页，请在系统设置里手动进入")
        }
    }
}
