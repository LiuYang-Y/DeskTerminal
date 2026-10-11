package com.qingke.desk.shell

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qingke.desk.connection.ConnectionPhase
import com.qingke.desk.connection.ConnectionUiState
import com.qingke.desk.core.model.DisplayInfo
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.projection.api.AvailabilityAction
import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionRenderMode
import com.qingke.desk.projection.api.ProjectionSession
import com.qingke.desk.projection.api.ProjectionVideoTarget

/**
 * 全屏投屏页。第一版只负责"接管画面 + 给出返回路径"，不做任何协议处理。
 *
 * 三种主体内容，按优先级分支：
 *   1. [AvailabilityResult.Unavailable] —— 拉起被挡住（外部应用没装 / 未支持），显示原因与补救；
 *   2. EMBEDDED_SURFACE               —— 我方 Surface 出画面；
 *   3. EXTERNAL_ACTIVITY              —— 画面在外部应用里，这里只说明"怎么回来"。
 */
@Composable
fun ProjectionScreen(
    uiState: ConnectionUiState,
    session: ProjectionSession?,
    displayInfo: DisplayInfo,
    onExit: () -> Unit,
    onRetry: () -> Unit,
    /** 提示页上的自助动作。整块 [AvailabilityResult.Unavailable] 都给出去，供 :shell 取包名等附加信息。 */
    onRemedy: (AvailabilityResult.Unavailable) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 逃生手势只在画面由本进程渲染时收得到触摸事件；
    // EXTERNAL_ACTIVITY（阶段一拉起外部 DiPlay）会收不到，此时只能靠按钮/返回键。
    val escapeEnabled = session?.capabilities?.renderMode == ProjectionRenderMode.EMBEDDED_SURFACE
    val blocked = uiState.availability as? AvailabilityResult.Unavailable

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .edgeSwipeUp(enabled = escapeEnabled, onSwipeUp = onExit),
    ) {
        when {
            blocked != null -> UnavailablePanel(unavailable = blocked)
            session == null -> StatusText("等待连接…")
            escapeEnabled -> ProjectionSurfaceHost(session = session, displayInfo = displayInfo)
            else -> ExternalProjectionPlaceholder(session.id)
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(DeskDimens.screenPadding),
        ) {
            Text(
                text = phaseLabel(uiState),
                color = Color.White,
                fontSize = DeskDimens.cardCaption,
            )
            uiState.lastError?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "${error.code} · ${error.detail}".trim(),
                    color = Color(0xFFFF8A80),
                    fontSize = DeskDimens.cardCaption,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(DeskDimens.screenPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 排在最前的必须是"去做那件事"：缺 Wi-Fi / 应用被停用时，
            // 用户第一步应该去把开关打开，原地重测一万次也没用。
            blocked?.action?.let { action ->
                Button(onClick = { onRemedy(blocked) }, modifier = Modifier.sizeIn(minHeight = DeskDimens.minTouchTarget)) {
                    Text(remedyLabel(action))
                }
            }
            if (blocked != null ||
                uiState.phase == ConnectionPhase.RECONNECTING ||
                uiState.lastError != null
            ) {
                // 这一颗同时是"重试"和"重新检测"：走的是同一条 connect()，
                // 而 connect() 每次都会重新查一遍可用性（含 Wi‑Fi 开关状态）。
                Button(onClick = onRetry, modifier = Modifier.sizeIn(minHeight = DeskDimens.minTouchTarget)) {
                    Text(if (blocked != null) "重新检测" else "重试")
                }
            }
            Button(
                onClick = onExit,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.sizeIn(minHeight = DeskDimens.minTouchTarget),
            ) {
                Icon(Icons.Default.Close, contentDescription = "退出投屏")
                Spacer(modifier = Modifier.width(8.dp))
                Text("返回首页")
            }
        }
    }
}

private fun remedyLabel(action: AvailabilityAction): String = when (action) {
    AvailabilityAction.OPEN_WIFI_SETTINGS -> "去开启"
    AvailabilityAction.OPEN_APP_DETAILS -> "去启用"
}

private fun phaseLabel(state: ConnectionUiState): String {
    val blocked = state.availability as? AvailabilityResult.Unavailable
    if (blocked != null) return blocked.headline

    return when {
        state.phase == ConnectionPhase.CONNECTED -> "投屏中 · ${state.sessionState}"
        state.phase == ConnectionPhase.CONNECTING -> "正在连接… · ${state.sessionState}"
        state.phase == ConnectionPhase.RECONNECTING ->
            "连接断开，正在重试（第 ${state.retryAttempt} 次）"

        else -> "未连接"
    }
}

