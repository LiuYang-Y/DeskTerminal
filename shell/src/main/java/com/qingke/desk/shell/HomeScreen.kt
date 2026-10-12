package com.qingke.desk.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.model.HomeCardKind
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.deskGlassSource
import com.qingke.desk.feature.home.CommuteCard
import com.qingke.desk.feature.home.HomeCardTile
import com.qingke.desk.feature.media.MediaCardTile
import com.qingke.desk.feature.media.MediaCommand
import com.qingke.desk.feature.media.MediaUiState
import com.qingke.desk.map.MapPreviewCard
import com.qingke.desk.map.MapViewHost
import com.qingke.desk.map.navi.NaviGuidance
import com.qingke.desk.map.navi.NaviShape
import kotlinx.coroutines.launch

/** 首页页数。今天只有两页；以后加页只改这个数与 [MapPage] / [InfoPage] 的分支。 */
private const val HOME_PAGE_COUNT: Int = 2

/**
 * 供 `androidTest` 定位用的测试标签。
 *
 * 为什么需要：这台基准机是 production 构建，`adb shell input` 一律被拒
 * （INJECT_EVENTS），"点地图卡进全屏页""在地图卡上横滑能不能翻页"这两条
 * 只能靠**进程内的 Compose UI 测试**（它走 Compose 自己的命中测试与手势分发，
 * 不经过系统 InputManager）。测试要按节点找元素，就用这几个 tag。
 */
internal const val HOME_MAP_CARD_TAG = "home-map-card"
internal const val HOME_CLOCK_CARD_TAG = "home-clock-card"

internal fun pageDotTag(index: Int): String = "page-dot-$index"

/**
 * 首页：**两页横滑**（CarPlay 仪表盘式），底部页码圆点。
 *
 * 2026-10-12 由"3×2 大卡栅格"改成两页，原因是一屏塞不下我们真正想放大看的东西：
 * 地图卡要占三分之二宽，时钟卡要能显示一个真正的大时钟，剩下两张小卡各得一块。
 * 六张卡挤在一屏的结果是每张都不够大。
 *
 * 纵向预算（基准机横屏 361 dp，侧栏与挖孔之外的**内容区**）：
 *   内容区 361 → 上下各留 12 → 337；页码行独占 24 → 每页卡片 313 高。
 * 横向：内容区 666 → 左右各留 20、gap 12 → 626 可用，
 *   按 2:1 分给主卡与副列：主卡 409、副列 205（409/626 = 65.4%，即"约三分之二"）。
 *
 * 第 1 页：地图卡（409×313） | 「回家/去公司」占位（205×150）+ 音乐卡（205×150）
 * 第 2 页：时钟卡（409×313，大字号） | 天气卡（205×313）
 *
 * 玻璃采样源套在内容区上：侧栏是浮在它之上的一层玻璃，采的就是这块内容。
 */
