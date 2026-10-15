package com.qingke.desk.feature.media.music

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.qingke.desk.feature.media.music.artwork.ArtworkBackdrop
import com.qingke.desk.feature.media.music.lyrics.LyricLine
import com.qingke.desk.feature.media.music.lyrics.LyricsDoc

/**
 * 背景大衬字（水印）。
 *
 * 参考图里那层"巨幅歌手名"是整页景深感的来源：它在最底下、颜色接近底色、
 * 字号接近屏高的三成，视线扫过去先认形再认字。
 *
 * ---------------------------------------------------------------------------
 * 为什么是深色而不是白色（2026-10-15 改）
 *
 * 旧版是半透明白字，与前景的白色主歌词叠在一起互相干扰 —— 两层白字抢同一块画面，
 * 谁都不突出。参考视频的做法是**深色压底**：大字用近黑的暗色沉进背景，
 * 亮色的前景主字浮在它上面，景深一眼可辨。所以默认色是 [Color.Black]，
 * 不透明度 0.24（见 [WATERMARK_ALPHA]）—— 左端极暗处它溶进背景，
 * 中右部逐渐显形，正是"从暗里浮出"的观感。
 *
 * ---------------------------------------------------------------------------
 * 为什么是"取 2~4 字"而不是整个歌手名
 *
 * 字号到 105~120sp 之后，一行最多也就放得下四五个汉字（666 dp 宽 ÷ 105 dp ≈ 6 字）。
 * 整段名字硬塞会溢出到屏幕外，而 `Text` 的 `Ellipsis` 在这么大的字号上会截成
 * "李荣…" 这种半截样子 —— 比不显示更难看。所以取"核心词"，规则见 [watermarkTextOf]。
 *
 * ---------------------------------------------------------------------------
 * 为什么走 `drawBehind` + `StaticLayout` 而不是 `Text`（2026-10-15 二次对标改）
 *
 * 有三件事 `Text` 做不到，而它们正是参考视频里那层大字的质感：
 *   1. **真衬线**：`Text` 走 Compose 的 `FontFamily.Serif`，它落到哪一款字由系统决定，
 *      与中央歌词用的 `Typeface.create(SERIF, BOLD)` 未必是同一份。现在两层用**同一个
 *      typeface**，"衬线"这件事在整页只有一个口径；
 *   2. **常驻模糊**：参考视频里大字的边缘是化开的（一坨柔光里的字），
 *      而 `Modifier.blur` 是 RenderEffect（API 31+）—— 在 API 30 的小米上静默无效。
 *      [BlurMaskFilter] 是 API 1 的原生能力，两台机器同一份代码。
 *      半径固定 [WATERMARK_BLUR_DP] dp：它是这层字的**固有属性**，不随动画变；
 *   3. **两层同时画**：换词要旧词淡出、新词淡入（交叉），`Text` 一次只能画一份内容。
 *
 * ---------------------------------------------------------------------------
 * 换词：600 ms 交叉淡入淡出
 *
 * 动态取词（见 [watermarkTextOf]）几乎是逐句在变的，硬切会"闪"。
 * 所以留一份 [prev]（上一句的字），在 [WATERMARK_SWAP_MS] 里让 `cross` 从 0 走到 1：
 * 旧词画 `1 − cross`、新词画 `cross`，两层叠着换。
 * **只有文字真的变了才动**（`prev == text` 直接返回）—— 否则每次重组都会重放一遍淡入。
 *
 * 外部契约不变：仍然是 `fillMaxWidth` + `offset(driftX, driftY)`，尺寸由 [size] 定
 * （行高 = size × [MusicLayout.LYRIC_LINE_HEIGHT_EM]，与 [LyricLineText] 同一算法）。
 */
