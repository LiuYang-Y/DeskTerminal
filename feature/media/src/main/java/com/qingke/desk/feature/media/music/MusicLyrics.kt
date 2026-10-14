package com.qingke.desk.feature.media.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
import com.qingke.desk.feature.media.music.lyrics.LyricLine
import com.qingke.desk.feature.media.music.lyrics.LyricsDoc

/**
 * 背景大衬字（水印）。
 *
 * 参考图里那层"巨幅歌手名"是整页景深感的来源：它在最底下、颜色接近底色、
 * 字号接近屏高的三成，视线扫过去先认形再认字。
 *
 * ---------------------------------------------------------------------------
 * 为什么是"取 2~4 字"而不是整个歌手名
 *
 * 字号到 105~120sp 之后，一行最多也就放得下四五个汉字（666 dp 宽 ÷ 105 dp ≈ 6 字）。
 * 整段名字硬塞会溢出到屏幕外，而 `Text` 的 `Ellipsis` 在这么大的字号上会截成
 * "李荣…" 这种半截样子 —— 比不显示更难看。所以取"核心词"，规则见 [watermarkTextOf]。
 *
 * 歌手名为空时回退到当前这句歌词的前 4 个字 —— 总有东西可衬，不会出现"这一页没水印"。
 */
@Composable
fun Watermark(
    text: String,
    size: TextUnit,
    alpha: Float,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
) {
    if (text.isBlank() || alpha <= 0f) return
    Text(
        text = text,
        fontSize = size,
        lineHeight = size * MusicLayout.LYRIC_LINE_HEIGHT_EM,
        color = Color.White,
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
 * 中央歌词舞台：**2 行聚焦**（当前行 + 下一行预告）。
 *
 * 两行的高度是**由构造保证**的：字号先夹取（26..32sp），行高再用夹取后的字号
 * 乘 [MusicLayout.LYRIC_LINE_HEIGHT_EM] 回算，所以整块高度恒等于
 * `2 × 行高 + 间距`，不会因为某一行字数多就塌下来。
 *
 * ---------------------------------------------------------------------------
 * HUD 淡出时它**不动也不变暗**
 *
 * 用户口径里的"纯净看词态"指的是左下封面与右下控制那两块退场，
 * 歌词本来就是这一屏的主体。跟着一起淡会让"看得更清楚"变成"什么都看不见"。
 * 所以本函数**不接受任何可见性参数** —— 它没有可淡出的状态。
 *
 * ---------------------------------------------------------------------------
 * 前奏时不让字跳
 *
 * 还没唱到第一句时（[LyricsDoc.focusAt] 返回 `(-1, 0)`），把它放进**大字号那一格**
 * 渲染，而不是放进下面的小格。于是第一句开唱时它留在原地、只是从暗变亮、下面多出第二句 ——
 * 反过来做的话，第一句开唱那一刻整块歌词会往上跳一行。
 *
 * ---------------------------------------------------------------------------
 * 逐字（本地导入的 Enhanced LRC）
 *
 * 当前行有音节时间轴时，用 [AnnotatedString] 按音节上色（唱过的亮、没唱的暗）。
 * 这是**在文本层做高亮**，不是叠一层裁剪遮罩 —— 中英文混排时"按比例裁剪"和
 * "按音节推进"完全对不上（一个汉字和一个 "night" 的时长差好几倍）。
 *
 * 它带来的重组是**必要且有限的**：位置每 200 ms 更新一次，只有这两行 [Text] 会重组。
 * 真正的逐帧元素（声波圆环、进度条）走的是绘制期读状态，不在这一条路径上。
 */
@Composable
fun LyricStage(
    doc: LyricsDoc,
    positionMs: Int,
    metrics: MusicMetrics,
    /**
     * 没有歌词可画时，大字号那一格显示什么。
     *
     * **由调用方决定，而不是这里按 `doc.isEmpty` 自己猜**：因为"空"有三种原因
     * （还在拉取 / 纯音乐 / 确实没有），三种该说的话完全不同，
     * 而只有页面知道现在是哪一种。传 null 表示什么都不显示。
     */
    placeholder: String?,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
) {
    val (currentIdx, nextIdx) = doc.focusAt(positionMs)

    val bigIdx = if (currentIdx >= 0) currentIdx else nextIdx
    val smallIdx = if (currentIdx >= 0) nextIdx else -1
    // 只有"正在唱的那一行"才用满色；前奏里的预告是暗的。
    val bigIsActive = currentIdx >= 0 && currentIdx == bigIdx

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
            LyricSlot(
                line = doc.lines.getOrNull(bigIdx),
                positionMs = positionMs,
                fontSize = metrics.lyricSize,
                lineHeight = metrics.lyricLineHeight,
                bright = bigIsActive,
                wordSweep = true,
                placeholder = placeholder,
            )
            LyricSlot(
                line = doc.lines.getOrNull(smallIdx),
                positionMs = positionMs,
                // 预告行比主行小一档。0.82 是"明显更小但还读得清"的位置。
                fontSize = (metrics.lyricSizeSp * PREVIEW_SIZE_RATIO).sp,
                lineHeight = (metrics.lyricLineHeightSp * PREVIEW_SIZE_RATIO).sp,
                bright = false,
                wordSweep = false,
                placeholder = null,
            )
        }
    }
}