@Composable
fun HomeScreen(
    cards: List<HomeCard>,
    driftX: Dp = 0.dp,
    driftY: Dp = 0.dp,
    /**
     * 地图卡点击 → 进全屏地图页。
     *
     * 地图卡是**预览模式**：不响应拖动缩放、View 层吞掉触摸（见 AMapCanvas 的 preview），
     * 整卡只有"点进去"这一个动作 —— 这样横滑手势才能完整交给 Pager。
     */
    onOpenMap: () -> Unit = {},
    /**
     * 地图单实例宿主，由 [DeskRoot] 创建后下发 —— 首页卡与全屏页共用同一个 MapView，
     * 来回切不重建（见 [MapViewHost]）。
     */
    mapHost: MapViewHost? = null,
    /**
     * 「回家 / 去公司」两个槽位当前存的地点名。null = 没设置过（卡片显示灰态"设置地址"）。
     * 只传名字而不是完整地点：卡片模块不需要知道坐标类型（见 [CommuteCard]）。
     */
    homeTitle: String? = null,
    workTitle: String? = null,
    /** 点"回家" / "去公司"：由 :shell 负责算路并进导航页。 */
    onHomeClick: () -> Unit = {},
    onWorkClick: () -> Unit = {},
    /**
     * 外部翻页请求（只有 debug 验证驱动会发，见 [ValidationDriver]）。
     * 带序号，所以"连续两次请求同一页"也是两个事件。
     */
    pageRequest: PageRequest? = null,
    /** 玻璃背景层句柄，由 [DeskRoot] 创建后下发。 */
    glassBackdrop: LayerBackdrop? = null,
    mediaState: MediaUiState = MediaUiState.Checking,
    onMediaCommand: (MediaCommand) -> Unit = {},
    onMediaRequestPermission: () -> Unit = {},
    /**
     * 导航总览：整条路线的几何（含包围盒）。非 null 时地图卡画"整条路线 + 车点"。
     * 由 [DeskRoot] 从 [com.qingke.desk.map.navi.NaviSession] 订阅后下发 ——
     * 首页只负责画，判断"在不在导航"不在这一层。
     */
    naviShape: NaviShape? = null,
    /**
     * 实时引导。非 null 时在地图卡上叠一层引导信息：下一转向、距离、路名、
     * 剩余里程与预计时间（见 [NaviCardOverlay]）。
     */
    naviGuidance: NaviGuidance? = null,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState { HOME_PAGE_COUNT }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pageRequest) {
        pageRequest?.let { pagerState.scrollToPage(it.page.coerceIn(0, HOME_PAGE_COUNT - 1)) }
    }

    // 地图只在第一页可见时渲染。翻到第二页就停渲染（AMap.setDisableRender）——
    // MapView 与 camera 状态都留着，但一帧都不画，这是最有效的省电手段。
    val mapVisible = pagerState.currentPage == 0

    Column(
        modifier = modifier
            .fillMaxSize()
            .deskGlassSource(glassBackdrop),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            // 两页都留在组合窗口内。默认是 0 —— 那样翻到第 2 页时第 1 页会被 dispose，
            // MapView 跟着走 onDispose，翻回来就是一次重建。这条是"翻页不重建"的关键。
            beyondViewportPageCount = HOME_PAGE_COUNT - 1,
        ) { page ->
            when (page) {
                0 -> MapPage(
                    mapHost = mapHost,
                    renderEnabled = mapVisible,
                    onOpenMap = onOpenMap,
                    mediaState = mediaState,
                    onMediaCommand = onMediaCommand,
                    onMediaRequestPermission = onMediaRequestPermission,
                    homeTitle = homeTitle,
                    workTitle = workTitle,
                    onHomeClick = onHomeClick,
                    onWorkClick = onWorkClick,
                    naviShape = naviShape,
                    naviGuidance = naviGuidance,
                    driftX = driftX,
                    driftY = driftY,
                )

                1 -> InfoPage(
                    cards = cards,
                    driftX = driftX,
                    driftY = driftY,
                )
            }
        }

        PageIndicator(
            pageCount = HOME_PAGE_COUNT,
            currentPage = pagerState.currentPage,
            onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
        )
    }
}

/** 每页统一的内边距：左右 20（呼吸）、上下 12。页码行不占这里的空间。 */
private val pagePadding: PaddingValues = PaddingValues(
    start = DeskDimens.screenPadding,
    end = DeskDimens.screenPadding,
    top = DeskDimens.cardAreaTopPadding,
    bottom = DeskDimens.cardAreaBottomPadding,
)

/**
 * 第 1 页：地图卡 + 右侧一列两张小卡。
 *
 * 权重 [DeskDimens.HOME_PAGE_MAIN_WEIGHT] : [DeskDimens.HOME_PAGE_SIDE_WEIGHT] = 2 : 1，
 * 写权重而不是写死宽度，换台机器自动按比例放大。
 */
@Composable
private fun MapPage(
    mapHost: MapViewHost?,
    renderEnabled: Boolean,
    onOpenMap: () -> Unit,
    mediaState: MediaUiState,
    onMediaCommand: (MediaCommand) -> Unit,
    onMediaRequestPermission: () -> Unit,
    homeTitle: String?,
    workTitle: String?,
    onHomeClick: () -> Unit,
    onWorkClick: () -> Unit,
    naviShape: NaviShape?,
    naviGuidance: NaviGuidance?,
    driftX: Dp,
    driftY: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(pagePadding),
        horizontalArrangement = Arrangement.spacedBy(DeskDimens.gridGap),
    ) {
        Box(
            modifier = Modifier
                .weight(DeskDimens.HOME_PAGE_MAIN_WEIGHT)
                .fillMaxHeight(),
        ) {
            MapPreviewCard(
                // testTag 只服务 androidTest 的定位（Compose 测试按节点找元素），
                // 界面上不显示任何东西。
                modifier = Modifier.testTag(HOME_MAP_CARD_TAG),
                host = mapHost,
                renderEnabled = renderEnabled,
                onOpenFull = onOpenMap,
                naviShape = naviShape,
                naviCar = naviGuidance?.car,
            )

            // 引导浮层叠在卡片之上。它**不吃触摸**（见 NaviCardOverlay 的说明），
            // 所以"点卡片回全屏导航页"在地图上是通的，在浮层上也是通的。
            naviGuidance?.let { NaviCardOverlay(guidance = it) }
        }

        Column(
            modifier = Modifier
                .weight(DeskDimens.HOME_PAGE_SIDE_WEIGHT)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(DeskDimens.gridGap),
        ) {
            // 「回家 / 去公司」：已设地址则可点直达（见 :shell 的 onCommute*）。
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                CommuteCard(
                    driftX = driftX,
                    driftY = driftY,
                    homeTitle = homeTitle,
                    workTitle = workTitle,
                    onHomeClick = onHomeClick,
                    onWorkClick = onWorkClick,
                    onSetupClick = onOpenMap,
                )
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                MediaCardTile(
                    state = mediaState,
                    onCommand = onMediaCommand,
                    onRequestPermission = onMediaRequestPermission,
                    driftX = driftX,
                    driftY = driftY,
                )
            }
        }
    }
}

