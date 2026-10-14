package com.qingke.desk.feature.media.music

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 音乐页的全部尺寸。**没有一个字段是常量** —— 每一项都由
 * `BoxWithConstraints` 给出的可用宽高 ([widthDp] / [heightDp]) 反推。
 *
 * 这是本项目"硬约束 —— 尺寸不写死"在音乐页上的落地：整页只有
 * [MusicLayout.metrics] 一个入口，页面各处一律读它的字段，
 * 谁都不许在组合代码里再写一个 `64.dp`。
 *
 * 单位一律 `Float`（dp / sp），转 `Dp`/`TextUnit` 由 [Dp]/[TextUnit] 的扩展在页面里做。
 * 这样这一份计算**不依赖 Compose 运行时**，可以直接跑单元测试
 * （见 `MusicLayoutTest`：非重叠不变式就是在这里被证伪的）。
 */
data class MusicMetrics(
    val widthDp: Float,
    val heightDp: Float,

    /** 页面四边的基础留白。 */
    val pad: Float,

    // ---- HUD（左下封面 + 右下控制）----
    /** 左下封面的边长。 */
    val coverSide: Float,
    /** 播放/暂停键的直径。 */
    val controlSize: Float,
    /** 上一首/下一首键的直径（比主键小一号）。 */
    val sideControlSize: Float,
    /** 控制键之间的间距。 */
    val controlGap: Float,
    /** 控制键里图标的边长。 */
    val controlIconSize: Float,
    /** 封面与右侧歌名之间那道缝。 */
    val coverTextGap: Float,
    /** 右下那一列的宽度（进度条就铺满它）。 */
    val hudColumnWidth: Float,
    /** 左下歌名/歌手这一块的最大宽度。 */
    val hudTextMaxWidth: Float,
    /** 进度条高度。 */
    val progressHeight: Float,
    /**
     * 进度条的**触控高度**。比可视高度高得多 —— 可视只有 6~7 dp，
     * 而"拖得动"这件事需要一块像样的触摸面。宽度有两百多 dp，
     * 高度给到主键的 38%（≈22~25 dp）在手臂距离外已经够用。
     */
    val progressTouchHeight: Float,
    /** 进度条与控制键之间的间距。 */
    val progressGap: Float,

    /** HUD 整条带的高度（从屏幕底边往上算，含 [pad]）。 */
    val hudBandHeight: Float,
    /** HUD 上沿的 y 坐标。 */
    val hudTopY: Float,

    // ---- HUD 左下那三行文字的尺寸 ----
    /** 歌名字号。 */
    val titleSizeSp: Float,
    /** 歌手字号。 */
    val artistSizeSp: Float,
    /** "导入歌词"那颗小字按钮的字号。 */
    val chipSizeSp: Float,
    /** 左下三行文字之间的间距。 */
    val hudTextGap: Float,

    // ---- 中央歌词（2 行聚焦）----
    /** 主歌词字号。 */
    val lyricSizeSp: Float,
    /** 主歌词的**显式行高**。见 [MusicLayout.LYRIC_LINE_HEIGHT_EM]。 */
    val lyricLineHeightSp: Float,
    /** 两行之间的间距。 */
    val lyricGap: Float,
    /** 两行歌词块的总高度。 */
    val lyricBlockHeight: Float,
    /** 歌词块上沿的 y。 */
    val lyricTopY: Float,
    /** 歌词块下沿的 y。 */
    val lyricBottomY: Float,

    // ---- 装饰 ----
    /** 背景大衬字（水印）字号。 */
    val watermarkSizeSp: Float,
    /** 左右声波圆环的最大扩散半径。 */
    val waveMaxRadius: Float,
) {

    val coverSideDp: Dp get() = coverSide.dp
    val controlSizeDp: Dp get() = controlSize.dp
    val sideControlSizeDp: Dp get() = sideControlSize.dp
    val controlIconSizeDp: Dp get() = controlIconSize.dp
    val progressHeightDp: Dp get() = progressHeight.dp
    val progressTouchHeightDp: Dp get() = progressTouchHeight.dp
    val lyricSize: TextUnit get() = lyricSizeSp.sp
    val lyricLineHeight: TextUnit get() = lyricLineHeightSp.sp
    val watermarkSize: TextUnit get() = watermarkSizeSp.sp
    val titleSize: TextUnit get() = titleSizeSp.sp
    val artistSize: TextUnit get() = artistSizeSp.sp
    val chipSize: TextUnit get() = chipSizeSp.sp
    val waveMaxRadiusDp: Dp get() = waveMaxRadius.dp

    /**
     * 歌词块底边是否守住了 HUD 的上沿（留 [MusicLayout.LYRIC_HUD_MIN_GAP] 的缝）。
     *
     * **这个判据是给单元测试用的，不是给运行时用的** —— 页面不做"底边超了就压歌词"
     * 这种运行时补救：那会让同一首歌在两台机器上歌词大小不同，反而更不可控。
     * 这里是"算完了要能验"，越界应当在开发阶段就红，而不是上线后靠眼睛发现。
     */
    val lyricClearsHud: Boolean
        get() = lyricBottomY <= hudTopY - MusicLayout.LYRIC_HUD_MIN_GAP

    /** 左下块与右下控制组之间是否没有交叠。 */
    val hudBlocksDoNotOverlap: Boolean
        get() = (pad + coverSide + coverTextGap + hudTextMaxWidth) <
            (widthDp - pad - hudColumnWidth)

    /**
     * 左下那块三行文字的总高（歌名 + 歌手 + 导入按钮）。
     *
     * 它必须**不超过封面边长**，否则左下的信息块会比封面高，
     * 把整条 HUD 的预算又往上顶一截 —— 那属于"设计时没想到的增长"。
     * 不变式由 `MusicLayoutTest` 盯着。
     */
    val hudTextBlockHeight: Float
        get() = coverSide * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM *
            (
                MusicLayout.TITLE_SIZE_RATIO +
                    MusicLayout.ARTIST_SIZE_RATIO +
                    MusicLayout.CHIP_SIZE_RATIO
                ) + hudTextGap * 2f
}