/** 预告行相对主行的字号比例。 */
private const val PREVIEW_SIZE_RATIO = 0.82f

/** 还没唱到的音节的不透明度。唱过的是满色。 */
private const val UPCOMING_SYLLABLE_ALPHA = 0.45f

/** 非当前行的不透明度。 */
private const val IDLE_LINE_ALPHA = 0.42f

@Composable
private fun LyricSlot(
    line: LyricLine?,
    positionMs: Int,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    bright: Boolean,
    wordSweep: Boolean,
    placeholder: String?,
) {
    val annotated: AnnotatedString = when {
        line == null -> AnnotatedString(placeholder.orEmpty())

        wordSweep && line.syllables.size > 1 -> buildAnnotatedString {
            line.syllables.forEach { syllable ->
                // 唱过的音节用满色，还没唱到的压到 45%。**不用"整行渐变"**：
                // 逐字歌词的信息量就在"哪个字正在被唱"，整行渐变会把它抹平。
                val color = if (syllable.startMs <= positionMs) {
                    Color.White
                } else {
                    Color.White.copy(alpha = UPCOMING_SYLLABLE_ALPHA)
                }
                withStyle(SpanStyle(color = color)) { append(syllable.text) }
            }
        }

        else -> AnnotatedString(line.text)
    }

    val color = when {
        placeholder != null -> Color.White.copy(alpha = 0.72f)
        bright -> Color.White
        else -> Color.White.copy(alpha = IDLE_LINE_ALPHA)
    }

    Text(
        text = annotated,
        fontSize = fontSize,
        // **行高必须显式给。** 不写的话 Compose 用字体度量算行高（中文约 1.56em），
        // 于是 [MusicMetrics.lyricBlockHeight] 那个"两行 + 间距"的高度就是错的 ——
        // 项目已经因为这件事被抓到过一次（红字压卡片顶边 4dp）。见 MusicLayout 的注释。
        lineHeight = lineHeight,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center,
        fontWeight = if (bright) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * 水印该显示什么。
 *
 * 规则（三条都要有理由，改之前先读）：
 *   1. **优先取含中文的片段** —— `G.E.M. 邓紫棋` 取 `邓紫棋` 而不是 `G.E.M.`。
 *      中英混排的歌手名里，中文那一段才是"名字"。
 *   2. 没有中文就取第一个词 —— `The Weeknd` 取 `The`（取整串会溢出）。
 *   3. 歌手名为空时回退到**歌名** —— 总有东西可衬。
 *
 * 注意回退用的是歌名而**不是当前这句歌词**：后者会让水印每换一句就变一次，
 * 而水印是这一页最大的一块静止元素 —— 让它跟着歌词闪，既抢视线，
 * 也让"固定在背景上的大字"这个视觉隐喻失效。歌名在一首歌里是恒定的。
 *
 * 抽成纯函数是为了能单测：它有三条分支，而这三条在真机上要凑齐得换好几首歌。
 */
internal fun watermarkTextOf(artist: String, fallback: String?): String {
    val source = artist.trim().ifBlank { fallback.orEmpty().trim() }
    if (source.isEmpty()) return ""
    val tokens = source
        .split(' ', '\u3000', '·', '/', ',', '、', '-')
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) return source.take(WATERMARK_MAX_CHARS)
    val cjkToken = tokens.firstOrNull { token -> token.any { it.code in CJK_RANGE } }
    return (cjkToken ?: tokens.first()).take(WATERMARK_MAX_CHARS).trim()
}

/** 水印最多几个字。多了在 666 dp 宽的屏上会溢出（105sp × 4 ≈ 420dp，还留有余量）。 */
private const val WATERMARK_MAX_CHARS = 4

/** CJK 统一表意文字。只用来判"这一段是不是中文"，不涉及任何渲染细节。 */
private val CJK_RANGE = 0x4E00..0x9FFF
