package com.qingke.desk.shell

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qingke.desk.core.data.SidebarSide
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.feature.media.MediaCommand
import com.qingke.desk.feature.media.MediaUiState

/**
 * 地图布局里的卡片浮层（方案 C + 用户选的 M1 档）。
 *
 * 地图**铺满整屏**，卡片是压在上面的一层，可以收成一条手柄 —— 收起之后地图
 * 全部露出来，这才是"地图优先"的那一档。
 *
 * ---------------------------------------------------------------------------
 * 为什么这里**不用液态玻璃**
 *
 * MapView 是 SurfaceView，走独立图层，Compose 的背景层采不到它的内容
 * （会录成一片黑）。给浮层上玻璃只会得到一块发黑的板子 —— 这个坑在底栏时期
 * 就在真机上踩过一次。所以这里用**半透明实色 + 圆角**，是刻意的降级，不是偷懒。
 *
 * ---------------------------------------------------------------------------
 * 宽度账（基准机 794dp 宽）
 *
 *   挖孔 40 + 侧栏 76 + 卡片区 240 + 手柄 24 = 380
 *   地图实际露出 ≈ 794 - 380 = 414 dp
 * 收起之后露出 ≈ 794 - 40 - 76 - 24 = 654 dp。
 *
 * @param sidebarSide 手柄贴屏幕的哪一侧：浮层永远待在**侧栏的对侧**，
 *                    手柄再贴在浮层的外侧边缘（手指往屏幕边上一划就能摸到）。
 */
@Composable
fun MapCardPane(
    cards: List<HomeCard>,
    sidebarSide: SidebarSide,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    driftX: Dp = 0.dp,
    driftY: Dp = 0.dp,
    onCardSelect: (HomeCard) -> Unit = {},
    mediaState: MediaUiState = MediaUiState.Checking,
    onMediaCommand: (MediaCommand) -> Unit = {},
    onMediaRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val paneWidth by animateDpAsState(
        targetValue = if (collapsed) 0.dp else DeskDimens.mapPaneWidth,
        label = "mapPaneWidth",
    )
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f)
    val shape = RoundedCornerShape(DeskDimens.cardCorner)

    val handleFirst = sidebarSide == SidebarSide.RIGHT

    Row(
        modifier = modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (handleFirst) {
            MapPaneHandle(
                collapsed = collapsed,
                expandedTowardsEnd = true,
                surface = surface,
                onClick = onToggleCollapsed,
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .width(paneWidth)
                .fillMaxHeight()
                .padding(vertical = DeskDimens.cardAreaTopPadding)
                .clip(shape)
                .background(surface),
        ) {
            // 收起过程中宽度会缩到 0，那几帧里卡片不该还在测 ——
            // 零点几 dp 的约束会让 CardGrid 里的文本量出很怪的结果。
            if (paneWidth > 24.dp && maxHeight > 0.dp) {
                CardArea(
                    cards = cards,
                    columns = DeskDimens.MAP_PANE_COLUMNS,
                    areaHeight = maxHeight,
                    driftX = driftX,
                    driftY = driftY,
                    onCardSelect = onCardSelect,
                    mediaState = mediaState,
                    onMediaCommand = onMediaCommand,
                    onMediaRequestPermission = onMediaRequestPermission,
                    minCardHeight = MAP_PANE_MIN_CARD_HEIGHT,
                    contentPadding = PaddingValues(
                        start = 12.dp,
                        end = 12.dp,
                        top = 8.dp,
                        bottom = 8.dp,
                    ),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (!handleFirst) {
            MapPaneHandle(
                collapsed = collapsed,
                expandedTowardsEnd = false,
                surface = surface,
                onClick = onToggleCollapsed,
            )
        }
    }
}

/**
 * 浮层里反推行数用的单卡最小高度：96dp。
 *
 * 108（首页那档）在 337dp 里只排得下 2 行，6 张卡 2 列就要滚动，单卡变成
 * 98 × 162 的瘦长条；96 能排 3 行，单卡 98 × 104 —— 接近方形，6 张一次看完。
 */
private val MAP_PANE_MIN_CARD_HEIGHT: Dp = 96.dp

/**
 * 收起 / 展开手柄。始终贴在浮层的外侧边缘。
 *
 * @param expandedTowardsEnd 展开时卡片往哪边长。true = 往手柄之后（右）长，
 *                           即手柄在左；false = 手柄在右，卡片往左长。
 *                           箭头据此翻转，"点它会往哪个方向动"一眼就看得出。
 */
@Composable
private fun MapPaneHandle(
    collapsed: Boolean,
    expandedTowardsEnd: Boolean,
    surface: Color,
    onClick: () -> Unit,
) {
    // 展开时箭头指向卡片要退去的方向；收起时指向卡片长出来的方向。
    val pointsEnd = if (collapsed) expandedTowardsEnd else !expandedTowardsEnd

    Box(
        modifier = Modifier
            .size(
                width = DeskDimens.mapPaneCollapsedWidth,
                height = DeskDimens.mapPaneHandleHeight,
            )
            .clip(RoundedCornerShape(DeskDimens.mapPaneCollapsedWidth / 2))
            .background(surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (pointsEnd) {
                Icons.AutoMirrored.Filled.ArrowForward
            } else {
                Icons.AutoMirrored.Filled.ArrowBack
            },
            contentDescription = if (collapsed) "展开卡片" else "收起卡片",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(20.dp),
        )
    }
}
