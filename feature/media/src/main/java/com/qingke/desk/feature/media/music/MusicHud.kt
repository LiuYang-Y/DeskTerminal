package com.qingke.desk.feature.media.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qingke.desk.feature.media.music.artwork.ArtworkVisual
import androidx.compose.foundation.Image

/** HUD 里所有前景元素用的颜色。整页只有深色底 + 浅色字这一套，不需要主题色。 */
private val HudForeground = Color.White

/**
 * 底部 HUD：左下封面 + 歌名/歌手/导入，右下进度条 + 三个控制键。
 *
 * ---------------------------------------------------------------------------
 * 淡出**只改 alpha，不改布局**
 *
 * 这一点是本项目已经验证过的准则（改位置的话每次唤醒歌词都会跟着跳一下）。
 * 这里 `Modifier.alpha(fade)` 挂在整块 HUD 的最外层，内部所有 `align` / `height`
 * 都跟着 [metrics] 走、与可见性无关 —— 淡出前后**像素布局完全一致**，
 * 真机上可以直接 diff 两张截图来验证。
 *
 * ---------------------------------------------------------------------------
 * 隐藏时必须**真的不可点**
 *
 * `Modifier.alpha(0f)` **不影响命中测试** —— 透明度 0 的按钮照样接得住点击。
 * 所以每个可点元素都带 `enabled = interactive`，由页面把"HUD 是否可见"传进来。
 * 少了这一步，症状是"看不见按钮的地方点一下，歌突然跳了"。
 *
 * 注意 `clickable(enabled = false)` 不会消费事件，于是这一下会落到页面的
 * 唤醒层上 —— 正是我们要的：在看词态点任何地方，第一下是"唤醒"，不是"操作"。
 */
