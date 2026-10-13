package com.qingke.desk.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * **按可用空间反推**尺寸的统一入口（2026-10-13 建）。
 *
 * ---------------------------------------------------------------------------
 * 这个文件存在的原因
 *
 * 之前 [DeskDimens] 里有一批"按某一台机器量出来"的常量：380、332、460、420……
 * 每一条的注释都写着"基准机上是 X dp"。换一台机器这些数字立刻失去依据 ——
 * 实测换机当天就靠它们现形了两个 bug（地图页右侧 257 dp 纯黑、"开始导航"被顶出屏幕）。
 *
 * 规则很简单：**凡是会被屏幕尺寸影响的尺寸，不许是常量，必须是 `可用空间 → 尺寸` 的函数。**
 * 仍然留在 [DeskDimens] 里的只允许是两类：
 *   · **触控下限、圆角、描边**这类"与屏幕无关"的观感常量；
 *   · **上限**（字号上限、投屏分辨率上限），上限不是布局尺寸，超出就夹住，不会挤坏布局。
 *
 * ---------------------------------------------------------------------------
 * 传入的"可用空间"是什么
 *
 * 一律指**内容区**：整屏 − 挖孔 − 侧栏。布局层（[com.qingke.desk.shell.DeskRoot]）
 * 算好之后传进来，这里不再重复扣一次。
 */

/**
 * 侧栏的排布方案。
 *
 * 三级降级，顺序是**先均分、再减内容、最后才缩间距**（用户口径）：
 *   1. [level] = [SidebarFitLevel.FULL]      每项够高 → 图标 + 文字标签都显示；
 *   2. [level] = [SidebarFitLevel.ICON_ONLY] 每项不够放标签 → 只显示图标；
 *   3. 间距从 [SidebarFit.PREFERRED_GAP] 往下退到 [SidebarFit.MIN_GAP]，把省下的高度还给条目。
 *
 * **条目的高度永不低于 [SidebarFit.MIN_TOUCH_DP]**。三级都退完还放不下时
 * [fitting] 为 false —— 这时不缩小触控区，而是把 [suggestedMaxItems] 报给调用方，
 * 让它减少入口数（历史上 6 项 → 5 项就是这么做的：宁可少一个入口，也不做小按钮）。
 */
enum class SidebarFitLevel(val label: String) {
    FULL("图标 + 文字"),
    ICON_ONLY("仅图标"),
}

data class SidebarFit(
    /** 每个条目的边长（dp）。已按可用高度均分。 */
    val itemSize: Dp,
    /** 条目间距（dp）。 */
    val gap: Dp,
    val level: SidebarFitLevel,
    /** 图标边长（dp）。 */
    val iconSize: Dp,
    /** 文字标签字号；[level] 为 ICON_ONLY 时这个值没有意义。 */
    val labelSize: TextUnit,
    /** 整列实际占的高度（dp）。 */
    val totalHeight: Dp,
    /**
     * 是否真的放得下。false = 连 [MIN_TOUCH_DP] 都排不下，
     * 此时 [itemSize] **被夹在** [MIN_TOUCH_DP]（不再往下缩），[overflow] 是超出量。
     */
    val fitting: Boolean,
    /** 放不下时超出可用高度多少（dp）。放得下时为 0。 */
    val overflow: Dp,
    /** 放不下时，按触控下限最多能排几个条目。调用方据此减少入口。 */
    val suggestedMaxItems: Int,
) {
    val showLabel: Boolean get() = level == SidebarFitLevel.FULL

    companion object {
        /**
         * 触控下限。**这是底线，不是可以再让的余量**（[DeskDimens.minTouchTarget] 同值）。
         * 桌面终端在手臂距离外操作，48dp 的 Material 基线不够用。
         */
        const val MIN_TOUCH_DP: Float = 64f

        /** 首选间距。 */
        const val PREFERRED_GAP_DP: Float = 6f

        /** 间距能退到的最小值。再小两个条目就视觉上连成一条了。 */
        const val MIN_GAP_DP: Float = 2f

        /** 条目边长的上限。屏幕很高时也不把图标做成巨无霸，多出来的高度留白居中。 */
        const val MAX_ITEM_DP: Float = 76f

        /** 要显示文字标签，条目至少要多高：图标 30 + 间距 4 + 标签行 ~16 = 50，取 54 留余量。 */
        const val LABEL_MIN_ITEM_DP: Float = 54f

        /** 只显示图标时，图标在条目里占的比例。 */
        const val ICON_RATIO: Float = 0.47f
    }
}

