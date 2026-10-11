package com.qingke.desk.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.model.HomeCardKind
import com.qingke.desk.core.ui.CardGrid
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.deskGlassSource
import com.qingke.desk.feature.home.HomeCardTile
import com.qingke.desk.feature.media.MediaCardTile
import com.qingke.desk.feature.media.MediaCommand
import com.qingke.desk.feature.media.MediaUiState
import kotlin.math.ceil

/**
 * 首页：一整块卡片栅格，占满"侧栏与挖孔之外"的全部区域。
 *
 * 2026-10-12 的两次减法：
 *  1. **顶部状态栏没了** —— Shizuku 指示改成右上角悬浮角标（只在异常时出现），
 *     为一条只放一颗小灯的横条让出 32dp 不划算；
 *  2. **底部图标栏没了** —— 它立起来变成侧栏，横向只吃 76dp（横向有 794dp），
 *     纵向一分不让。
 * 于是纵向预算从 231dp 涨到 337dp，卡片从 109.5dp 高长到 162.5dp ——
 * 这就是用户选的 H1（3×2 大卡）。
 *
 * 内容区仍然**铺到**容器底（不再为谁让路），因为已经没有浮在下面的栏了；
 * 真正需要的只是上下各 12dp 呼吸空间。
 *
 * 玻璃采样源套在内容区上：侧栏是浮在它之上的一层玻璃，采的就是这块内容。
 * 历史坑：曾经允许内容探进底栏 24dp 好给玻璃当素材，真机结果是第二排卡片被
 * 玻璃栏压住 —— 内容不该给装饰让路。
 */