/**
 * 音乐页的尺寸推导。
 *
 * ---------------------------------------------------------------------------
 * 为什么用比例而不是 dp 常量
 *
 * 两台在用/在验的机器尺寸差别很大，而它们的内容区**又都不是整屏**：
 *
 * | 机器 | 内容区（宽 × 高） | 说明 |
 * |---|---|---|
 * | 一加 PKR110（Android 16） | **666 × 361 dp** | 794 − 挖孔 40 − 侧栏 76 − 间距 12；高不变 |
 * | 小米 11 Ultra（Android 11） | **786 × 411 dp** | 914 − 挖孔 39 − 侧栏 76 − 间距 12；高不变 |
 *
 * 高度差 50 dp（14%）、宽度差 120 dp（18%）。任何写死的 dp 常量都会在其中一台上
 * 明显不对 —— 这个项目已经因为这类问题换机当天连爆两个 bug（见 `AdaptiveLayout` 的注释）。
 *
 * ---------------------------------------------------------------------------
 * 推导链（一条链，每步只依赖上一步）
 *
 * ```
 * pad       = H × 0.055
 * cover     = clamp(H × 0.16, 56, 68)
 * control   = cover                       （主键与封面同度量，画面才有"一对主角"的感觉）
 * sideCtrl  = cover × 0.85
 * lyricSize = clamp(H × 0.086, 26, 32)    ← 夹取
 * lineH     = lyricSize × 1.30            ← 用夹取后的字号**回算**行高
 * block     = 2 × lineH + lyricSize × 0.55
 * watermark = H × 0.29
 * waveR     = min(W × 0.38, H × 0.72)
 * hudBand   = pad + max(cover, progressTouchH + progressGap + control)
 * ```
 *
 * 注意 HUD 带高里用的是 **progressTouchH**（触控高度）而不是可视高度 ——
 * 那是"进度条拖得动"的代价：可视只有 6 dp，但要给它留一块 22~25 dp 的触摸面。
 * 这一项让 HUD 带比"只按可视高度排"高出约 30 dp，**表里的数字就是被它改过的**
 * （旧表按可视高度算，已经对不上，2026-10-14 一并更正）。
 *
 * **"夹取后回算"是关键的一步**（2026-10-14 定稿）：字号有上下限（26..32sp），
 * 但行高必须是字号的函数。如果先算行高再夹字号，就会得到"字号被夹小、行高还是大的"
 * 那种两套阈值打架的状态 —— 症状是矮屏上字很小、行距却很宽，而且**看不出来是算错了**。
 *
 * ---------------------------------------------------------------------------
 * 两行聚焦（而不是 5 行列表）
 *
 * 参考图的气质来自"大字 + 大片留白"。5 行列表会把 97 dp 的歌词块撑到 240 dp，
 * 中央舞台立刻变成一块密集文本区。2 行之后：
 *
 * | | 一加 666×361 | 小米 786×411 |
 * |---|---|---|
 * | 歌词块高 | 97.8 dp（占高 27%） | 100.8 dp（占高 25%） |
 * | 歌词块底边 | 229.4 | 255.9 |
 * | HUD 上沿 | 253.9 | 289.1 |
 * | 缝 | **24.5 dp** | **33.2 dp** |
 *
 * 缝比 [LYRIC_HUD_MIN_GAP]（8 dp）大 3~4 倍，说明这个比例在两端都有余量，
 * 不是"刚好卡上"。**这些数字不是估的，是 `MusicLayoutTest` 里实测的同一套公式算出来的** ——
 * 改比例之后表会失效，测试会先红，别让它和实现漂开。
 *
 * 真正的边界情形由 `MusicLayoutTest` 用一整个尺寸矩阵去撞，实测结论：
 *  · 高度降到 **301 dp** 时歌词块才会贴上 HUD（在役机器是 361 / 411，余量充足）；
 *  · 宽度降到 **289 dp** 时左右两块 HUD 才会撞上（在役机器是 666 / 786）。
 * 两条边界都被测试钉成了断言，比例一被改动就会有人来提醒。
 */
