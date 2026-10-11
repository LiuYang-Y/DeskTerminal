package com.qingke.desk.hosting.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.qingke.desk.core.ui.DeskTheme
import com.qingke.desk.hosting.BlockReason
import com.qingke.desk.hosting.ui.BlockedNotice

/**
 * **仅 debug 变体**：把 [BlockedNotice] 的四种状态单独渲染出来，供真机截图验收。
 *
 * 它渲染的就是托管页里那份组件本身，不是复制品 —— 截图能证明的东西和线上一致。
 *
 * 用法：
 *   adb shell am start -n com.qingke.desk/com.qingke.desk.hosting.debug.NoticePreviewActivity \
 *       --es reason not_running
 *   reason 取值：not_installed / not_running / not_authorized / target_missing
 */
class NoticePreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val which = intent.getStringExtra(EXTRA_REASON) ?: REASON_NOT_RUNNING
        setContent {
            DeskTheme {
                BlockedNotice(
                    reason = reasonOf(which),
                    // 预览里按钮不做事：点一下只是关掉这一页。
                    onRefresh = { finish() },
                    onRequestPermission = { finish() },
                    onExit = { finish() },
                )
            }
        }
    }

    private fun reasonOf(value: String): BlockReason = when (value) {
        REASON_NOT_INSTALLED -> BlockReason.ShizukuNotInstalled
        REASON_NOT_AUTHORIZED -> BlockReason.ShizukuNotAuthorized
        REASON_TARGET_MISSING ->
            BlockReason.TargetMissing("高德地图", "com.autonavi.minimap")

        else -> BlockReason.ShizukuNotRunning
    }

    private companion object {
        const val EXTRA_REASON = "reason"
        const val REASON_NOT_INSTALLED = "not_installed"
        const val REASON_NOT_RUNNING = "not_running"
        const val REASON_NOT_AUTHORIZED = "not_authorized"
        const val REASON_TARGET_MISSING = "target_missing"
    }
}
