package com.qingke.desk.hosting.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskTheme
import com.qingke.desk.hosting.ShizukuBootPrompt
import com.qingke.desk.hosting.ShizukuDebugOverride
import com.qingke.desk.hosting.ShizukuStatus
import com.qingke.desk.hosting.ui.ShizukuBootPromptCard
import com.qingke.desk.hosting.ui.ShizukuGuideScreen
import com.qingke.desk.hosting.ui.ShizukuStatusChip

/**
 * **仅 debug 变体**：把首页状态栏指示、Shizuku 引导页、重启后的一次性提示
 * 单独渲染出来，供真机截图验收。渲染的是 [ShizukuStatusBar] /
 * [ShizukuGuideScreen] / [ShizukuBootPromptCard] 这三份真代码本身，不是复制品。
 *
 * 用法：
 *   # 只看界面
 *   adb shell am start -n com.qingke.desk/com.qingke.desk.hosting.debug.ShizukuPreviewActivity \
 *       --es view guide --es state not_running
 *   view: chip / guide / prompt（默认 guide）
 *   state: ready / not_running / not_authorized / not_installed（默认 not_running）
 *
 *   # 把假状态写进应用（活过 force-stop），用来验收"未运行时首页长什么样"
 *   adb shell am start -n com.qingke.desk/...ShizukuPreviewActivity --es apply not_running
 *   adb shell am start -n com.qingke.desk/...ShizukuPreviewActivity --es apply off     # 清除
 *
 *   # 清掉"本次开机已提示"的记账，用来验收重启后只提示一次
 *   adb shell am start -n com.qingke.desk/...ShizukuPreviewActivity --ez resetboot true
 */
class ShizukuPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apply = intent.getStringExtra(EXTRA_APPLY)
        if (apply != null) {
            ShizukuDebugOverride.write(this, if (apply == VALUE_OFF) null else statusOf(apply))
            finish()
            return
        }
        if (intent.getBooleanExtra(EXTRA_RESET_BOOT, false)) {
            ShizukuBootPrompt.resetForDebug(this)
            finish()
            return
        }

        val status = statusOf(intent.getStringExtra(EXTRA_STATE))
        val view = intent.getStringExtra(EXTRA_VIEW) ?: VIEW_GUIDE
        setContent {
            DeskTheme {
                PreviewHost {
                    when (view) {
                        // 2026-10-12：指示改成右上角悬浮角标之后，它不再被包在
                        // 一条状态栏里 —— 预览页直接渲染这个 chip 本身。
                        VIEW_CHIP -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    top = DeskDimens.statusChipTopPadding,
                                    end = DeskDimens.statusChipSidePadding,
                                ),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            ShizukuStatusChip(status = status, onClick = { })
                        }

                        VIEW_PROMPT -> ShizukuBootPromptCard(
                            status = status,
                            onOpenGuide = { },
                            onDismiss = { },
                        )

                        else -> ShizukuGuideScreen(
                            status = status,
                            onRefresh = { },
                            onRequestPermission = { },
                            onOpenShizuku = { },
                            onExit = { finish() },
                        )
                    }
                }
            }
        }
    }

    private fun statusOf(value: String?): ShizukuStatus = when (value) {
        STATE_READY -> ShizukuStatus.READY
        STATE_NOT_AUTHORIZED -> ShizukuStatus.NOT_AUTHORIZED
        STATE_NOT_INSTALLED -> ShizukuStatus.NOT_INSTALLED
        else -> ShizukuStatus.NOT_RUNNING
    }

    private companion object {
        const val EXTRA_VIEW = "view"
        const val EXTRA_STATE = "state"
        const val EXTRA_APPLY = "apply"
        const val EXTRA_RESET_BOOT = "resetboot"

        const val VIEW_CHIP = "chip"
        const val VIEW_GUIDE = "guide"
        const val VIEW_PROMPT = "prompt"

        const val STATE_READY = "ready"
        const val STATE_NOT_AUTHORIZED = "not_authorized"
        const val STATE_NOT_INSTALLED = "not_installed"

        const val VALUE_OFF = "off"
    }
}

@Composable
private fun PreviewHost(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        content()
    }
}
