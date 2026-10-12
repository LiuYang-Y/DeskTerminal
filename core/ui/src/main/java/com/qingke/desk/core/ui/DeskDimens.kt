package com.qingke.desk.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 横屏桌面终端的全部尺寸常量。
 *
 * 硬约束：本文件不出现任何 px，只出现 dp / sp / 比例。
 * 需要物理像素的地方（投屏 Surface、视频分辨率上限）在这里只声明"逻辑上限"，
 * 由调用方结合 LocalDensity 换算，避免把某一台机器的分辨率写进布局。
 *
 * ---------------------------------------------------------------------------
 * 校正基准（2026-10-10 adb 实测，非估算；两台机都实跑过截图）
 *
 * 当前基准机  OnePlus PKR110（OP60EBL1，SDK 36 / Android 16）
 *   物理分辨率  1264 x 2780（竖屏）→ 横屏 2780 x 1264
 *   密度        560 dpi（3.5 倍）—— 面板物理 DPI 是 452/450，但应用密度是 560
 *   横屏逻辑    2780/3.5 = 794 dp 宽，1264/3.5 = 361 dp 高
 *   挖孔        横屏左侧 140 px = 40 dp，内容用 displayCutout 插图避让
 *   实测栅格    6 张卡 3x2，单卡 806x422 px = 230 x 120.5 dp
 *
 * 参考机      Xiaomi M2102K1C（star，SDK 30 / Android 11）
 *   1440x3200 @560dpi → 横屏 3200x1440 = 914 x 411 dp，左侧挖孔 137 px
 * ---------------------------------------------------------------------------
 *
 * 横屏纵向预算（2026-10-12 重算：底栏改侧栏、顶部状态栏改右上角悬浮角标之后，
 * 纵向被彻底释放；改任何一个值都要回来重算这一段）：
 *   侧栏        5 项 × [sideBarItemSize] 64 + 4 × [sideBarItemGap] 6
 *               + 2 × [sideBarInnerPadding] 6 = 356 ≤ 361，上下各余 2.5dp 垂直居中
 *   卡片区      上下各 [cardAreaTopPadding]/[cardAreaBottomPadding] 12 → 可用高 337
 *   两行卡片    (337 - gridGap 12) / 2 = 162.5 dp/张
 *   单卡内容区  162.5 - cardPadding 28 = 134.5 dp
 * 反推行数：floor((337 + 12) / (cardMinHeight 108 + 12)) = 2 —— 与上面的两行自洽。
 *
 * 横向预算：
 *   挖孔        40 dp（**由 displayCutout 实时读，不写死左右** —— 手机倒转 180°
 *               它会翻到另一侧，写死左边就会在倒转后错位）
 *   侧栏        [sideBarWidth] 76
 *   内容宽      794 - 40 - 76 = 678
 *   卡片区      左右各 screenPadding 20 → 638；3 列 gap 12 → 单卡 (638 - 24)/3 ≈ 204.7
 *
 * 注意 cardMinHeight 只用来**从可用高度反推行数**，不等于单卡实际高度；
 *   卡片里不能再假设自己有多高，字号必须按可用宽高反推
 *   （见 [TEXT_LINE_HEIGHT_RATIO] 与 feature:home 的时钟卡 / 天气卡）。
 *
 * 历史坑（四次改版，都留着当反面教材）：
 *   1. cardMinHeight 取过 140 → 只剩 1 行；
 *   2. 状态栏 40 + 底部只让出 58（允许内容探进底栏 24dp 给玻璃当采样素材）
 *      → 第二排卡片被玻璃栏压住，真机截图确认过；
 *   3. 底栏横排 6 项 × 64dp = 384dp 就占掉大半宽度，纵向预算被压到 231dp，
 *      卡片只能做到 109.5dp 高。2026-10-12 把底栏立起来做成侧栏，
 *      横向只吃 76dp、纵向一分不让，卡片因此能长到 162.5dp。
 *   4. **算术错**：方案定案时把 5×64 + 4×8 + 2×6 记成了 356，实际是 364 > 361 —— 
 *      侧栏比屏幕高 3dp。真机症状：侧栏的条目节点实测 224×1222（比容器内应有的
 *      1232 少 10px），第五项被挤掉 10px、整列还比屏幕中心低 10px。
 *      gap 由 8 改 6 之后 = 356 ✓ 与这句注释一致。**改预算先按计算器，别照抄旧数字。**
 */