@Composable
fun HomeScreen(
    cards: List<HomeCard>,
    driftX: Dp = 0.dp,
    driftY: Dp = 0.dp,
    onShellSelect: (ShellItem) -> Unit,
    /** 卡片点击。目前只有"手机投屏"这张卡响应。 */
    onCardSelect: (HomeCard) -> Unit,
    /**
     * 玻璃背景层句柄，由 [DeskRoot] 创建后下发 —— 它同时被内容区和侧栏用到，
     * 必须在两者共同的父层创建，不能各建一块（各建各的就采不到对方的内容）。
     */
    glassBackdrop: LayerBackdrop? = null,
    /**
     * 音乐卡状态。由 [DeskRoot] 从 :feature:media 的监视器取出后下发。
     * MUSIC 那一张卡不走 HomeCardTile —— 它有实时数据和三个控制按钮，
     * 不是纯展示卡。
     */
    mediaState: MediaUiState = MediaUiState.Checking,
    onMediaCommand: (MediaCommand) -> Unit = {},
    onMediaRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .deskGlassSource(glassBackdrop),
    ) {
        CardArea(
            cards = cards,
            columns = DeskDimens.GRID_COLUMNS,
            areaHeight = maxHeight,
            driftX = driftX,
            driftY = driftY,
            onCardSelect = onCardSelect,
            mediaState = mediaState,
            onMediaCommand = onMediaCommand,
            onMediaRequestPermission = onMediaRequestPermission,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 卡片区。
 *
 * 两件事在这里定：
 *  1. **行数**由可用高度反推（口径见 [usableHeight]）—— 换机器自动适配，
 *     不放不下时是少一行，而不是把卡片压扁；
 *  2. **超出可见行数时改为可滚动** —— 内容按总行数算出确定高度，可见区不变。
 *     今天 6 张卡 3 列正好 2 行，走不到滚动分支。
 */
@Composable
internal fun CardArea(
    cards: List<HomeCard>,
    columns: Int,
    areaHeight: Dp,
    driftX: Dp,
    driftY: Dp,
    onCardSelect: (HomeCard) -> Unit,
    mediaState: MediaUiState,
    onMediaCommand: (MediaCommand) -> Unit,
    onMediaRequestPermission: () -> Unit,
    /**
     * 反推行数用的"单卡最小高度"。首页用 [DeskDimens.cardMinHeight]（108 → 2 行大卡）；
     * 地图布局的浮层只有 240dp 宽、卡片是 2 列，同样的 108 会把 6 张卡压成 2 行
     * 再滚动，单卡变成 98×162 的瘦长条 —— 所以那边传 96 换 3 行（98×104，接近方形）。
     */
    minCardHeight: Dp = DeskDimens.cardMinHeight,
    contentPadding: PaddingValues = PaddingValues(
        start = DeskDimens.screenPadding,
        end = DeskDimens.screenPadding,
        top = DeskDimens.cardAreaTopPadding,
        bottom = DeskDimens.cardAreaBottomPadding,
    ),
    modifier: Modifier = Modifier,
) {
    val rows = remember(areaHeight, cards.size, minCardHeight) {
        computeRows(usableHeight(areaHeight), minCardHeight)
    }
    val totalRows = remember(columns, cards.size) {
        ceil(cards.size / columns.toFloat()).toInt().coerceAtLeast(1)
    }
    val layoutsCards: @Composable (Int) -> Unit = { index ->
        val card = cards[index]
        if (card.kind == HomeCardKind.MUSIC) {
            MediaCardTile(
                state = mediaState,
                onCommand = onMediaCommand,
                onRequestPermission = onMediaRequestPermission,
                driftX = driftX,
                driftY = driftY,
            )
        } else {
            HomeCardTile(
                card = card,
                driftX = driftX,
                driftY = driftY,
                onClick = if (card.kind == HomeCardKind.PROJECTION) {
                    { onCardSelect(card) }
                } else {
                    null
                },
            )
        }
    }

    if (totalRows <= rows) {
        CardGrid(
            itemCount = cards.size,
            modifier = modifier,
            columns = columns,
            rows = rows,
            contentPadding = contentPadding,
            itemContent = layoutsCards,
        )
        return
    }

    val visibleHeight = usableHeight(areaHeight).coerceAtLeast(0.dp)
    val gap = DeskDimens.gridGap
    val cardHeight = ((visibleHeight - gap * (rows - 1)) / rows).coerceAtLeast(1.dp)
    val contentHeight = cardHeight * totalRows + gap * (totalRows - 1)

    Box(
        modifier = modifier
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        CardGrid(
            itemCount = cards.size,
            modifier = Modifier
                .fillMaxWidth()
                .height(contentHeight),
            columns = columns,
            rows = totalRows,
            contentPadding = PaddingValues(0.dp),
            itemContent = layoutsCards,
        )
    }
}

/**
 * 侧栏条目。**5 项**，不是 6 项 —— 见 [SideBar] 顶部的算术：6 × 64dp 放不进 361dp。
 * 「智能家居」暂时不在列表里，但 [ShellIds.SMART_HOME] 与
 * [handleShellSelect] 里的分支都保留，加回来只是往这里补一行。
 */
internal fun defaultShellItems(): List<ShellItem> = listOf(
    ShellItem(ShellIds.HOME, "首页", Icons.Default.Home),
    ShellItem(ShellIds.CLOCK, "时钟", Icons.Default.Schedule),
    ShellItem(ShellIds.MUSIC, "音乐", Icons.Default.MusicNote),
    ShellItem(ShellIds.MAP, "地图", Icons.Default.Map),
    ShellItem(ShellIds.PROJECTION, "手机投屏", Icons.Default.Cast),
)

/**
 * 卡片区真正能用的高度：容器高度扣掉上下呼吸空间。
 *
 * 口径统一在这里，**不写第二遍** —— 以前 computeRows 用整段内容区高度、
 * 铺卡时又用扣过内边距的高度，两边不一致，结果"算出来 2 行"但每行实际矮了一截。
 */
private fun usableHeight(areaHeight: Dp): Dp =
    (areaHeight - DeskDimens.cardAreaTopPadding - DeskDimens.cardAreaBottomPadding)
        .coerceAtLeast(0.dp)

/**
 * 可用高度能放下几行。
 *
 * 基准机（横屏 361dp，上下各留 12 → 可用 337dp）核算：
 * cardMinHeight 取 108 → floor((337 + 12) / 120) = 2 行，单卡 162.5dp；
 * 取 96（地图浮层那档）→ 3 行，单卡 104dp。
 * 换机器不需要改这里，改传入的 minCardHeight 即可。
 */
private fun computeRows(availableHeight: Dp, minCardHeight: Dp): Int {
    val gap = DeskDimens.gridGap
    val unit = minCardHeight + gap
    if (unit <= 0.dp) return 1
    return ((availableHeight + gap) / unit).toInt().coerceAtLeast(1)
}
