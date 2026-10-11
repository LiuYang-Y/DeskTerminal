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
 * 横屏纵向预算（按当前基准机 361 dp 推导，改任何一个值都要重算）：
 *   DockBar     dockItemSize 64 + dockSpacing 12 = 76
 *   卡片区      361 - 76 = 285
 *   减内边距    285 - (top screenPadding 20 + bottom dockSpacing 12) = 253
 *   两行卡片    253 - gridGap 12 = 241 / 2 = 120.5 dp/张
 * 注意：120.5 < cardMinHeight 124 —— 这是允许的，cardMinHeight 是"反推行数"的下限，
 *   不是单卡实际高度。**卡片内不能再假设自己有多高**，大字号必须按可用高度反推
 *   （见 [TEXT_LINE_HEIGHT_RATIO] 与 feature:home 的时钟卡）。
 * 若 cardMinHeight 取到 140：行数会被压到 1 行（校正前实测到的情况）。
 *
 * 加了顶部状态栏之后的预算（2026-10-11 起，状态栏放 Shizuku 指示）：
 *   状态栏      statusBarTopPadding 8 + statusBarHeight 32 = 40
 *   栅格可用高  361 - 40 - dockContentBottomPadding 58 = 263
 *   两行卡片    (263 - gridTopPadding 8*2 - gridGap 12) / 2 = 117.5 dp/张
 *   反推行数    floor((263 + 12) / 136) = 2 —— 仍是 2 行，安全。
 *   状态栏再高 4 dp 或 cardMinHeight 提到 132 都会掉到 1 行，改动前先算这一行。
 */
object DeskDimens {

    // ---------- 屏幕留白 ----------

    /** 内容区距屏幕边缘的呼吸空间。 */
    val screenPadding: Dp = 20.dp

    /** 卡片区与图标栏之间的间距。 */
    val dockSpacing: Dp = 12.dp

    // ---------- 顶部状态栏 ----------

    /**
     * 顶部状态栏高度。里面放的是常驻的状态指示（Shizuku 是否可用等）。
     *
     * 32 dp 是**上限**，不是随手取的：横屏纵向预算极度紧张，
     * 状态栏再高一点就会把卡片挤成 1 行（见下方纵向预算）。
     */
    val statusBarHeight: Dp = 32.dp

    /** 状态栏距屏幕顶边的留白。 */
    val statusBarTopPadding: Dp = 8.dp

    /** 状态指示胶囊的高度与字号。 */
    val statusChipHeight: Dp = 28.dp
    val statusChipText: TextUnit = 12.sp

    /** 卡片栅格距状态栏的间距。原来卡片直接贴 [screenPadding]，加了状态栏后收紧。 */
    val gridTopPadding: Dp = 8.dp

    // ---------- 地图布局（首页让出一块竖屏托管面板）----------

    /**
     * 托管面板的竖屏宽高比（宽 / 高）。
     *
     * 取 3:4。方案是拿真机尺寸算出来的：横屏只有 361 dp 高，扣掉状态栏 40 dp 后
     * 内容区 321 dp，面板上下各留 [hostingPanelTopGap] / [hostingPanelBottomGap]
     * 之后高 307 dp；宽度按比例反推 = 230 dp（805 × 1075 px @560dpi，对角线约 2.4 英寸）。
     *
     * 这是"面板尽量大"与"左侧还能放下 3 列卡片"的平衡点：
     *   · 退回 9:16（0.5625）→ 面板 173 dp，卡片单卡反而变宽到 182 dp；
     *   · 放宽到 5:6（0.833）→ 面板 256 dp，但卡片单卡只剩 154 dp，内容会挤。
     * 换比例只改这一个常量，布局不用动。
     *
     * 注意：面板宽度**不可能超过它的高度**，而高度又被屏幕高度锁死 ——
     * 230 dp 比任何手机的最窄宽度（320 dp）都窄，第三方应用在这么窄的窗口里
     * 怎么排版不受我们控制，只能真机看。
     */
    const val HOSTING_PANEL_ASPECT: Float = 3f / 4f

    /** 面板距内容区顶部 / 底部的留白。底部留 6 与底栏底边对齐。 */
    val hostingPanelTopGap: Dp = 8.dp
    val hostingPanelBottomGap: Dp = 6.dp

    /** 面板与左侧卡片区之间的间距。 */
    val hostingPanelGap: Dp = 12.dp

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
     * 卡片最小高度下限。
     * 基准机实测：取 140 时可用高度只够排 1 行，取 124 可稳定排 2 行且每张约 145 dp。
     */
    val cardMinHeight: Dp = 124.dp

    val gridGap: Dp = 12.dp
    val cardCorner: Dp = 20.dp
    val cardPadding: Dp = 16.dp

    // ---------- 大触控区（CarPlay 观感的核心）----------

    /**
     * 最小可点击尺寸。Material 基线是 48dp；桌面终端在手臂距离外操作，放大到 64dp。
     */
    val minTouchTarget: Dp = 64.dp

    val dockItemSize: Dp = 64.dp
    val dockIconSize: Dp = 32.dp

    // ---------- 底部图标栏与液态玻璃 ----------
    //
    // 图标栏是**浮在内容之上**的一层（不再是 Column 里的一行），这样它背后才有东西可"玻"。
    // 如果内容整整齐齐停在栏的上方，栏背后只剩一层纯底色，模糊和折射统统看不出来 ——
    // 官方教程专门把这一点做成其中一步（Step 2: draw the background to the backdrop）。

    /** 图标栏距屏幕底边的留白。 */
    val dockBarBottomMargin: Dp = 6.dp

    /**
     * 图标栏整体占位高度：容器上下内边距（[dockSpacing]）+ 单个条目 [dockItemSize]。
     * 用于推算内容区要给底部留多少余量。
     */
    val dockBarHeight: Dp = dockBarBottomMargin + dockItemSize + dockSpacing

    /**
     * 内容允许探到图标栏下方的深度。
     * 取值上限由卡片内容决定：基准机单卡约 135 dp 高、内容顶部对齐且四周有 16 dp 内边距，
     * 底部约有一指宽是空的，探 24 dp 压不到任何文字。
     * TODO(目视) [待校正]：这个值直接决定玻璃效果明不明显，需要真机截图确认。
     */
    val dockContentOverlap: Dp = 24.dp

    /** 内容区底部内边距。 = [dockBarHeight] - [dockContentOverlap]。 */
    val dockContentBottomPadding: Dp = dockBarHeight - dockContentOverlap

    /** 玻璃容器的圆角。做成浮动胶囊，四个边才有折射可看。 */
    val dockCorner: Dp = 28.dp

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

    val dockLabel: TextUnit = 12.sp

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

    /** 完成一次完整漂移循环所需分钟数。 */
    const val BURN_IN_DRIFT_PERIOD_MIN: Long = 10L

    /** 空闲多久后转低亮屏保。 */
    const val IDLE_TIMEOUT_MS: Long = 120_000L

    /**
     * 屏保窗口亮度（窗口级，不写系统设置，因此无需 WRITE_SETTINGS）。
     * TODO(真机观感) [待校正]：0.08f 是保守起点，adb 读不出来这个值 ——
     *   需要在真机暗光环境下目视确认"够暗防残影、又不至于看不见"再定稿。
     */
    const val SCREENSAVER_BRIGHTNESS: Float = 0.08f
}
