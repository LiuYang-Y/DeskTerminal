package com.qingke.desk.feature.media.music.online

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.feature.media.music.LoginPaneMetrics
import com.qingke.desk.feature.media.music.MusicLayout

/**
 * 扫码登录浮层。
 *
 * ---------------------------------------------------------------------------
 * 它只做两件事：**把 [QqLoginState] 画出来**，以及把三个意图（获取二维码 / 取消 / 退出登录）
 * 原样上抛。它不认识 musicu、不认识 MQTT —— 与音乐页不认识 `MediaSession` 是同一条边界。
 *
 * ---------------------------------------------------------------------------
 * 两条实现约定（都来自本项目已经踩过的坑）
 *
 * 1. **尺寸全部来自 [metrics]**，这个文件里没有一个 `64.dp` 这样的字面量。
 *    浮层与音乐页共用同一个 `BoxWithConstraints` 的 `maxWidth/maxHeight`。
 * 2. **点击关闭挂在遮罩上，不挂在卡片上**。卡片另用空 `detectTapGestures` 吞掉点击 ——
 *    少了这一步，在卡片上点任何空白处都会把浮层关掉（用户正想扫码时最容易误触）。
 */
@Composable
fun QqLoginPane(
    state: QqLoginState,
    metrics: LoginPaneMetrics,
    onStartLogin: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = metrics.outerYDp)
                .clip(RoundedCornerShape(percent = 12))
                .background(PaneSurface)
                // 吞掉点击，别让它穿透到遮罩上（见类注释第 2 条）。
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(horizontal = metrics.padXDp, vertical = metrics.padYDp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "QQ 音乐账号",
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )

            Box(
                modifier = Modifier
                    .padding(top = metrics.gapYDp)
                    .size(metrics.qrSideDp),
                contentAlignment = Alignment.Center,
            ) {
                QrArea(state = state, side = metrics.qrSideDp)
            }

            Text(
                text = statusTextOf(state),
                fontSize = metrics.statusSize,
                lineHeight = metrics.statusSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.68f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = metrics.gapYDp)
                    .padding(horizontal = metrics.padXDp),
            )

            Row(
                modifier = Modifier.padding(top = metrics.gapYDp),
                horizontalArrangement = Arrangement.spacedBy(metrics.gapYDp * 2f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val (label, action) = primaryActionOf(state, onStartLogin, onCancel, onLogout)
                PaneButton(
                    text = "关闭",
                    metrics = metrics,
                    primary = false,
                    onClick = onDismiss,
                )
                PaneButton(
                    text = label,
                    metrics = metrics,
                    primary = true,
                    onClick = action,
                )
            }
        }
    }
}

/** 二维码区。每种状态**都有东西可看** —— 空白区会让人以为界面卡住了。 */
@Composable
private fun QrArea(state: QqLoginState, side: Dp) {
    when (state) {
        is QqLoginState.QrReady -> {
            // 只 decode 一次。码没变就不该重解 —— 一次 decode + 一次 inflate 在 200dp 上是几毫秒，
            // 但每次重组都做的话，浮层会明显发涩。
            val bitmap = remember(state.pngBase64) { decodeQr(state.pngBase64) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "登录二维码",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(percent = 6))
                        .background(Color.White),
                )
            } else {
                // 解不出来时**明说**，不要给一个空白方块。
                Text(
                    text = "二维码解析失败，请重新获取",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    lineHeight = 12.sp * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                    textAlign = TextAlign.Center,
                )
            }
        }

        QqLoginState.Preparing,
        QqLoginState.Scanned,
        QqLoginState.Exchanging,
        -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.85f))

        else -> Text(
            text = glyphOf(state),
            fontSize = (side.value * 0.5f).sp,
            color = Color.White.copy(alpha = 0.5f),
        )
    }
}

private fun decodeQr(pngBase64: String): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val bytes = Base64.decode(pngBase64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

private fun glyphOf(state: QqLoginState): String = when (state) {
    is QqLoginState.LoggedIn -> "✓"
    is QqLoginState.Expired -> "!"
    is QqLoginState.Failed -> "×"
    else -> "♪"
}

private fun statusTextOf(state: QqLoginState): String = when (state) {
    is QqLoginState.Failed -> state.reason
    is QqLoginState.LoggedIn -> "已登录 · ${state.cred.mask()}"
    is QqLoginState.Expired -> "登录已过期，请重新扫码"
    else -> state.statusLabel()
}

private fun primaryActionOf(
    state: QqLoginState,
    onStartLogin: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
): Pair<String, () -> Unit> = when (state) {
    is QqLoginState.LoggedIn -> "退出登录" to onLogout
    // 从 Preparing 开始就是"进行中"，此时主按钮的语义是取消 ——
    // 再点一次"获取二维码"会在上游铸出第二张码，把第一张和它的订阅一起作废。
    QqLoginState.Preparing,
    is QqLoginState.QrReady,
    QqLoginState.Scanned,
    QqLoginState.Exchanging,
    -> "取消" to onCancel

    else -> "获取二维码" to onStartLogin
}

@Composable
private fun PaneButton(
    text: String,
    metrics: LoginPaneMetrics,
    primary: Boolean,
    onClick: () -> Unit,
) {
    // 自绘而不是 Material3 的 Button：后者的默认最小高度是 48dp，会和我们按可用空间
    // 反推出来的 buttonHeight 互相打架（矮屏上它会把按钮顶回 48，卡片就溢出了）。
    Box(
        modifier = Modifier
            .height(metrics.buttonHeightDp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (primary) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = metrics.padXDp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = metrics.buttonTextSize,
            lineHeight = metrics.buttonTextSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
            color = if (primary) PaneOnPrimary else Color.White.copy(alpha = 0.9f),
            maxLines = 1,
        )
    }
}

/** 浮层底色。比音乐页背景亮一档，才能在深色内容上"浮"起来。 */
private val PaneSurface = Color(0xFF17171A).copy(alpha = 0.97f)

private val PaneOnPrimary = Color(0xFF101014)
