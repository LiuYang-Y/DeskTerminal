package com.qingke.desk.feature.media

import android.service.notification.NotificationListenerService
import android.util.Log

private const val TAG = "DeskMediaListener"

/**
 * 通知监听服务。
 *
 * 它**不读任何通知内容** —— 存在的唯一目的，是让本应用成为"已启用的通知监听器"，
 * 从而满足 MediaSessionManager.getActiveSessions() 的权限前提（详见本模块 Manifest 的注释）。
 * 真正的取数与控制在 [MediaSessionMonitor] 里。
 *
 * 刻意不覆写 onNotificationPosted：一旦覆写，我们就真的在处理全设备通知了，
 * 而这个模块只需要媒体会话。
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected -> media sessions available")
        MediaListenerBridge.dispatch()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "listener disconnected")
        MediaListenerBridge.dispatch()
    }
}