@Composable
fun Watermark(
    text: String,
    size: TextUnit,
    alpha: Float,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
    /** 水印颜色。默认深黑压底（见类注释）—— 白色版已淘汰：与前景白字互相干扰。 */
    color: Color = Color.Black,
) {
    if (text.isBlank() || alpha <= 0f) return

    val density = LocalDensity.current
    val textSizePx = with(density) { size.toPx() }
    val lineHeightDp = with(density) { (size * MusicLayout.LYRIC_LINE_HEIGHT_EM).toDp() }
    val lineSpacingMult = with(density) {
        (size * MusicLayout.LYRIC_LINE_HEIGHT_EM).toPx() / size.toPx()
    }
    val blurPx = with(density) { WATERMARK_BLUR_DP.dp.toPx() }
    val textColor = color.toArgb()

    // Paint 复用（与 [LyricLineText] 同一条理由）：每帧新建 Paint 会让交叉期每帧都分配。
    // 按 blurPx 记忆：密度变了要换成新半径的滤镜。
    val paint = remember(blurPx) {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            // 常驻模糊 —— 这一层的字永远是这个化开的样子，不是过渡效果。
            maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
        }
    }

    // 上一句的字。**动画结束后才更新**：交叉过程中它必须保持旧值，
    // 否则两层会画成同一句话（那看起来就是"没换"）。
    var prev by remember { mutableStateOf(text) }
    val cross = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (prev == text) return@LaunchedEffect
        cross.snapTo(0f)
        cross.animateTo(1f, tween(WATERMARK_SWAP_MS))
        prev = text
    }
    val crossValue = cross.value.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(lineHeightDp)
            // 防烧屏微位移用 offset 而不是 padding：偏移量是正负 6 dp，
            // `Modifier.padding` 不接受负值，而水印一半时间会往左/往上走。
            .offset(x = driftX, y = driftY)
            .alpha(alpha)
            .drawBehind {
                // **必须显式写 `this.size`**：本函数的参数就叫 `size`（TextUnit），
                // 直接把 DrawScope 的 size 遮住了。
                val boxW = this.size.width
                val boxH = this.size.height
                if (boxW <= 0f) return@drawBehind
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val layoutW = boxW.toInt().coerceAtLeast(1)
                    // 旧词先画、新词后画（新词压在上面）：交叉期才像"换"，不像"叠"。
                    // 稳态时只画一层 —— 不留一层 alpha=0 的空绘制。
                    val layers: List<Pair<String, Float>> = if (prev == text || crossValue >= 1f) {
                        listOf(text to 1f)
                    } else {
                        listOf(prev to 1f - crossValue, text to crossValue)
                    }
                    layers.forEach { (content, layerAlpha) ->
                        paint.textSize = textSizePx
                        paint.color = textColor
                        paint.alpha = (layerAlpha.coerceIn(0f, 1f) * 255f).toInt()
                        val layout = StaticLayout.Builder
                            .obtain(content, 0, content.length, paint, layoutW)
                            .setAlignment(Layout.Alignment.ALIGN_CENTER)
                            .setLineSpacing(0f, lineSpacingMult)
                            .setIncludePad(false)
                            // 单行 + 不省略：与旧版 `Text(maxLines = 1, softWrap = false,
                            // overflow = Clip)` 一致 —— 超宽裁掉，不折行
                            // （三成大字的折行会盖住中央歌词）。
                            .setMaxLines(1)
                            .setEllipsize(null)
                            .build()
                        native.save()
                        // 在 [lineHeightDp] 这个盒子里垂直居中：字号大、行盒高，
                        // 不居中的话大字会明显偏下。
                        native.translate(0f, (boxH - layout.height) / 2f)
                        layout.draw(native)
                        native.restore()
                    }
                }
            },
    )
}

/** 水印的常驻模糊半径（dp）。见 [Watermark] 的类注释：它是这层字的固有属性。 */
private const val WATERMARK_BLUR_DP = 6f

/** 水印换词的交叉淡入淡出时长（ms）。 */
private const val WATERMARK_SWAP_MS = 600

