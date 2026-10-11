package com.qingke.desk.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.model.HomeCardKind
import com.qingke.desk.core.ui.CardGrid
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.deskGlassSource
import com.qingke.desk.core.ui.rememberDeskGlassBackdrop
import com.qingke.desk.feature.home.HomeCardTile
import com.qingke.desk.feature.media.MediaCardTile
import com.qingke.desk.feature.media.MediaCommand
import com.qingke.desk.feature.media.MediaUiState

/**
 * 首页：内容区铺满 + 底部图标栏浮在其上。
 *
 * 之所以从"Column 里加一行"改成"Box 上浮一层"：液态玻璃采的是**它背后那一层内容**，
 * 若底栏独占一行、背后只有纯底色，模糊与折射都无从体现（详见 DeskGlass 的说明）。
 * 现在内容区直接铺到屏幕底，底栏只占最下面一条并允许内容探进去
 * [DeskDimens.dockContentOverlap]，玻璃才有东西可采样。
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
     * 音乐卡状态。由 [DeskRoot] 从 :feature:media 的监视器取出后下发。
     * MUSIC 那一张卡不走 HomeCardTile —— 它有实时数据和三个控制按钮，
     * 不是纯展示卡。
     */
    mediaState: MediaUiState = MediaUiState.Checking,
    onMediaCommand: (MediaCommand) -> Unit = {},
    onMediaRequestPermission: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 玻璃背景层。ENABLED=false 时它返回 null，下面两个 Modifier 自动退化为空操作，
    // 不需要在任何调用点写分支。
    val glassBackdrop = rememberDeskGlassBackdrop(
        background = MaterialTheme.colorScheme.background,
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // 被录进背景层的**只有内容区**。把底栏也套进来会导致自采样：
        // 每帧把上一帧模糊过的画面再录一遍，越糊越糊。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .deskGlassSource(glassBackdrop),
        ) {
            val rows = remember(maxHeight, cards.size) { computeRows(maxHeight) }
            CardGrid(
                itemCount = cards.size,
                columns = DeskDimens.GRID_COLUMNS,
                rows = rows,
                contentPadding = PaddingValues(
                    start = DeskDimens.screenPadding,
                    end = DeskDimens.screenPadding,
                    top = DeskDimens.screenPadding,
                    bottom = DeskDimens.dockContentBottomPadding,
                ),
            ) { index ->
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
        }

        DockBar(
            items = defaultDockItems(),
            selectedId = null,
            onSelect = onDockSelect,
            glassBackdrop = glassBackdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 第一版的固定条目。未实现的能力点击后由 :shell 给出提示。 */
internal fun defaultDockItems(): List<DockItem> = listOf(
    DockItem(DockIds.HOME, "首页", Icons.Default.Home),
    DockItem(DockIds.CLOCK, "时钟", Icons.Default.Schedule),
    DockItem(DockIds.MUSIC, "音乐", Icons.Default.MusicNote),
    DockItem(DockIds.SMART_HOME, "智能家居", Icons.Default.Lightbulb),
    DockItem(DockIds.PROJECTION, "手机投屏", Icons.Default.Cast),
)

/**
 * 可用高度能放下几行。
 *
 * 已按基准机（横屏 411 dp 高）核算：cardMinHeight 取 124 时可稳定得到 2 行；
 * 取 140 时会被压成 1 行。换机器不需要改这里，改 [DeskDimens.cardMinHeight] 即可。
 */
private fun computeRows(availableHeight: Dp): Int {
    val gap = DeskDimens.gridGap
    val unit = DeskDimens.cardMinHeight + gap
    if (unit <= 0.dp) return 1
    return ((availableHeight + gap) / unit).toInt().coerceAtLeast(1)
}