object MusicLayout {

    // ---- 比例 ----

    /** 四边基础留白。0.055 ≈ 20 dp（361 高）/ 22.6 dp（411 高）。 */
    const val PAD_RATIO = 0.055f

    /** 封面相对高度的比例。 */
    const val COVER_RATIO = 0.16f

    /** 封面的夹取区间。下限 56 与主控制键同量级；上限 68 防止高屏上封面变成一个大方块。 */
    const val COVER_MIN = 56f
    const val COVER_MAX = 68f

    /** 上一首/下一首键相对主键的比例。 */
    const val SIDE_CONTROL_RATIO = 0.85f

    /** 控制键之间的间距，相对主键直径。 */
    const val CONTROL_GAP_RATIO = 0.30f

    /** 控制键图标的比例。 */
    const val CONTROL_ICON_RATIO = 0.46f

    /** 封面与文字之间那道缝，相对封面边长。 */
    const val COVER_TEXT_GAP_RATIO = 0.21f

    /** 右下控制列的宽度，相对页面宽。 */
    const val HUD_COLUMN_RATIO = 0.32f

    /** 左下歌名/歌手块的最大宽度，相对页面宽。 */
    const val HUD_TEXT_RATIO = 0.30f

    /** 进度条高度，相对页面高。 */
    const val PROGRESS_RATIO = 0.017f
    const val PROGRESS_MIN = 5f
    const val PROGRESS_MAX = 8f

    /** 进度条与控制键之间的间距，相对主键直径。 */
    const val PROGRESS_GAP_RATIO = 0.13f

    /** 进度条的触控高度下限（相对主键直径）。 */
    const val PROGRESS_TOUCH_RATIO = 0.38f

    /** HUD 左下三行文字相对封面边长的比例。 */
    const val TITLE_SIZE_RATIO = 0.300f
    const val ARTIST_SIZE_RATIO = 0.205f
    const val CHIP_SIZE_RATIO = 0.175f

    /** 左下三行文字之间的间距，相对封面边长。0.04 很小是有意的 —— 三行要挤在封面高度之内。 */
    const val HUD_TEXT_GAP_RATIO = 0.04f

    /**
     * HUD 三行文字的行高系数（em）。
     *
     * 与歌词用的是同一个道理（**行高必须显式给**），只是数值不同：左下这块是"信息栏"，
     * 排得紧一点才像一块牌子；1.25 而不是 1.30，是为了让三行加起来不超过封面高度
     * （三行总高 ≈ 0.68 × 封面边长）。这个关系由 `MusicLayoutTest` 盯着。
     */
    const val HUD_TEXT_LINE_HEIGHT_EM = 1.25f

    /** 主歌词字号，相对页面高。 */
    const val LYRIC_SIZE_RATIO = 0.086f
    const val LYRIC_SIZE_MIN = 26f
    const val LYRIC_SIZE_MAX = 32f

    /**
     * 主歌词的**行高系数（em）**。
     *
     * ⚠️ 这个数会**显式传给 `Text` 的 `lineHeight` 参数**，不是"期望值"。
     * 项目在 2026-10-14 已经因为这件事吃过一次亏：Compose 在没写 `lineHeight` 时，
     * 行高来自**字体度量**（中文 ascent 大，实测 13sp 的行盒约 20.3dp ≈ 1.56em），
     * 而当时按 1.25 反推安全区，结果红字压进卡片顶边约 4 dp。
     * 所以：**凡涉及行高的推导，行高必须显式声明或实测，不许借别的比例。**
     *
     * 1.30 是按"两行之间要能看出是一句"选的：再小两行会贴成一块，再大就散架了。
     */
    const val LYRIC_LINE_HEIGHT_EM = 1.30f

