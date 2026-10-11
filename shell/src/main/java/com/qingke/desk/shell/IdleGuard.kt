package com.qingke.desk.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.qingke.desk.core.ui.DeskDimens
import kotlinx.coroutines.delay

/**
 * 空闲守护：一段时间没有交互就盖上屏保层。
 *
 * 只负责"判定空闲"和"渲染屏保"，亮度交给 [com.qingke.desk.core.platform.DisplayController]
 * 由上层统一处理 —— 免得两处各自改亮度打架。上层通过 [onIdleChange] 收到进出屏保的通知，
 * 在**进屏保时**压暗、**退出屏保时立刻交还**系统亮度（见 MainActivity）。
 *
 * 2026-10-12 修：之前上层是在 onResume 里按小时数强制设窗口亮度，
 * 结果每次进入应用都把用户自己的亮度拉走一档（真机投诉"进来就变暗"）。
 * 现在只有屏保会动亮度，平时一行都不碰。
 *
 * TODO(step-1): 屏幕常亮与屏保是矛盾的。当前策略是常亮 + 屏保压到低亮；
 *           若真机发现压亮度仍不够防烧屏，再改为到点直接息屏、触摸唤醒。
 */
@Composable
fun IdleGuard(
    modifier: Modifier = Modifier,
    idleTimeoutMs: Long = DeskDimens.IDLE_TIMEOUT_MS,
    onIdleChange: (Boolean) -> Unit = {},
    screensaver: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var idle by remember { mutableStateOf(false) }

    // 用 rememberUpdatedState：lambda 每次重组都是新的，不能让 LaunchedEffect 因为
    // 它变了就重启（那会把空闲计时重新开始，屏保永远等不到）。
    val onIdleChangeNow by rememberUpdatedState(onIdleChange)

    LaunchedEffect(lastInteractionMs, idle) {
        if (idle) return@LaunchedEffect
        delay(idleTimeoutMs)
        if (System.currentTimeMillis() - lastInteractionMs >= idleTimeoutMs) {
            idle = true
        }
    }

    LaunchedEffect(idle) { onIdleChangeNow(idle) }

    // 离开组合时（换屏、Activity 销毁）必须把亮度交还 ——
    // 否则屏保亮着的时候进程被收走，亮度就永远停在 0.08 那一档。
    DisposableEffect(Unit) {
        onDispose { onIdleChangeNow(false) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    lastInteractionMs = System.currentTimeMillis()
                    idle = false
                },
            )
        },
    ) {
        content()

        if (idle) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            lastInteractionMs = System.currentTimeMillis()
                            idle = false
                        }
                    },
            ) {
                screensaver()
            }
        }
    }
}