/**
 * 按可用高度给侧栏排布。
 *
 * @param availableHeightDp 侧栏能用的**纵向**空间（已扣掉挖孔与上下留白，但**不含**侧栏自身内边距）。
 * @param itemCount 入口数量。
 * @param innerPadding 侧栏玻璃块的内边距（上下各一份）。它不参与"均分"，先扣掉。
 */
fun fitSidebar(
    availableHeightDp: Float,
    itemCount: Int,
    innerPadding: Dp = DeskDimens.sideBarInnerPadding,
): SidebarFit {
    val count = itemCount.coerceAtLeast(1)
    val usable = (availableHeightDp - innerPadding.value * 2f).coerceAtLeast(0f)

    // 间距从大到小试：每退一档就把省下的高度还给条目，直到条目能到触控下限。
    var chosenGap = SidebarFit.MIN_GAP_DP
    var item = 0f
    for (gap in listOf(
        SidebarFit.PREFERRED_GAP_DP,
        4f,
        SidebarFit.MIN_GAP_DP,
    )) {
        item = if (count == 1) usable else (usable - (count - 1) * gap) / count
        chosenGap = gap
        if (item >= SidebarFit.MIN_TOUCH_DP) break
    }

    val rawItem = item
    // 屏幕很高时封顶，多出来的高度由外层居中消化。
    val capped = rawItem.coerceAtMost(SidebarFit.MAX_ITEM_DP)

    val fitting = rawItem >= SidebarFit.MIN_TOUCH_DP
    val finalItem = if (fitting) capped else SidebarFit.MIN_TOUCH_DP

    val total = count * finalItem + (count - 1) * chosenGap + innerPadding.value * 2f
    val overflow = (total - availableHeightDp).coerceAtLeast(0f)

    val level = if (finalItem >= SidebarFit.LABEL_MIN_ITEM_DP) {
        SidebarFitLevel.FULL
    } else {
        SidebarFitLevel.ICON_ONLY
    }

    val icon = (finalItem * SidebarFit.ICON_RATIO)
        .coerceAtMost(if (level == SidebarFitLevel.FULL) 30f else finalItem * 0.6f)

    val suggested = floor(
        (usable + SidebarFit.MIN_GAP_DP) /
            (SidebarFit.MIN_TOUCH_DP + SidebarFit.MIN_GAP_DP),
    ).toInt().coerceAtLeast(1)

    return SidebarFit(
        itemSize = finalItem.dp,
        gap = chosenGap.dp,
        level = level,
        iconSize = icon.dp,
        labelSize = DeskDimens.sideBarLabel,
        totalHeight = total.dp,
        fitting = fitting,
        overflow = overflow.dp,
        suggestedMaxItems = suggested,
    )
}

/**
 * 其余"按可用空间反推"的尺寸。
 *
 * 每个函数都写清**它替代了哪个写死的常量**以及**依据**，方便回头核对。
 */
object AdaptiveDimens {

    // ---------- 设置浮层 ----------

    /**
     * 浮层宽度。替代 [DeskDimens.settingsPaneWidth] 的 380.dp。
     *
     * 380 是按 794dp 屏算出来的（内容宽 678 的 56%）。改成比例之后：
     * 更宽的屏幕浮层按比例变宽（不超过 [PANE_MAX_WIDTH]），更窄的按比例变窄
     * （不低于 [PANE_MIN_WIDTH]，低于这个值里面的"侧栏位置"两个按钮就要换行了）。
     */
    fun settingsPaneWidth(contentWidthDp: Float): Dp =
        (contentWidthDp * PANE_WIDTH_RATIO)
            .coerceIn(PANE_MIN_WIDTH, PANE_MAX_WIDTH)
            .dp

    private const val PANE_WIDTH_RATIO: Float = 0.56f
    private const val PANE_MIN_WIDTH: Float = 280f
    private const val PANE_MAX_WIDTH: Float = 460f

    // ---------- 卡片栅格 ----------

    /**
     * 栅格列数。替代 [DeskDimens.GRID_COLUMNS] 的常量 3。
     *
     * 依据：单卡窄于 [MIN_CARD_WIDTH] 时卡内文字必然被截（实测 205dp 是下限附近）。
     * 列数由"可用宽 ÷ 单卡下限"反推，再夹到 [MIN_COLUMNS]..[MAX_COLUMNS]。
     */
    fun gridColumns(contentWidthDp: Float): Int =
        (contentWidthDp / MIN_CARD_WIDTH).roundToInt()
            .coerceIn(MIN_COLUMNS, MAX_COLUMNS)