    /** 两行歌词之间的间距，相对字号。 */
    const val LYRIC_GAP_EM = 0.55f

    /** 背景大衬字，相对页面高。 */
    const val WATERMARK_RATIO = 0.29f

    /** 声波圆环最大半径：宽向比例 / 高向比例，取小。 */
    const val WAVE_WIDTH_RATIO = 0.38f
    const val WAVE_HEIGHT_RATIO = 0.72f

    /** 歌词块与 HUD 之间必须留出的最小缝。 */
    const val LYRIC_HUD_MIN_GAP = 8f

    /**
     * 由可用空间算出全部尺寸。
     *
     * @param widthDp 内容区可用宽（`BoxWithConstraints` 的 `maxWidth`），**不是屏幕宽**。
     * @param heightDp 内容区可用高（`maxHeight`）。
     */
    fun metrics(widthDp: Float, heightDp: Float): MusicMetrics {
        val w = widthDp.coerceAtLeast(1f)
        val h = heightDp.coerceAtLeast(1f)

        val pad = h * PAD_RATIO

        val cover = (h * COVER_RATIO).coerceIn(COVER_MIN, COVER_MAX)
        val control = cover
        val sideControl = cover * SIDE_CONTROL_RATIO
        val controlGap = control * CONTROL_GAP_RATIO
        val controlIcon = control * CONTROL_ICON_RATIO
        val coverTextGap = cover * COVER_TEXT_GAP_RATIO

        val progressHeight = (h * PROGRESS_RATIO).coerceIn(PROGRESS_MIN, PROGRESS_MAX)
        val progressGap = control * PROGRESS_GAP_RATIO
        val progressTouchHeight = maxOf(progressHeight, control * PROGRESS_TOUCH_RATIO)

        val hudColumnWidth = w * HUD_COLUMN_RATIO
        val hudTextMaxWidth = w * HUD_TEXT_RATIO

        // 字号先夹取，行高再用**夹取后的字号**回算 —— 顺序不能颠倒，理由见类注释。
        val lyricSize = (h * LYRIC_SIZE_RATIO).coerceIn(LYRIC_SIZE_MIN, LYRIC_SIZE_MAX)
        val lyricLineHeight = lyricSize * LYRIC_LINE_HEIGHT_EM
        val lyricGap = lyricSize * LYRIC_GAP_EM
        val lyricBlockHeight = lyricLineHeight * 2f + lyricGap

        val hudContentHeight = maxOf(
            cover,
            progressTouchHeight + progressGap + control,
        )
        val hudBandHeight = pad + hudContentHeight
        val hudTopY = h - hudBandHeight

        val lyricTopY = (h - lyricBlockHeight) / 2f
        val lyricBottomY = lyricTopY + lyricBlockHeight

        val watermarkSize = h * WATERMARK_RATIO
        val waveMaxRadius = minOf(w * WAVE_WIDTH_RATIO, h * WAVE_HEIGHT_RATIO)

        return MusicMetrics(
            widthDp = w,
            heightDp = h,
            pad = pad,
            coverSide = cover,
            controlSize = control,
            sideControlSize = sideControl,
            controlGap = controlGap,
            controlIconSize = controlIcon,
            coverTextGap = coverTextGap,
            hudColumnWidth = hudColumnWidth,
            hudTextMaxWidth = hudTextMaxWidth,
            progressHeight = progressHeight,
            progressTouchHeight = progressTouchHeight,
            progressGap = progressGap,
            hudBandHeight = hudBandHeight,
            hudTopY = hudTopY,
            titleSizeSp = cover * TITLE_SIZE_RATIO,
            artistSizeSp = cover * ARTIST_SIZE_RATIO,
            chipSizeSp = cover * CHIP_SIZE_RATIO,
            hudTextGap = cover * HUD_TEXT_GAP_RATIO,
            lyricSizeSp = lyricSize,
            lyricLineHeightSp = lyricLineHeight,
            lyricGap = lyricGap,
            lyricBlockHeight = lyricBlockHeight,
            lyricTopY = lyricTopY,
            lyricBottomY = lyricBottomY,
            watermarkSizeSp = watermarkSize,
            waveMaxRadius = waveMaxRadius,
        )
    }
}