/**
 * 第 2 页：时钟卡（大，409×313）+ 天气卡（205×313）。
 *
 * 同样是 2:1。时钟放大到 409 宽之后，字号上限要从 56.sp 提到
 * [DeskDimens.clockTimeLarge]（96.sp）—— 卡大了四倍字没变，看着就像忘了适配。
 */
@Composable
private fun InfoPage(
    cards: List<HomeCard>,
    driftX: Dp,
    driftY: Dp,
) {
    val clock = cards.firstOrNull { it.kind == HomeCardKind.CLOCK }
    val weather = cards.firstOrNull { it.kind == HomeCardKind.WEATHER }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(pagePadding),
        horizontalArrangement = Arrangement.spacedBy(DeskDimens.gridGap),
    ) {
        if (clock != null) {
            Box(
                modifier = Modifier
                    .weight(DeskDimens.HOME_PAGE_MAIN_WEIGHT)
                    .fillMaxHeight()
                    .testTag(HOME_CLOCK_CARD_TAG),
            ) {
                HomeCardTile(
                    card = clock,
                    driftX = driftX,
                    driftY = driftY,
                    maxClockSp = DeskDimens.clockTimeLarge,
                )
            }
        }
        if (weather != null) {
            Box(modifier = Modifier.weight(DeskDimens.HOME_PAGE_SIDE_WEIGHT).fillMaxHeight()) {
                HomeCardTile(
                    card = weather,
                    driftX = driftX,
                    driftY = driftY,
                )
            }
        }
    }
}

/**
 * 页码圆点行。独占 [DeskDimens.pageIndicatorHeight]，不挤占卡片高度。
 *
 * 圆点可点（点击区 32×24，比圆点本身大一圈，手指不用瞄准）：
 * 它与横滑是两条并存的操作路径，不是替代关系。
 */
@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DeskDimens.pageIndicatorHeight),
        horizontalArrangement = Arrangement.Center,
        // Row 的纵向参数是 verticalAlignment（不是 verticalArrangement，那是 Column 的）。
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val selected = index == currentPage
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = DeskDimens.pageIndicatorHeight)
                    .clip(CircleShape)
                    // selectable 而不是 clickable：圆点表达"选中的是哪一页"，
                    // 无障碍上就有了 selected 语义（而且 androidTest 能直接断言选中态）。
                    .selectable(selected = selected, onClick = { onSelect(index) })
                    .testTag(pageDotTag(index)),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            if (selected) {
                                DeskDimens.pageDotSelectedSize
                            } else {
                                DeskDimens.pageDotSize
                            },
                        )
                        .clip(CircleShape)
                        .background(
                            if (selected) {
                                Color.White.copy(alpha = 0.9f)
                            } else {
                                Color.White.copy(alpha = 0.4f)
                            },
                        ),
                )
            }
            if (index < pageCount - 1) {
                Box(modifier = Modifier.size(width = DeskDimens.pageDotGap, height = 1.dp))
            }
        }
    }
}

/**
 * 侧栏条目。**5 项**，不是 6 项 —— 见 [SideBar] 顶部的算术：6 × 64dp 放不进 361dp。
 * 「智能家居」暂时不在列表里，但 [ShellIds.SMART_HOME] 与
 * [handleShellSelect] 里的分支都保留，加回来只是往这里补一行。
 *
 * 「手机投屏」留在侧栏：2026-10-12 首页那张投屏卡删掉了，侧栏这个入口不动 ——
 * 投屏是一次性的动作，放在常驻导航里比放在信息卡里合适。
 */
internal fun defaultShellItems(): List<ShellItem> = listOf(
    ShellItem(ShellIds.HOME, "首页", Icons.Default.Home),
    ShellItem(ShellIds.CLOCK, "时钟", Icons.Default.Schedule),
    ShellItem(ShellIds.MUSIC, "音乐", Icons.Default.MusicNote),
    ShellItem(ShellIds.MAP, "地图", Icons.Default.Map),
    ShellItem(ShellIds.PROJECTION, "手机投屏", Icons.Default.Cast),
)