@Composable
private fun StatusText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color.White.copy(alpha = 0.7f), fontSize = DeskDimens.cardBody)
    }
}

/**
 * 拉起被挡住时的提示页。
 *
 * 存在的理由很实在：阶段一依赖外部 DiPlay，没装是常态而不是异常。
 * 这里必须把"缺什么 + 怎么补救"说清楚，**绝不能只是转圈或直接崩掉**。
 */
@Composable
private fun UnavailablePanel(
    unavailable: AvailabilityResult.Unavailable,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color(0xFF1B1E26),
            shape = RoundedCornerShape(DeskDimens.cardCorner),
            modifier = Modifier.fillMaxWidth(0.72f),
        ) {
            Column(
                modifier = Modifier.padding(DeskDimens.cardPadding * 1.5f),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    text = unavailable.headline,
                    color = Color.White,
                    fontSize = DeskDimens.cardTitle,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = unavailable.detail,
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = DeskDimens.cardBody,
                )
                if (unavailable.remedy.isNotBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = unavailable.remedy,
                        color = Color(0xFF9ECBFF),
                        fontSize = DeskDimens.cardBody,
                    )
                }
                unavailable.extras["package"]?.let { pkg ->
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = buildString {
                            append("包名 $pkg")
                            unavailable.extras["version"]?.let { append(" · $it") }
                        },
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = DeskDimens.cardCaption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 画面已移交给外部应用。
 *
 * 这里要把"现在画面是谁在画、怎么回来"讲清楚 —— 用户看到黑屏如果不知道
 * 下一步该按什么，第一反应就是拔线。
 */
@Composable
private fun ExternalProjectionPlaceholder(sessionId: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color(0xFF1B1E26),
            shape = RoundedCornerShape(DeskDimens.cardCorner),
        ) {
            Column(
                modifier = Modifier.padding(DeskDimens.cardPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "画面已交给外部应用渲染",
                    color = Color.White,
                    fontSize = DeskDimens.cardTitle,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "阶段一由 $sessionId 接收 iPhone 的 CarPlay 画面，" +
                        "视频帧不经过本进程，所以本页只是一层背景，屏幕上不会显示任何内容。\n\n" +
                        "iPhone 侧的连接在外部应用里完成；回到外部应用后，" +
                        "按返回键或从最近任务切回本应用，都会自动回到首页。",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = DeskDimens.cardCaption,
                )
            }
        }
    }
}

/**
 * 投屏幕布。
 *
 * 尺寸来源：**实际可用于 Compose 的像素空间**，而不是 MainActivity 启动时读到的
 * 窗口尺寸 —— 后者在 onCreate 时读取，横屏旋转尚未完成，拿到的可能是竖屏值；
 * 而且旋转后不会自动更新。用错值会让 SurfaceView 的 buffer 小于视图，
 * 右边露出一条纯黑带（真机截图确认过的现象）。
 *
 * 物理像素上限仍由 [DeskDimens] 管，这里只做裁剪。
 */
@Composable
private fun ProjectionSurfaceHost(
    session: ProjectionSession,
    displayInfo: DisplayInfo,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val surfaceWidthPx = with(density) { maxWidth.roundToPx() }
            .coerceAtMost(DeskDimens.PROJECTION_MAX_WIDTH_PX)
        val surfaceHeightPx = with(density) { maxHeight.roundToPx() }
            .coerceAtMost(DeskDimens.PROJECTION_MAX_HEIGHT_PX)

        key(session) {
            AndroidView(
                // TODO(step-2)：接入真实码流后，这里要按视频宽高比做信箱式留边，
                //   而不是铺满可用空间。模拟实现没有真实分辨率，先铺满。
                modifier = Modifier.size(
                    width = with(density) { surfaceWidthPx.toDp() },
                    height = with(density) { surfaceHeightPx.toDp() },
                ),
                factory = { context ->
                    val view = SurfaceView(context)
                    view.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            holder.setFixedSize(surfaceWidthPx, surfaceHeightPx)
                            session.attachVideoSurface(
                                ProjectionVideoTarget(
                                    surface = holder.surface,
                                    widthPx = surfaceWidthPx,
                                    heightPx = surfaceHeightPx,
                                    densityDpi = displayInfo.densityDpi,
                                )
                            )
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            session.detachVideoSurface()
                        }
                    })
                    view
                },
            )
        }
    }
}