/**
 * 中央歌词舞台：**单句折行居中 + 真衬线粗体**（2026-10-15 对标参考视频重写）。
 *
 * ---------------------------------------------------------------------------
 * 与旧版的区别（旧版是"当前行大字 + 下一行半透明小字预告"的列表感）
 *
 * 参考视频的中央只有**当前这一句**：单句较长时在语义空格处折成上下两行，
 * 两行字号、字重、颜色**完全一致**；字体是 `Typeface.create(SERIF, BOLD)` 的系统衬线粗体
 * （真衬线，不是 `FontFamily.Serif` 落到哪算哪 —— 与背景水印共用同一个 typeface），
 * 颜色取封面主色派生的**同色系浅亮色**（[ArtworkBackdrop.lyricInk]：绝对 S=0.28 / V=0.95）
 * —— 有色调的浅色，不是刺眼纯白。块高仍是两行预算（[MusicMetrics.lyricBlockHeight]），
 * 单句不折行时垂直居中，不会塌。
 *
 * 折行宽度由调用方给（见 `maxLineWidth`）：内容区可用宽 × [MusicLayout.LYRIC_MAX_WIDTH_RATIO]。
 * 不写死 dp —— 两台在役机器的内容区宽差 120 dp。
 *
 * ---------------------------------------------------------------------------
 * 切句过渡：600 ms 双层交叉 + 横向微舒展（全 API 版本同一套代码）
 *
 * 参考视频的换句不是"上一下一"，而是**两层叠着换**：
 *   · 旧句：整体淡出（1 → 0），横向**往外张**（1.00 → [LINE_SCALE_OUT_END]），
 *     模糊从 0 加重到 [BLUR_OUT_DP] —— 像被推散；
 *   · 新句：从 [LINE_SCALE_IN_START] **收进来**（到 1.00），模糊从 [BLUR_IN_DP] 消散到 0 ——
 *     像被收拢、对上焦。
 * 横向只动 6~8%，纵向 `scaleY` 恒为 1：纵向缩放会改变两行折句的观感高度，
 * 整块跟着"呼吸"就变成了布局变化，而不是过渡。
 *
 * 单一时间轴 [LINE_SWAP_MS] = 600 ms（旧版是"前半淡出 160 + 后半淡入 160"，
 * 合计 320 ms 在真机上快得看不出交叉，只像闪一下）。
 *
 * 模糊走 [BlurMaskFilter] + `drawIntoCanvas` 的原生 Paint 绘制（见 [LyricLineText]）——
 * **API 1 起就有的能力**，小米（API 30）与一加（API 36）跑的是同一份代码、同一个效果。
 * 曾经按 API 31 分岔（RenderEffect vs 双层残影妥协）已废除：妥协即阉割。
 *
 * 过渡只由**行号**触发，不由逐字扫描触发：后者每 200ms 变一次 annotated 串，
 * 拿它当键会让每一句都在反复播过渡动画。
 *
 * ---------------------------------------------------------------------------
 * HUD 淡出时它**不动也不变暗**
 *
 * 用户口径里的"纯净看词态"指的是左下封面与右下控制那两块退场，
 * 歌词本来就是这一屏的主体。跟着一起淡会让"看得更清楚"变成"什么都看不见"。
 * 所以本函数**不接受任何可见性参数** —— 它没有可淡出的状态。
 */