    private const val MIN_CARD_WIDTH: Float = 200f
    private const val MIN_COLUMNS: Int = 2
    private const val MAX_COLUMNS: Int = 4

    // ---------- 导航搜索层 ----------

    /**
     * 搜索面板宽度。替代 `NaviSearchLayer.PANEL_MAX_WIDTH` 的 420.dp。
     *
     * 420 是按 786dp 内容宽算的（53%）。改成比例 + 夹取。
     */
    fun searchPanelWidth(contentWidthDp: Float): Dp =
        (contentWidthDp * 0.53f).coerceIn(280f, 520f).dp

    /** 搜索条宽度。替代 320.dp。 */
    fun searchBarWidth(contentWidthDp: Float): Dp =
        (contentWidthDp * 0.42f).coerceIn(220f, 380f).dp

    /** 结果列表限高。替代 250.dp —— 改成可用高的 60%。 */
    fun searchListHeight(contentHeightDp: Float): Dp =
        (contentHeightDp * 0.60f).coerceIn(160f, 320f).dp

    /**
     * 预览卡整体限高。**替代那个写死的 332.dp** —— 它就是换机当天
     * "开始导航按钮被顶出屏幕"的直接原因（内容区只剩 293dp，卡片要 332dp）。
     *
     * 可用高 = 内容区高 − 搜索条那一条（[SEARCH_BAR_BAND]）− 上下留白。
     * 结果再夹到 [PREVIEW_MIN_HEIGHT]..[PREVIEW_MAX_HEIGHT]：
     * 下限保证"至少能看见动作行"，上限防止在很高的屏上卡片被拉成一条。
     */
    fun previewMaxHeight(contentHeightDp: Float): Dp =
        (contentHeightDp - SEARCH_BAR_BAND - PREVIEW_MARGIN)
            .coerceIn(PREVIEW_MIN_HEIGHT, PREVIEW_MAX_HEIGHT)
            .dp

    /** 搜索条 + 它与卡片之间的间距，卡片下沿要让出这么多。 */
    private const val SEARCH_BAR_BAND: Float = 68f
    private const val PREVIEW_MARGIN: Float = 16f
    private const val PREVIEW_MIN_HEIGHT: Float = 200f
    private const val PREVIEW_MAX_HEIGHT: Float = 360f

    /**
     * 路线列表限高：**只给整行**（替代写死的 164.dp）。
     *
     * 关键不是"多高"，而是"必须是整行的高度" —— 半行会让用户以为还能往下滑，
     * 实际上滑不动。所以这里先按行高反推能放几个整行，再算回高度。
     */
    fun routeListHeight(
        availableHeightDp: Float,
        rowHeightDp: Float = 52f,
        rowGapDp: Float = 4f,
        maxRows: Int = 3,
    ): Dp {
        val rows = floor((availableHeightDp + rowGapDp) / (rowHeightDp + rowGapDp))
            .toInt()
            .coerceIn(1, maxRows)
        return (rows * rowHeightDp + (rows - 1) * rowGapDp).dp
    }

    /** 预览卡宽度。替代 `PREVIEW_MAX_WIDTH` 的 460.dp。 */
    fun previewWidth(contentWidthDp: Float): Dp =
        (contentWidthDp * 0.58f).coerceIn(300f, 520f).dp

    // ---------- 其它 ----------

    /** 托管提示卡的最大宽度。替代 `HostingNotice` 里写死的 560.dp。 */
    fun noticeMaxWidth(contentWidthDp: Float): Dp =
        (contentWidthDp * 0.72f).coerceIn(320f, 640f).dp

    /**
     * 判"窄布局"的阈值（替代 `MapPane` 里写死的 `maxWidth < 300.dp`）。
     *
     * **这一条不改成比例**，原因是它不是"屏幕有多宽"的判断，而是"这个组件自己需要多宽"
     * 的判断：低于 [NARROW_LAYOUT_WIDTH_DP] 时两栏并排会把文字挤成两三个字一行，
     * 必须改成上下堆叠。这类"组件自身的可读性下限"与屏幕无关，写死是对的 ——
     * 但要从调用点挪到这里并写清理由，不能再散落在业务代码里当魔法数字。
     *
     * 调用方传的是**该组件自己拿到的宽度**，不是屏幕宽。
     */
    fun isNarrowLayout(componentWidthDp: Float): Boolean =
        componentWidthDp < NARROW_LAYOUT_WIDTH_DP

    const val NARROW_LAYOUT_WIDTH_DP: Float = 300f
}
