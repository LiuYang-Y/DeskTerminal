package com.qingke.desk.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import com.qingke.desk.core.ui.DeskDimens
import kotlinx.coroutines.delay

/**
 * 空闲守护：一段时间没有交互就盖上屏保层。
 *
 * 只负责"判定空闲"和"渲染屏保"，亮度交给 [com.qingke.desk.core.platform.DisplayController]
 * 由上层统一处理 —— 免得两处各自改亮度打架。
 *
 * TODO(step-1): 屏幕常亮与屏保是矛盾的。当前策略是常亮 + 屏保压到低亮；
 *           若真机发现压亮度仍不够防烧屏，再改为到点直接息屏、触摸唤醒。
 */
@Composable
fun IdleGuard(
    modifier: Modifier = Modifier,
    idleTimeoutMs: Long = DeskDimens.IDLE_TIMEOUT_MS,
    screensaver: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    var lastInteractionMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var idle by remember { mutableStateOf(false) }

    LaunchedEffect(lastInteractionMs, idle) {
        if (idle) return@LaunchedEffect
        delay(idleTimeoutMs)
        if (System.currentTimeMillis() - lastInteractionMs >= idleTimeoutMs) {
            idle = true
        }
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
