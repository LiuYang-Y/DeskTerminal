package com.qingke.desk.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.MaterialTheme
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
import com.qingke.desk.hosting.ShizukuStatus
import com.qingke.desk.hosting.ui.ShizukuStatusBar
import kotlin.math.ceil

/**
 * 首页：状态栏 + 内容区铺满。
 *
 * 之所以从"Column 里加一行"改成"内容铺满 + 底栏浮在其上"：液态玻璃采的是
 * **它背后那一层内容**，若底栏独占一行、背后只有纯底色，模糊与折射都无从体现
 * （详见 DeskGlass 的说明）。现在内容区直接铺到屏幕底，底栏由 [DeskRoot] 统一浮在底部。
 *
 * 但**卡片不铺到屏幕底**：卡片区底部按 [DeskDimens.gridBottomPadding] 停在底栏
 * 上边缘齐平处。曾经允许内容探进底栏 24 dp 好给玻璃当采样素材，真机结果是
 * 第二排卡片被玻璃栏压住（天气卡明细行、待办卡第三行、音乐卡进度条全被挡），
 * 2026-10-11 改回"内容不与底栏重叠"。
 *
 * 底栏上提到 [DeskRoot] 是因为**它不再只属于首页**：地图布局（:hosting 的面板）
 * 也要从底栏切走，否则把第三方应用的画面铺出来之后就没有出口了。
 *
 * 顶部状态栏是 2026-10-11 加的，放 Shizuku 的常驻指示。它**占实位**，不是浮层：
 * 横向 361 dp 的高度里，浮层压到卡片上比少 20 dp 更糟。纵向预算的账记在
 * [DeskDimens] 的文件头，改这里任何一个值都要回去重算那一行。
 *
 * 栅格行数不写死 —— 由可用高度除以"卡片最小高度"反推，
 * 空间不够时少放一行，而不是把卡片压扁。这样同一份代码
 * 在 5 寸和 7 寸横屏上都不会出怪比例。
 */
@Composable
fun HomeScreen(
    cards: List<HomeCard>,
    driftX: Dp = 0.dp,
    driftY: Dp = 0.dp,
    onDockSelect: (DockItem) -> Unit,
    /** 卡片点击。目前只有"手机投屏"这张卡响应。 */
    onCardSelect: (HomeCard) -> Unit,
    /**
     * 玻璃背景层句柄，由 [DeskRoot] 创建后下发 —— 它同时被内容区和底栏用到，
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
    /**
     * Shizuku 的当前状态，由 [DeskRoot] 在每次回到前台时现查后下发。
     * 顶部状态栏把它显示成一颗常驻的小灯 —— 这个模块停了不会有任何别的征兆，
     * 只能靠它提前说。
     */
    shizukuStatus: ShizukuStatus = ShizukuStatus.READY,
    /** 点状态栏上的指示 —— 进引导页。 */
    onShizukuIndicatorClick: () -> Unit = {},
    /**
     * 非空时切到**地图布局**：右侧让出一块竖屏面板给 :hosting 的虚拟屏，
     * 卡片退到左侧，底栏原地不动。
     *
     * 面板只有画面出口，没有自己的状态提示 —— 被挡住 / 失败由 [DeskRoot]
     * 用整屏提示盖住（面板只有 230×307 dp，塞不下三段步骤加两个按钮）。
     */
    hostingPanel: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Spacer(modifier = Modifier.height(DeskDimens.statusBarTopPadding))
        ShizukuStatusBar(
            status = shizukuStatus,
            onClick = onShizukuIndicatorClick,
        )

        // 被录进背景层的**只有内容区**。把底栏也套进来会导致自采样：
        // 每帧把上一帧模糊过的画面再录一遍，越糊越糊。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .deskGlassSource(glassBackdrop),
        ) {
            val areaHeight = maxHeight

            if (hostingPanel == null) {
                CardArea(
                    cards = cards,
                    columns = DeskDimens.GRID_COLUMNS,
                    areaHeight = areaHeight,
                    driftX = driftX,
                    driftY = driftY,
                    onCardSelect = onCardSelect,
                    mediaState = mediaState,
                    onMediaCommand = onMediaCommand,
                    onMediaRequestPermission = onMediaRequestPermission,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 面板高度吃掉"状态栏以下、底栏以上"的整段，宽度由竖屏比例反推 ——
                // 面板必须尽量大，它是这里唯一要看清的东西。
                val panelHeight = (
                    areaHeight -
                        DeskDimens.hostingPanelTopGap -
                        DeskDimens.hostingPanelBottomGap
                    ).coerceAtLeast(0.dp)
                val panelWidth = panelHeight * DeskDimens.HOSTING_PANEL_ASPECT

                // 右侧留 [DeskDimens.screenPadding]：面板虽然靠右站，
                // 但不该贴着屏幕物理边缘 —— 左边卡片有 20 dp 呼吸空间，
                // 面板贴边会显得是"没做完"。
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = DeskDimens.screenPadding),
                ) {
                    CardArea(
                        cards = cards,
                        columns = DeskDimens.GRID_COLUMNS,
                        areaHeight = areaHeight,
                        driftX = driftX,
                        driftY = driftY,
                        onCardSelect = onCardSelect,
                        mediaState = mediaState,
                        onMediaCommand = onMediaCommand,
                        onMediaRequestPermission = onMediaRequestPermission,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    Spacer(modifier = Modifier.width(DeskDimens.hostingPanelGap))
                    // 用 offset 而不是 padding 定位：offset 不参与测量，
                    // 面板的"高度"就还是那 307 dp，不会被外边距挤小一圈。
                    Box(
                        modifier = Modifier
                            .offset(y = DeskDimens.hostingPanelTopGap)
                            .width(panelWidth)
                            .height(panelHeight),
                    ) {
                        hostingPanel()
                    }
                }
            }
        }
    }
}