object DeskDimens {

    // ---------- 屏幕留白 ----------

    /** 内容区距屏幕边缘的呼吸空间。 */
    val screenPadding: Dp = 20.dp

    /** 内容区与侧栏之间的间距。侧栏是浮层，这条缝只是观感上的"不贴死"。 */
    val sidebarSpacing: Dp = 12.dp

    // ---------- 右上角悬浮区（2026-10-12 起只有设置齿轮）----------

    /**
     * 状态指示胶囊的高度与字号。
     *
     * 2026-10-12：Shizuku 角标已从悬浮区移进设置面板，这两个值现在只被
     * :hosting 的 debug 预览页用（[com.qingke.desk.hosting.ui.ShizukuStatusChip]）。
     * 留着而不是删掉：预览页要照着真实尺寸看一眼，删了它就得写死一遍同样的数。
     */
    val statusChipHeight: Dp = 28.dp
    val statusChipText: TextUnit = 12.sp

    /** 悬浮区距屏幕顶边 / 侧边的留白。侧边还要再叠挖孔插图（见 DeskRoot）。 */
    val statusChipTopPadding: Dp = 12.dp
    val statusChipSidePadding: Dp = 12.dp

    /** 悬浮区里，设置齿轮与展开后的浮层之间的间距。 */
    val statusChipGap: Dp = 8.dp

    /**
     * 悬浮区占掉的**顶部带**高度 = 顶部留白 + 齿轮边长 + 间距（12 + 40 + 8）。
     *
     * 全屏隐私卡要按它给内容顶部让位：齿轮是常驻的，压在标题上两边都读不清
     * （真机截图 .verify/pager/v11-revoke-fullscreen.png）。
     * 之所以给一整条带而不是"只在齿轮那一侧避让"：齿轮会跟着侧栏镜像到左上或
     * 右上，而卡片的标题是横向铺满的 —— 只避一侧，用户把侧栏切到另一边就又叠上了。
     * 整条 60 dp 换的是"两种侧栏位置下都不会叠"，这个交易划算。
     */
    val overlayChipBandHeight: Dp = 60.dp

    /**
     * 设置浮层宽度。794 dp 宽的屏幕取 380 —— 接近半屏，但**不会碰到侧栏**
     * （侧栏 76 + 挖孔 40 + 两侧留白，浮层从另一边算起还有约 440 dp 的余量），
     * 所以"切到左侧/右侧"这两个按钮在浮层里始终是完整可见的。
     */
    val settingsPaneWidth: Dp = 380.dp

    /**
     * 设置齿轮的边长。40 dp 与状态角标的高度 28 dp 拉开差距：
     * 常驻的入口比"只在异常时出现"的角标大一圈，手指不用在两者之间犹豫。
     */
    val settingsChipSize: Dp = 40.dp

    /** 浮层里按钮 / 开关的统一高度，保证与卡片一样一次点中。 */
    val settingsControlHeight: Dp = 40.dp

    /**
     * 浮层内边距。比常规卡片的 18 dp 小一档：横屏 361 dp 的高度里要装下
     * 标题行 + 四项设置，每一档都是从这个预算里省出来的（2026-10-12 由 14 收到 12）。
     */
    val settingsPanePadding: Dp = 12.dp

    /**
     * 浮层里各设置项之间的间距。2026-10-12 由 10 收到 8，与上面同一个原因：
     * 四项排完后剩下最后一个（Shizuku）被顶出屏幕一行，收 2 dp × 3 段刚好放回来。
     */
    val settingsItemGap: Dp = 8.dp

    // ---------- 首页分页（2026-10-12 改：CardGrid 单页 → 两页横滑）----------

    /**
     * 分页主卡（地图卡 / 时钟卡）与副列（205 dp 那一列）的权重比，2 : 1。
     *
     * 基准机每页可用 626 dp、gap 12 → 614 分三份：主卡 409、副列 205。
     * 409/626 = 65.4%，就是用户要的"约三分之二"。
     * 写成权重而不是写死宽度：换一台 914 dp 宽的机器自动按比例放大。
     */
    const val HOME_PAGE_MAIN_WEIGHT: Float = 2f
    const val HOME_PAGE_SIDE_WEIGHT: Float = 1f