@Composable
fun LyricStage(
    doc: LyricsDoc,
    positionMs: Int,
    metrics: MusicMetrics,
    /**
     * 没有歌词可画时主位显示什么。
     *
     * **由调用方决定，而不是这里按 `doc.isEmpty` 自己猜**：因为"空"有三种原因
     * （还在拉取 / 纯音乐 / 确实没有），三种该说的话完全不同，
     * 而只有页面知道现在是哪一种。传 null 表示什么都不显示。
     */
    placeholder: String?,
    /** 封面主色。主字色由它派生（见 [ArtworkBackdrop.lyricInk]）。 */
    accent: Color,
    /** 折行的宽度上限。见类注释 —— 由页面按内容区可用宽算出来传进来。 */
    maxLineWidth: Dp,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
) {
    val (currentIdx, nextIdx) = doc.focusAt(positionMs)

    // 前奏（还没唱到第一句）时把预告行放进主位 —— 第一句开唱时它留在原地，
    // 整块歌词不会往上跳一行。
    val bigIdx = if (currentIdx >= 0) currentIdx else nextIdx
    val isSinging = currentIdx >= 0 && currentIdx == bigIdx
    val line = doc.lines.getOrNull(bigIdx)

    val lyricColor = ArtworkBackdrop.lyricInk(accent)

    val target: AnnotatedString = when {
        // 没有行也没有占位话 → 空串（高度仍在，不塌）。
        line == null -> AnnotatedString(placeholder.orEmpty())

        // 逐字（本地导入的 Enhanced LRC）：唱过的音节用满色，没唱到的压到 45%。
        // **不用"整行渐变"**：逐字歌词的信息量就在"哪个字正在被唱"。
        isSinging && line.syllables.size > 1 -> buildAnnotatedString {
            line.syllables.forEach { syllable ->
                val color = if (syllable.startMs <= positionMs) {
                    lyricColor
                } else {
                    lyricColor.copy(alpha = UPCOMING_SYLLABLE_ALPHA)
                }
                withStyle(SpanStyle(color = color)) { append(syllable.text) }
            }
        }

        else -> AnnotatedString(line.text)
    }

    // ---- 切句过渡（见类注释）：行号是唯一的键 ----
    //
    // 只有一条时间轴：0 → 1 的 [LINE_SWAP_MS]。新旧两句**读同一个 p**、各取一半 ——
    // 两条独立的 animateTo 会让"旧句淡完"与"新句开始淡"之间出现一段双方都很淡的空档。
    var prevIdx by remember { mutableIntStateOf(bigIdx) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(bigIdx) {
        if (prevIdx == bigIdx) return@LaunchedEffect
        progress.snapTo(0f)
        progress.animateTo(1f, tween(LINE_SWAP_MS))
        // 动画结束才推进 prevIdx：进行中它必须还是"旧句"，否则 oldTarget 会变成新句。
        prevIdx = bigIdx
    }
    val p = progress.value.coerceIn(0f, 1f)
    val fading = prevIdx != bigIdx
    // 旧句取 prevIdx 那一行（它的逐字扫描用实时位置算 —— 换句瞬间旧句已唱完，
    // 扫描结果天然是"全部唱过"的满色，正合适）。
    val oldTarget: AnnotatedString = run {
        val oldLine = doc.lines.getOrNull(prevIdx)
        val wasSinging = currentIdx >= 0 && currentIdx == prevIdx
        when {
            oldLine == null -> AnnotatedString(placeholder.orEmpty())
            wasSinging && oldLine.syllables.size > 1 -> buildAnnotatedString {
                oldLine.syllables.forEach { s ->
                    val color = if (s.startMs <= positionMs) lyricColor else lyricColor.copy(alpha = UPCOMING_SYLLABLE_ALPHA)
                    withStyle(SpanStyle(color = color)) { append(s.text) }
                }
            }
            else -> AnnotatedString(oldLine.text)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .offset(x = driftX, y = driftY),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(metrics.lyricBlockHeight.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(
                space = metrics.lyricGap.dp,
                alignment = Alignment.CenterVertically,
            ),
        ) {
            // 一个槽位，新旧两句叠在里面交叉淡溶 —— 分两个槽位会上下排开，
            // 那就成了"换行"而不是"换句"。
            Box(contentAlignment = Alignment.Center) {
                if (fading) {
                    LyricLineText(
                        text = oldTarget,
                        fontSize = metrics.lyricSize,
                        lineHeight = metrics.lyricLineHeight,
                        maxWidth = maxLineWidth,
                        color = lyricColor,
                        alpha = 1f - p,
                        // 往外张（1.00 → 1.06）：旧句是"被推散"。
                        // 反过来缩小会让它像往屏幕里退，与"飘走"的观感相反。
                        scaleX = 1f + (LINE_SCALE_OUT_END - 1f) * p,
                        blurDp = BLUR_OUT_DP * p,
                    )
                }
                LyricLineText(
                    text = target,
                    fontSize = metrics.lyricSize,
                    lineHeight = metrics.lyricLineHeight,
                    maxWidth = maxLineWidth,
                    color = lyricColor,
                    // 稳态（没有过渡）恒为 1；过渡中从 0 长到 1。
                    alpha = p,
                    // 从 1.08 收进来（新句是"被收拢"）。
                    scaleX = LINE_SCALE_IN_START - (LINE_SCALE_IN_START - 1f) * p,
                    // 最后 8% 直接把模糊清零（[SHARPEN_AT]）：插值到 0 会在 0~2dp 那一段
                    // 停很久（人眼对这段最敏感），看起来像"糊了很久才突然清楚"。
                    blurDp = if (p >= SHARPEN_AT) 0f else BLUR_IN_DP * (1f - p),
                )
            }
        }
    }
}

/** 切句过渡的总时长（ms，单条时间轴）。 */
private const val LINE_SWAP_MS = 600

/** 换句时旧句模糊的最大半径（dp）。比新句略小：它是"散开"，不是"糊住"。 */
private const val BLUR_OUT_DP = 20f

/** 换句时新句模糊的起始半径（dp）。略大于 [BLUR_OUT_DP] —— 新句要"从更虚的地方收进来"。 */
private const val BLUR_IN_DP = 22f

/** 新句的模糊在进度到这一比例时直接归零。见 [LyricStage] 里 `blurDp` 的注释。 */
private const val SHARPEN_AT = 0.92f

/** 旧句横向展开的终值（1.00 → 这个值）。 */
private const val LINE_SCALE_OUT_END = 1.06f

/** 新句横向收拢的起始值（这个值 → 1.00）。 */
private const val LINE_SCALE_IN_START = 1.08f

/** 还没唱到的音节的不透明度。唱过的是满色。 */
private const val UPCOMING_SYLLABLE_ALPHA = 0.45f

/**
 * 一行歌词的渲染。**样式在这里只有一份**：衬线粗体、显式行高、居中、最多两行。
 *
 * ---------------------------------------------------------------------------
 * 为什么走 `drawIntoCanvas` + `StaticLayout` + `BlurMaskFilter`
 * （2026-10-15 用户口径：小米 API 30 与一加 API 36 必须 100% 同一套代码）
 *
 * 换句过渡要的是**真高斯模糊聚拢**。Compose 的 `Modifier.blur` /
 * `graphicsLayer.renderEffect` 底层是 RenderEffect，**API 31 才生效** ——
 * 在 API 30 的小米上静默无效果。曾用"双层微偏移残影"妥协，被用户否决：
 * QQ 音乐在 API 30 上同样是真高斯模糊，妥协就是阉割。
 *
 * 正解是 `android.graphics.BlurMaskFilter`：**API 1 就存在的原生能力**，
 * 走 `drawIntoCanvas { canvas.nativeCanvas }` 直接用系统 Paint 画
 * （硬件加速下 API 28+ 完整支持，两台在役机器 30 / 36 都覆盖）。
 * 多行居中用 [StaticLayout]（ALIGN_CENTER + setMaxLines(2)），
 * 逐字高亮把 AnnotatedString 的 SpanStyle 翻成 [ForegroundColorSpan]。
 *
 * 半径 ≤ 0.5dp 时 **maskFilter 置 null**（不是设个 0 半径的滤镜）——
 * 稳态下不该有一个"模糊半径为 0"的滤镜对象挂在 Paint 上。
 *
 * @param maxWidth 折行的宽度上限。`min(这个, 槽位宽)` 才是 StaticLayout 的排版宽度 ——
 *   排版块再整体水平居中，所以折行后两行的中心仍在屏幕中线上。
 * @param scaleX 横向缩放。**只缩横向**：纵向一起缩会让两行折句整块"呼吸"，
 *   那是布局变化不是过渡（见 [LyricStage] 的类注释）。
 * @param blurDp 模糊半径（dp），由调用方按过渡进度算好传进来。
 */
@Composable
private fun LyricLineText(
    text: AnnotatedString,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    maxWidth: Dp,
    color: Color,
    alpha: Float,
    scaleX: Float,
    blurDp: Float,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { fontSize.toPx() }
    // StaticLayout 的行距用倍数表达：显式行高 ÷ 字号。Compose 的 lineHeight 是精确值，
    // 这里是"同一意图的最近近似"—— 布局整体在块高内居中，行距差只影响组内微偏心，
    // 不会溢出（块高就是两行预算，见 LyricStage）。
    val lineSpacingMult = with(density) { lineHeight.toPx() / fontSize.toPx() }
    val maxWidthPx = with(density) { maxWidth.toPx() }
    val blurPx = with(density) { blurDp.dp.toPx() }

    // Paint 复用：每帧新建一个 Paint 会让过渡期每帧都分配；
    // remember 住、每帧只改三个字段（maskFilter / textSize / color）。
    // 用 TextPaint 而不是 Paint：StaticLayout.Builder.obtain 要的是它。
    val paint = remember {
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                this.alpha = alpha
                this.scaleX = scaleX
                // 纵向恒为 1：见 KDoc。
                this.scaleY = 1f
            }
            .drawBehind {
                drawIntoCanvas { canvas ->
                    paint.maskFilter =
                        if (blurPx > 0.5f) BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) else null
                    paint.textSize = textSizePx
                    paint.color = color.toArgb()

                    // 排版宽度取"槽位宽"与"折行上限"的较小者。
                    val layoutW = minOf(size.width, maxWidthPx).coerceAtLeast(1f)
                    val spannable = toSpannable(text)
                    val layout = StaticLayout.Builder
                        .obtain(spannable, 0, spannable.length, paint, layoutW.toInt())
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, lineSpacingMult)
                        .setIncludePad(false)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()

                    val native = canvas.nativeCanvas
                    // 整块在槽位内垂直居中：单句不折行时不会贴顶，两行时作为一组居中。
                    val top = (size.height - layout.height) / 2f
                    native.save()
                    // 排版块比槽位窄时，把**块**摆到中间 —— ALIGN_CENTER 管的是块内居中，
                    // 不管块在槽位里的位置。
                    native.translate((size.width - layoutW) / 2f, top)
                    layout.draw(native)
                    native.restore()
                }
            },
    )
}