/**
 * 卡片区。
 *
 * 两件事在这里定：
 *  1. **行数**由"状态栏以下、底栏以上"的可用高度反推（口径见 [usableHeight]）；
 *  2. **超出可见行数时改为可滚动** —— 内容按总行数算出确定高度，可见区不变。
 *     今天 6 张卡 3 列正好 2 行，走不到滚动分支；等卡片变多（或地图布局把
 *     卡片区挤窄）时，行为是"能滚"而不是"把第 3 行裁掉"。
 */
@Composable
private fun CardArea(
    cards: List<HomeCard>,
    columns: Int,
    areaHeight: Dp,
    driftX: Dp,
    driftY: Dp,
    onCardSelect: (HomeCard) -> Unit,
    mediaState: MediaUiState,
    onMediaCommand: (MediaCommand) -> Unit,
    onMediaRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(areaHeight, cards.size) { computeRows(usableHeight(areaHeight)) }
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
            contentPadding = PaddingValues(
                start = DeskDimens.screenPadding,
                end = DeskDimens.screenPadding,
                top = DeskDimens.gridTopPadding,
                bottom = DeskDimens.gridBottomPadding,
            ),
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
            .padding(
                start = DeskDimens.screenPadding,
                end = DeskDimens.screenPadding,
                top = DeskDimens.gridTopPadding,
                bottom = DeskDimens.gridBottomPadding,
            )
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

/** 第一版的固定条目。未实现的能力点击后由 :shell 给出提示。 */
internal fun defaultDockItems(): List<DockItem> = listOf(
    DockItem(DockIds.HOME, "首页", Icons.Default.Home),
    DockItem(DockIds.CLOCK, "时钟", Icons.Default.Schedule),
    DockItem(DockIds.MUSIC, "音乐", Icons.Default.MusicNote),
    DockItem(DockIds.SMART_HOME, "智能家居", Icons.Default.Lightbulb),
    DockItem(DockIds.HOSTING, "地图", Icons.Default.Map),
    DockItem(DockIds.PROJECTION, "手机投屏", Icons.Default.Cast),
)

/**
 * 卡片区真正能用的高度：内容区高度扣掉上留白与底部让给底栏的那一段。
 *
 * 口径统一在这里，**不写第二遍** —— 以前 computeRows 用整段内容区高度、
 * 铺卡时又用扣过内边距的高度，两边不一致，结果"算出来 2 行"但每行实际矮了一截。
 */
private fun usableHeight(areaHeight: Dp): Dp =
    (areaHeight - DeskDimens.gridTopPadding - DeskDimens.gridBottomPadding).coerceAtLeast(0.dp)

/**
 * 可用高度能放下几行。
 *
 * 已按基准机（横屏 361 dp 高，状态栏 38 + 底栏让位 82 → 可用 235 dp）核算：
 * cardMinHeight 取 108 时得到 2 行；取 140 时会被压成 1 行。
 * 换机器不需要改这里，改 [DeskDimens.cardMinHeight] 即可。
 */
private fun computeRows(availableHeight: Dp): Int {
    val gap = DeskDimens.gridGap
    val unit = DeskDimens.cardMinHeight + gap
    if (unit <= 0.dp) return 1
    return ((availableHeight + gap) / unit).toInt().coerceAtLeast(1)
}