    /** 页码圆点行的总高度（含上下留白）。它独占这一条，不挤占卡片高度。 */
    val pageIndicatorHeight: Dp = 24.dp

    /** 圆点直径：未选中 6、选中 8。差 2 dp 是为了让"当前页"看得出来又不过分跳。 */
    val pageDotSize: Dp = 6.dp
    val pageDotSelectedSize: Dp = 8.dp

    /** 圆点间距。 */
    val pageDotGap: Dp = 10.dp

    // ---------- 卡片栅格 ----------

    /**
     * 首页列数。基准机横屏 874 dp 可用宽度 / 3 列 ≈ 283 dp 每张。
     * 当前假数据是 6 张卡，3x2 正好填满，不留空格子。
     * 卡片张数变多时先考虑翻页，不要无脑加列 —— 列多了单卡会窄于 209 dp。
     */
    const val GRID_COLUMNS: Int = 3

    /** 首页行数。只是参考值，实际由 [com.qingke.desk.shell] 按可用高度反推。 */
    const val GRID_ROWS: Int = 2

    /**
     * 卡片最小高度下限，只用于**从可用高度反推行数**。
     * 基准机实测：取 140 时可用高度只够排 1 行；108 可得 2 行。
     * 底栏改侧栏之后可用高度涨到 337，108 仍得 2 行（单卡因此长到 162.5 dp）。
     */
    val cardMinHeight: Dp = 108.dp

    val gridGap: Dp = 12.dp
    val cardCorner: Dp = 20.dp

    /** 卡片区上/下留白。底栏移走后上下对称，各 12。 */
    val cardAreaTopPadding: Dp = 12.dp
    val cardAreaBottomPadding: Dp = 12.dp

    /**
     * 卡片内边距。2026-10-11 由 16 收到 14：纵向预算里每张卡只多出 4 dp 内容区，
     * 但这 4 dp 正好是"天气卡第四行不被裁"与"被裁"的分界。
     */
    val cardPadding: Dp = 14.dp

    // ---------- 大触控区（CarPlay 观感的核心）----------

    /**
     * 最小可点击尺寸。Material 基线是 48dp；桌面终端在手臂距离外操作，放大到 64dp。
     *
     * 这个数是**底线，不是可以再让的余量**：侧栏方案 D 就是为了保住它 ——
     * 6 个入口竖排需要 6×64=384dp > 屏幕高 361dp，放不下；于是砍掉一个尚未实现的
     * 入口（智能家居）换成 5 项 × 64dp = 320dp，而不是把 6 项压成 48~54dp。
     */
    val minTouchTarget: Dp = 64.dp

    // ---------- 侧边栏（原底栏，2026-10-12 立起来）----------
    //
    // 立起来的理由就是上面那条：横屏纵向 361dp 是硬约束，横排底栏吃掉 82dp 纵向，
    // 竖排侧栏只吃 76dp 横向（横向有 794dp，富裕）。
    //
    // 侧栏同样是**浮在内容之上**的一层，这样它背后才有东西可"玻"。
    // 如果内容整整齐齐停在它旁边，栏背后只剩一层纯底色，模糊和折射统统看不出来。

    /** 单个条目：图标 + 文字标签竖向排列，见方 [sideBarItemSize]。 */
    val sideBarItemSize: Dp = 64.dp
    val sideBarIconSize: Dp = 30.dp

    /**
     * 条目之间的间距。5 项 → 4 段，合计 24dp。
     *
     * 2026-10-12 由 8dp 改 6dp：8dp 时整列 = 5×64 + 4×8 + 2×6 = **364dp > 361dp**，
     * 比屏幕高 3dp，真机上第五项被挤掉 10px（见文件头的"历史坑 4"）。
     * 6dp 时 = 356dp ✓。间距是这一列里唯一可退的量 —— 条目尺寸 64dp 是"手臂距离
     * 外也点得中"的底线，内边距 6dp 再退玻璃就贴住图标了。
     */
    val sideBarItemGap: Dp = 6.dp