@Composable
fun MusicHud(
    visual: ArtworkVisual,
    title: String,
    artist: String,
    sourceLabel: String,
    positionMs: State<Long>,
    durationMs: Long,
    isPlaying: Boolean,
    canSeek: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    canPlayPause: Boolean,
    metrics: MusicMetrics,
    fade: Float,
    interactive: Boolean,
    driftX: Dp,
    driftY: Dp,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onImportLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(fade)
            .padding(metrics.pad.dp)
            // 漂移挂在最外层：整块 HUD 一起缓慢游走，各元素之间的相对位置不变。
            .offset(x = driftX, y = driftY),
    ) {
        // ---- 左下：封面 + 信息 ----
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .height(metrics.coverSideDp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkTile(visual = visual, side = metrics.coverSideDp)

            Column(
                modifier = Modifier
                    .padding(start = metrics.coverTextGap.dp)
                    .width(metrics.hudTextMaxWidth.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = title.ifBlank { "未知曲目" },
                    fontSize = metrics.titleSize,
                    lineHeight = metrics.titleSize * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM,
                    color = HudForeground,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitleOf(artist, sourceLabel),
                    fontSize = metrics.artistSize,
                    lineHeight = metrics.artistSize * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM,
                    color = HudForeground.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "导入歌词",
                    fontSize = metrics.chipSize,
                    lineHeight = metrics.chipSize * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM,
                    color = HudForeground.copy(alpha = if (interactive) 0.55f else 0f),
                    maxLines = 1,
                    modifier = Modifier
                        .padding(top = metrics.hudTextGap.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(enabled = interactive, onClick = onImportLyrics)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }

        // ---- 右下：进度条 + 控制键 ----
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .width(metrics.hudColumnWidth.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SeekBar(
                positionMs = positionMs,
                durationMs = durationMs,
                enabled = canSeek && interactive,
                touchHeight = metrics.progressTouchHeightDp,
                barHeight = metrics.progressHeightDp,
                onSeek = onSeek,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.padding(top = metrics.progressGap.dp),
                horizontalArrangement = Arrangement.spacedBy(
                    metrics.controlGap.dp,
                    Alignment.End,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(
                    size = metrics.sideControlSizeDp,
                    iconSize = metrics.controlIconSizeDp,
                    enabled = canPrevious && interactive,
                    description = "上一首",
                    onClick = onPrevious,
                    icon = { tint, modifier -> Icon(Icons.Default.SkipPrevious, null, modifier, tint = tint) },
                )
                ControlButton(
                    size = metrics.controlSizeDp,
                    iconSize = metrics.controlIconSizeDp,
                    enabled = canPlayPause && interactive,
                    description = if (isPlaying) "暂停" else "播放",
                    onClick = onPlayPause,
                    icon = { tint, modifier ->
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = modifier,
                            tint = tint,
                        )
                    },
                )
                ControlButton(
                    size = metrics.sideControlSizeDp,
                    iconSize = metrics.controlIconSizeDp,
                    enabled = canNext && interactive,
                    description = "下一首",
                    onClick = onNext,
                    icon = { tint, modifier -> Icon(Icons.Default.SkipNext, null, modifier, tint = tint) },
                )
            }
        }
    }
}

/** `歌手 · 来源`。两段都为空时给一句兜底，避免左下角出现一个空白行。 */
private fun subtitleOf(artist: String, source: String): String {
    val parts = listOf(artist.trim(), source.trim()).filter { it.isNotBlank() }
    return if (parts.isEmpty()) "未知艺术家" else parts.joinToString(" · ")
}

@Composable
private fun ArtworkTile(visual: ArtworkVisual, side: Dp) {
    val backdrop = visual.backdrop
    val shape = RoundedCornerShape(side * 0.17f)
    Box(
        modifier = Modifier
            .size(side)
            .clip(shape)
            .background(visual.accent.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        if (backdrop != null) {
            // 底图是模糊过的小图 —— 当成封面的占位是够的（缩到 58 dp 之后
            // 它看起来就是一团与专辑同色的色块），而且**不用再解一次原图**。
            Image(
                bitmap = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = HudForeground.copy(alpha = 0.75f),
                modifier = Modifier.size(side * 0.42f),
            )
        }
    }
}

/**
 * 可拖动进度条。
 *
 * ---------------------------------------------------------------------------
 * 它**一次重组都不产生**
 *
 * 进度值通过 [State] 传进来，并且在 `Canvas` 的**绘制 lambda** 里才读
 * （`positionMs.value` / `dragFraction.value`）。Compose 对绘制期读取只做
 * "重绘"级别的失效，不触发重组 —— 这是本项目"逐帧元素不进组合"那条准则的落地。
 *
 * 拖动中的值单独用一个 `State<Float>`：`null` 表示没在拖。
 * 有它才能在拖的时候**不跟着播放位置走**（否则手指按着，进度条自己往前爬）。
 */
@Composable
private fun SeekBar(
    positionMs: State<Long>,
    durationMs: Long,
    enabled: Boolean,
    touchHeight: Dp,
    barHeight: Dp,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // -1 表示"没在拖"。用哨兵而不是 nullable State：避免每次拖动都装箱。
    val dragFraction = remember { mutableFloatStateOf(NO_DRAG) }

    Box(
        modifier = modifier
            .height(touchHeight)
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs <= 0L) return@pointerInput
                detectTapGestures { offset ->
                    val w = size.width.coerceAtLeast(1)
                    val fraction = (offset.x / w).coerceIn(0f, 1f)
                    onSeek((fraction * durationMs).toLong())
                }
            }
            .pointerInput(enabled, durationMs) {
                if (!enabled || durationMs <= 0L) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.coerceAtLeast(1)
                        dragFraction.floatValue = (offset.x / w).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        val f = dragFraction.floatValue
                        if (f >= 0f) onSeek((f * durationMs).toLong())
                        dragFraction.floatValue = NO_DRAG
                    },
                    onDragCancel = { dragFraction.floatValue = NO_DRAG },
                ) { change, _ ->
                    val w = size.width.coerceAtLeast(1)
                    dragFraction.floatValue = (change.position.x / w).coerceIn(0f, 1f)
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (durationMs <= 0L) return@Canvas

            val barPx = barHeight.toPx()
            val cy = size.height / 2f
            val top = cy - barPx / 2f
            val corner = CornerRadius(barPx / 2f, barPx / 2f)

            val dragged = dragFraction.floatValue
            val fraction = if (dragged >= 0f) {
                dragged
            } else {
                (positionMs.value.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            }

            // 轨道
            drawRoundRect(
                color = HudForeground.copy(alpha = 0.20f),
                topLeft = Offset(0f, top),
                size = Size(size.width, barPx),
                cornerRadius = corner,
            )
            // 已播部分
            val filled = size.width * fraction
            if (filled > 0f) {
                drawRoundRect(
                    color = HudForeground.copy(alpha = if (enabled) 1f else 0.75f),
                    topLeft = Offset(0f, top),
                    size = Size(filled, barPx),
                    cornerRadius = corner,
                )
            }
            // 拖柄。拖动中放大一点 —— 手指挡住了它，需要更强的反馈。
            drawCircle(
                color = HudForeground,
                radius = barPx * (if (dragged >= 0f) 1.6f else 0.95f),
                center = Offset(filled.coerceIn(barPx, size.width - barPx), cy),
            )
        }
    }
}

private const val NO_DRAG = -1f

/**
 * 一颗圆形控制键。
 *
 * 用 `Box + clickable` 而不是 `IconButton`：后者为了保证 48dp 触控区会自带一圈内边距，
 * 在按比例算出来的尺寸里会把图标顶小一圈。这里尺寸本身就是按可用空间反推的，
 * 已经远大于 48dp，不需要 `IconButton` 再补一次。
 */
@Composable
private fun ControlButton(
    size: Dp,
    iconSize: Dp,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
    icon: @Composable (Color, Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.06f))
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = description),
        contentAlignment = Alignment.Center,
    ) {
        icon(HudForeground.copy(alpha = if (enabled) 1f else 0.35f), Modifier.size(iconSize))
    }
}
