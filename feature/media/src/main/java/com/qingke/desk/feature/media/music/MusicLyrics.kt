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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    Text(
        text = text,
        fontSize = size,
        lineHeight = size * MusicLayout.LYRIC_LINE_HEIGHT_EM,
        color = color,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            // 防烧屏微位移用 offset 而不是 padding：偏移量是正负 6 dp，
            // `Modifier.padding` 不接受负值，而水印一半时间会往左/往上走。
            .offset(x = driftX, y = driftY)
            .alpha(alpha),
    )
}

/**
 * 中央歌词舞台：**单句双行居中 + 衬线粗体**（2026-10-15 对标参考视频重写）。
 *
 * ---------------------------------------------------------------------------
 * 与旧版的区别（旧版是"当前行大字 + 下一行半透明小字预告"的列表感）
 *
 * 参考视频的中央只有**当前这一句**：单句较长时在语义空格处折成上下两行，
 * 两行字号、字重、颜色**完全一致**；字体显式 `FontFamily.Serif + FontWeight.Bold`，
 * 颜色取封面主色的高明度浅亮变体（[ArtworkBackdrop.light]）—— 有色调的浅色，
 * 不是刺眼纯白。块高仍是两行预算（[MusicMetrics.lyricBlockHeight]），
 * 单句不折行时垂直居中，不会塌。
 *
 * ---------------------------------------------------------------------------
 * 切句过渡：320ms 真高斯模糊淡入淡出（全 API 版本同一套代码）
 *
 * 换句时旧句淡出（缩小到 0.94、模糊加重到 14dp），新句淡入（0.94 长到 1.0、
 * 模糊从 14dp 消散到 0）。模糊走 [BlurMaskFilter] + `drawIntoCanvas` 的
 * 原生 Paint 绘制（见 [LyricLineText]）—— **API 1 起就有的能力**，
 * 小米（API 30）与一加（API 36）跑的是同一份代码、同一个效果。
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
    /** 封面主色。主字色取它的高明度浅亮变体（见 [ArtworkBackdrop.light]）。 */
    accent: Color,
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

    val lyricColor = ArtworkBackdrop.light(accent)

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
    var prevIdx by remember { mutableIntStateOf(bigIdx) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(bigIdx) {
        if (prevIdx == bigIdx) return@LaunchedEffect
        // 旧句淡出（前半程）
        swap.animateTo(0f, tween(LINE_SWAP_HALF_MS))
        prevIdx = bigIdx
        // 新句淡入（后半程）
        swap.animateTo(1f, tween(LINE_SWAP_HALF_MS))
    }
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
                        color = lyricColor,
                        alpha = swap.value.coerceIn(0f, 1f),
                        scale = SCALE_MIN + (1f - SCALE_MIN) * swap.value,
                        haze = (1f - swap.value).coerceIn(0f, 1f),
                    )
                }
                LyricLineText(
                    text = target,
                    fontSize = metrics.lyricSize,
                    lineHeight = metrics.lyricLineHeight,
                    color = lyricColor,
                    // 稳态（没有过渡）恒为 1；过渡后半从 0 长到 1。
                    alpha = if (fading) (1f - swap.value).coerceIn(0f, 1f) else 1f,
                    scale = if (fading) 1f - (1f - SCALE_MIN) * swap.value else 1f,
                    haze = if (fading) swap.value.coerceIn(0f, 1f) else 0f,
                )
            }
        }
    }
}

/** 换句动画前半/后半各自的时长（合计 320ms，用户口径）。 */
private const val LINE_SWAP_HALF_MS = 160

/** 还没唱到的音节的不透明度。唱过的是满色。 */
private const val UPCOMING_SYLLABLE_ALPHA = 0.45f

/** 换句时新句的初始缩放（长到 1.0f）。 */
private const val SCALE_MIN = 0.94f

/**
 * 换句时模糊的最大半径（dp）。**真高斯模糊，全 API 版本同一套代码** ——
 * 走 [BlurMaskFilter]（API 1 原生能力，硬件加速下 API 28+ 完整支持），
 * 不是 RenderEffect（API 31+）。用户口径：小米 API 30 与一加 API 36 必须一致。
 */
private const val LINE_SWAP_BLUR_DP = 14f

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
 * @param haze 朦胧度 0..1。过渡中用：0 = 稳态（无滤镜），1 = 最大模糊。
 */
@Composable
private fun LyricLineText(
    text: AnnotatedString,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    color: Color,
    alpha: Float,
    scale: Float,
    haze: Float,
) {
    val density = LocalDensity.current
    val textSizePx = with(density) { fontSize.toPx() }
    // StaticLayout 的行距用倍数表达：显式行高 ÷ 字号。Compose 的 lineHeight 是精确值，
    // 这里是"同一意图的最近近似"—— 布局整体在块高内居中，行距差只影响组内微偏心，
    // 不会溢出（块高就是两行预算，见 LyricStage）。
    val lineSpacingMult = with(density) { lineHeight.toPx() / fontSize.toPx() }
    val maxBlurPx = with(density) { LINE_SWAP_BLUR_DP.dp.toPx() }

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
                this.scaleX = scale
                this.scaleY = scale
            }
            .drawBehind {
                drawIntoCanvas { canvas ->
                    val blurPx = haze * maxBlurPx
                    paint.maskFilter =
                        if (blurPx > 0.5f) BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL) else null
                    paint.textSize = textSizePx
                    paint.color = color.toArgb()

                    val spannable = toSpannable(text)
                    val layout = StaticLayout.Builder
                        .obtain(spannable, 0, spannable.length, paint, size.width.toInt().coerceAtLeast(1))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, lineSpacingMult)
                        .setIncludePad(false)
                        .setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END)
                        .build()

                    // 整块在槽位内垂直居中：单句不折行时不会贴顶，两行时作为一组居中。
                    val native = canvas.nativeCanvas
                    val top = (size.height - layout.height) / 2f
                    native.save()
                    native.translate(0f, top)
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