    /** 侧栏玻璃块的内边距。条目不直接贴着玻璃边缘。 */
    val sideBarInnerPadding: Dp = 6.dp

    /** 侧栏玻璃块宽度 = 64 + 6 × 2。 */
    val sideBarWidth: Dp = sideBarItemSize + sideBarInnerPadding * 2

    /**
     * 侧栏玻璃块的圆角。做成浮动胶囊，四条边才有折射可看。
     * 76 dp 宽配 28 dp 圆角 —— 再大就快变成圆形了。
     */
    val sideBarCorner: Dp = 28.dp

    /** 选中指示块的圆角。它是 64dp 见方里的一块，圆角比侧栏小一号才像"块"。 */
    val sideBarIndicatorCorner: Dp = 20.dp

    /** 侧栏条目文字标签字号。 */
    val sideBarLabel: TextUnit = 12.sp

    /**
     * 卡片里的装饰图标尺寸。原先借用的是"底栏图标"那个常量，底栏改成侧栏之后
     * 语义就不对了（卡片图标跟侧栏没关系），单列一个。
     */
    val cardIconSize: Dp = 32.dp

    // ---------- 字体 ----------
    // 本机 fontScale = 1.0（scaledDensity 560 = density 560），sp 与 dp 1:1。

    val cardTitle: TextUnit = 24.sp
    val cardBody: TextUnit = 15.sp
    val cardCaption: TextUnit = 13.sp

    /**
     * 卡片内大时钟的**字号上限**。
     *
     * 实测：本机单卡内容区只有约 88.5 dp 高（卡 120.5 - cardPadding 32），
     * 56.sp 的中文字面行高约 1.17 倍 → 65 dp，加上副标题就溢出被裁。
     * 所以 56.sp 只是上限，实际字号由 ClockBody 按可用高度反推，见下方比例常量。
     */
    val clockTime: TextUnit = 56.sp

    /**
     * **大卡**里的时钟字号上限（第二页时钟卡 409 × 313）。
     *
     * 56.sp 是按 88 dp 内容区定的；分页后时钟卡内容区约 381 × 285，
     * 沿用 56 会显得小得莫名其妙（卡变大了四倍，字没变）。
     * 96.sp 渲染行高约 112 dp，加日期副标题仍有余量。
     * 它同样是**上限**：实际字号仍由 [TEXT_LINE_HEIGHT_RATIO] 按可用高度反推。
     */
    val clockTimeLarge: TextUnit = 96.sp

    /**
     * 文本实际行高与字号的比值。
     * 本机实测 56.sp 渲染出约 65.7 dp 行高 → 1.17；取 1.25 留安全余量。
     * 用于按可用高度反推字号，避免"字比卡高被裁"这类问题。
     */
    const val TEXT_LINE_HEIGHT_RATIO: Float = 1.25f

    // ---------- 投屏 ----------

    /**
     * 视频分辨率上限（物理像素）。取基准机横屏实际分辨率 3200 x 1440，
     * 即"不超过屏幕"的最大值。实际 CarPlay 码流通常远小于此（约 1920x720），
     * 这里只是裁剪上限，不是目标分辨率。
     */
    const val PROJECTION_MAX_WIDTH_PX: Int = 3200
    const val PROJECTION_MAX_HEIGHT_PX: Int = 1440

    /** 起始帧率。原项目建议 30fps 起步、HEVC 关闭，稳定后再往上试。 */
    const val PROJECTION_TARGET_FPS: Int = 30
    const val PROJECTION_HEVC_ENABLED: Boolean = false

    // ---------- 防烧屏 ----------

    /** 时钟等固定元素的最大漂移半径。 */
    val burnInDriftRange: Dp = 6.dp

    /**
     * 完成一次完整漂移循环所需分钟数。
     *
     * 这是**唯一**留下来的防烧屏手段（2026-10-12）：屏保与"屏保时压暗亮度"都删掉了。
     * 漂移只作用在**固定元素**（时钟、卡片文字）上，让它们不长期停在同一批像素上；
     * 它不挡内容、不改亮度、不需要用户介入，代价只有一个 —— 内容整体会缓慢游走。
     */
    const val BURN_IN_DRIFT_PERIOD_MIN: Long = 10L
}