/**
 * AnnotatedString → Spannable：把逐字高亮的 [SpanStyle.color] 翻成
 * [ForegroundColorSpan]。没有 span 的字用 Paint 的基色（歌词主色）。
 */
private fun toSpannable(text: AnnotatedString): CharSequence {
    if (text.spanStyles.isEmpty()) return text.text
    val ssb = SpannableStringBuilder(text.text)
    text.spanStyles.forEach { range ->
        val spanColor = range.item.color ?: return@forEach
        ssb.setSpan(
            ForegroundColorSpan(spanColor.toArgb()),
            range.start,
            range.end,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
    }
    return ssb
}

/**
 * 水印该显示什么。
 *
 * 规则（2026-10-15 加了第一条"动态取词"，改之前先读）：
 *   1. **有同步歌词时，取当前句的首词** —— 英文取第一个单词（`I'll` / `Oh`），
 *      中文取前 2 个汉字。水印跟着歌词滚动，参考视频里大字是随演唱内容变的。
 *   2. 没有歌词（前奏/间奏/纯音乐）时回退歌手名：**优先含中文的片段**
 *      （`G.E.M. 邓紫棋` 取 `邓紫棋`），没有中文取第一个词（`The Weeknd` 取 `The`）。
 *   3. 歌手名也没有时回退到**歌名** —— 总有东西可衬。
 *
 * 抽成纯函数是为了能单测：分支多，而真机上要凑齐得换好几首歌。
 */
internal fun watermarkTextOf(artist: String, fallback: String?, currentLine: String?): String {
    if (!currentLine.isNullOrBlank()) return firstWordOf(currentLine)
    val source = artist.trim().ifBlank { fallback.orEmpty().trim() }
    if (source.isEmpty()) return ""
    val tokens = source
        .split(' ', '\u3000', '·', '/', ',', '、', '-')
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return source.take(WATERMARK_MAX_CHARS)
    val cjkToken = tokens.firstOrNull { token -> token.any { it.code in CJK_RANGE } }
    return (cjkToken ?: tokens.first()).take(WATERMARK_MAX_CHARS).trim()
}

/**
 * 一句歌词的首词：**中文前 2 字，英文第一个单词**。
 *
 * 英文按空白与连字符切（`Oh-oh-oh` 取 `Oh`），撇号算词的一部分（`I'll` 不拆）——
 * 参考视频里英文大字就是 `I'll` / `Oh` / `Can't` 这种单个词。
 * 行首的标点/引号先剥掉：`"Yeah"` 取 `Yeah`，不是 `"Y`。
 */
internal fun firstWordOf(line: String): String {
    val trimmed = line.trim().trimStart { ch -> !ch.isLetter() && ch.code !in CJK_RANGE }
    if (trimmed.isEmpty()) return ""
    val cjk = trimmed.takeWhile { it.code in CJK_RANGE }
    if (cjk.isNotEmpty()) return cjk.take(2)
    // 英文：第一个空白/连字符分隔的词。撇号属于词的一部分（I'll / Can't 不拆）。
    // 词尾的引号/逗号等标点再剥掉（"No" → No，不是 No"）。
    //
    // **不套 [WATERMARK_MAX_CHARS] 那个 4 字帽**：那是给汉字定的（105sp 一行放得下
    // 四个汉字），而英文单词按参考视频的口径是**整个词** —— "Can't" 五个字母，
    // 截成 "Can'" 是个坏掉的词。只留一个 12 字符的防失控帽（没有空格的长串）。
    val word = trimmed
        .takeWhile { it != ' ' && it != '\t' && it != '\u3000' && it != '-' }
        .trimEnd { ch -> !ch.isLetter() && ch.code !in CJK_RANGE }
    return word.take(ENGLISH_WORD_MAX_CHARS).trim()
}

/** 英文首词的防失控帽。参考视频口径是"整个词"（Can't 五个字母也不截），这里是兜底。 */
private const val ENGLISH_WORD_MAX_CHARS = 12

/** 水印最多几个字。多了在 666 dp 宽的屏上会溢出（105sp × 4 ≈ 420dp，还留有余量）。 */
private const val WATERMARK_MAX_CHARS = 4

/** CJK 统一表意文字。只用来判"这一段是不是中文"，不涉及任何渲染细节。 */
private val CJK_RANGE = 0x4E00..0x9FFF
