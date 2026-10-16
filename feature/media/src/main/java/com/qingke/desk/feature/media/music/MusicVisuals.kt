package com.qingke.desk.feature.media.music

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.feature.media.music.artwork.ArtworkBackdrop
import com.qingke.desk.feature.media.music.artwork.ArtworkVisual
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 背景层。四档的差别**全部落在这里**（见 [BackgroundSpec]）。
 *
 * 它铺满整页、在最底下，不参与命中测试 —— 页面的点击唤醒是在外层统一处理的。
 *
 * ---------------------------------------------------------------------------
 * 左暗右亮的戏剧性光场（2026-10-15 对标参考视频改）
 *
 * 旧版是 `base → accent → base` 的斜向渐变，两端都暗，整体"平"。
 * 参考视频的光场是**水平三段**：左端压到接近深黑（明度 0.18）→ 中部过渡（0.50）
 * → 右端高饱和暖亮（0.86）。三个停靠点全部由封面主色按 HSV 明度绝对派生
 * （见 [ArtworkBackdrop.shade]）—— 不用乘系数的原因是：封面本身暗时右端永远亮不起来，
 * 封面亮时左端永远暗不下去，只有绝对明度能保证"每首歌都是同一束光"。
 *
 * 防烧屏漂移**不适用**于这一层：渐变是跨全屏的连续插值，没有固定边缘，
 * 烧屏风险来自高对比静止 UI（水印、歌词、HUD），那几处照旧走 [BurnInGuard]。
 */
@Composable
fun MusicBackdrop(
    visual: ArtworkVisual,
    spec: BackgroundSpec,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            // OFF 档只有一块纯色。**这不是"偷懒的同一条路径"**：
            // 渐变本身也是一次跨屏插值采样，OFF 的语义就是"一层动效都不建"。
            //
            // 条件写在 `then` 上而不是给 `background` 传个透明色：后者会照样铺一次
            // 全屏的透明矩形（多一层绘制、也多一层合成），而 OFF 档的整个意思就是"不加层"。
            .then(if (spec.solidOnly) Modifier.background(visual.base) else Modifier),
    ) {
        if (spec.solidOnly) return@Box

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0f to ArtworkBackdrop.shade(visual.accent, LIGHT_FIELD_LEFT),
                            0.5f to ArtworkBackdrop.shade(visual.accent, LIGHT_FIELD_MID),
                            1f to ArtworkBackdrop.shade(visual.accent, LIGHT_FIELD_RIGHT),
                        ),
                    ),
                ),
        )

        val backdrop = visual.backdrop
        if (backdrop != null && spec.backdropAlpha > 0f) {
            Image(
                bitmap = backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = spec.backdropAlpha,
                modifier = Modifier.fillMaxSize(),
            )
            // 底图之上必须有压暗层：底图是"放大后的封面"，明度不受控，
            // 不压一层的话浅色歌词在浅色专辑上读不清（取色的明度夹取只保住了渐变那一层）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = spec.scrimAlpha)),
            )
        }

        // 四角压暗（vignette）。作用是把视线收拢到中央的歌词，而不是"加个滤镜"。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = spec.vignetteAlpha)),
                        radius = 1600f,
                    ),
                ),
        )
    }
}

/** 背景的分档参数。由 [backgroundSpec] 从 [EffectTier] 映射而来。 */
data class BackgroundSpec(
    /** 只画一块纯色（OFF 档）。 */
    val solidOnly: Boolean,
    val backdropAlpha: Float,
    val scrimAlpha: Float,
    val vignetteAlpha: Float,
)

fun backgroundSpec(tier: EffectTier): BackgroundSpec = when (tier) {
    // 2026-10-15 真机校准（小米 3200×1440 截图采样）：旧值 backdrop 0.55 / scrim 0.30
    // 把左暗右亮渐变盖得左右几乎对称（采样 RGB 差 <20），参考视频的"戏剧性光场"
    // 完全出不来。底图只该提供**微妙的封面纹理**，光场必须由渐变主导 ——
    // 三层全部调低，渐变透出 70% 以上，左端接近深黑、右端明亮才立得住。
    EffectTier.HIGH -> BackgroundSpec(false, backdropAlpha = 0.26f, scrimAlpha = 0.16f, vignetteAlpha = 0.40f)
    EffectTier.MEDIUM -> BackgroundSpec(false, backdropAlpha = 0.20f, scrimAlpha = 0.20f, vignetteAlpha = 0.35f)
    EffectTier.LOW -> BackgroundSpec(false, backdropAlpha = 0.00f, scrimAlpha = 0.00f, vignetteAlpha = 0.28f)
    EffectTier.OFF -> BackgroundSpec(true, backdropAlpha = 0f, scrimAlpha = 0f, vignetteAlpha = 0f)
}

// ---------------------------------------------------------------------------------
// 声波圆环
// ---------------------------------------------------------------------------------

/**
 * 圆环的分档参数。**`rings` 只当开关用**（0 = 这一档不画），见 [WaveRings] 的类注释。
 *
 * `periodMs` 是**一个完整波周期**的时间（波形向外扩散一轮）。改成渐变之后
 * 波形是连续的一条曲线，"几道环"不再是可数的整数，所以周期本身成了主要的节奏旋钮：
 * 0.35 周期/秒（约 2.86 s 一轮）是"看得出在动、但盯着看不心慌"的位置。
 */
data class WaveSpec(
    val rings: Int,
    val alpha: Float,
    val periodMs: Float,
)

fun waveSpec(tier: EffectTier): WaveSpec = when (tier) {
    // 2857 ms = 0.35 周期/秒。三档共用同一个速度观感，靠 alpha 分强弱 ——
    // 低档再把速度也放慢会让"低性能档"看起来像卡住，而不是省电。
    EffectTier.HIGH -> WaveSpec(rings = 4, alpha = 0.30f, periodMs = 2_857f)
    EffectTier.MEDIUM -> WaveSpec(rings = 3, alpha = 0.22f, periodMs = 3_600f)
    EffectTier.LOW -> WaveSpec(rings = 2, alpha = 0.14f, periodMs = 4_200f)
    EffectTier.OFF -> WaveSpec(rings = 0, alpha = 0f, periodMs = 0f)
}

/**
 * 呼吸相位。`0..1` 循环。
 *
 * ---------------------------------------------------------------------------
 * "可停算"是怎么做到的（本项目硬约束，不是优化）
 *
 * `active` 为 false 时**协程根本不启动**（不是"启动了但把 alpha 画成 0"）。
 * 三种情况会让它为 false，缺一不可：
 *   1. 播放暂停（[NowPlaying.isPlaying] = false）；
 *   2. 页面不可见 —— 页面离开组合时 `LaunchedEffect` 自然取消；
 *   3. [EffectTier.OFF]。
 *
 * 三者任一成立时，这个进程里**没有任何 `withFrameNanos` 回调在排队**，
 * Choreographer 不会被我们唤醒。对一台 24 小时常亮的设备，
 * "跑着但画得看不出来"和"没跑"在耗电上是两件完全不同的事。
 *
 * 返回值是 [State]，**只许在绘制期读**（`Canvas { }` / `drawBehind { }` 的 lambda 里）。
 * 在组合期读它会让每帧都触发重组 —— 那正是我们要避开的做法。
 */
@Composable
fun rememberEffectPhase(active: Boolean, periodMs: Float): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active, periodMs) {
        if (!active || periodMs <= 0f) {
            // 停下来时把相位归零，下一次启动从 0 开始 —— 不要让它停在哪就留在哪，
            // 否则"暂停再播放"会看到圆环从半空中冒出。
            phase.floatValue = 0f
            return@LaunchedEffect
        }
        var baseNanos = -1L
        while (true) {
            val now = withFrameNanos { it }
            if (baseNanos < 0L) baseNanos = now
            phase.floatValue = (((now - baseNanos) / 1_000_000f) % periodMs) / periodMs
        }
    }
    return phase
}

/**
 * 左右两侧的**连续余弦声波**（2026-10-15 二次对标重写：细线圈 → 径向渐变波场）。
 *
 * ---------------------------------------------------------------------------
 * 为什么从"画圆环"改成"一条径向渐变"
 *
 * 旧版每个扩散相位画一对 `drawCircle(Stroke)`（暗的波谷 + 亮的波峰）。真机截图里能看出：
 *   · 环是**一根根独立的线**，相邻两环之间是背景原色 —— 波纹之间有空档，像同心圆分析图；
 *   · 每个环两端（`StrokeCap.Round` 的收口）留下可见接头；
 *   · 环数只能是整数，所以波"往外走"是一跳一跳的（环的消失与新生都发生在采样点上）。
 *
 * 参考视频里的波纹是**一整片连续起伏的光**：没有空档、没有接头。
 * 所以改成一条 `Brush.radialGradient`：沿半径放 [WAVE_STOPS] 个色标，
 * 每个色标的颜色与透明度按 `cos` 取值。渐变在色标之间做线性插值 ——
 * 48 段线性拼起来就是一条**看不出折线**的余弦波。
 *
 * 另外，整片波用**一次** `drawRect` 铺满（不再是每环一次绘制）：
 * 绘制调用从每侧 `2 × rings` 次降到 2 次，LOW 档少画的那点活在这里是实打实的。
 *
 * ---------------------------------------------------------------------------
 * 圆心在屏幕**外面**
 *
 * 左圆心 `x = −0.10W`、右圆心 `x = 1.06W`，`y = 0.50H`。圆心出屏有两层意思：
 *   1. 露出来的只有靠屏幕那一段圆弧 —— 波纹是**贴着两侧的竖带**，
 *      屏幕中央（歌词所在）自然留白，不需要再画一层遮罩去"抠"出干净区；
 *   2. 圆心那个奇点（半径 0 处所有相位挤在一起）根本不进画面，不会在中间糊成一团。
 *
 * 左右不对称（0.10 vs 0.06）是刻意的：完全对称会显得像工程示意图，
 * 差一点点才像"两束各自扩散的波"。
 *
 * ---------------------------------------------------------------------------
 * 光的颜色
 *
 * 每个色标是 `lerp(黑, 波峰色, crest)`，其中 `crest = (cos + 1) / 2`（1 = 波峰，0 = 波谷）：
 *   · **波谷**（crest = 0）= 黑 @ [TROUGH_ALPHA_LEFT]（左）/ [TROUGH_ALPHA_RIGHT]（右）——
 *     左侧背景本来就接近深黑，影要压得浓一点才看得见；右侧是亮底，浓黑会显脏。
 *   · **波峰**（crest = 1）= 封面主色的高明度变体 @ `spec.alpha`。
 *     用 [ArtworkBackdrop.light] 而不是 [ArtworkBackdrop.lyricInk]：这里要的是
 *     "跟着封面走的亮色"，与歌词那种"稳定的浅色"是两种诉求。
 *   · 中间的色标是两者的连续过渡 —— "亮环旁边必然跟着暗影"，立体感来自这条曲线本身，
 *     而不是来自第二种彩色。
 *
 * 透明度整体再乘 `(1 − f) × bass`：能量向外衰减，同时响度小时整片波一起暗下去。
 *
 * ---------------------------------------------------------------------------
 * `isPlaying` 与"有效响度"：外部播放源下不能整片波消失（2026-10-15 修）
 *
 * 响度只可能来自我们自己的 `TeeAudioProcessor` —— 它挂在**自家 ExoPlayer** 的
 * AudioSink 上。而音乐页大部分时间是**外部**系统媒体会话在播（QQ 音乐 App 自己播，
 * 那个 PCM 根本不过我们的进程）。此时 `level` 恒 0，于是：
 *   · 径向渐变的每个色标 alpha 都乘 0 → **整片波完全不可见**。
 * 用户看左右声波会"静止不动"。
 *
 * 所以这里把"页面在播"（[isPlaying]）与"有没有频谱"分开：
 * 拿不到频谱时，用一条**低频呼吸**兜底（`sin` 走 [FALLBACK_BREATH_HZ] 个周期/相位轮），
 * 让波纹继续起伏。一旦真频谱出现（`rawBass > [SPECTRUM_TRUST_FLOOR]`）就立刻换回真值 ——
 * 兜底只是"没数据时的替身"，不是滤波器。
 *
 * `phase` 与 `amplitude` 仍然只在**绘制期**读取（见 [rememberEffectPhase] 与
 * [com.qingke.desk.feature.media.music.spectrum.TeeAudioProcessor.level]），不会触发重组。
 * `amplitude` = 1 是"没有频谱数据时的满幅观感"，0 是静默（外部源播放时就是 0，见上）。
 */
@Composable
fun WaveRings(
    spec: WaveSpec,
    accent: Color,
    phase: State<Float>,
    amplitude: State<Float>,
    /** 页面是否在播。**只有它决定"没频谱时要不要兜底呼吸"**，与谱本身的来源无关。 */
    isPlaying: Boolean,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
) {
    // rings 只当开关：0 档（OFF）连 Canvas 都不建。非零档之间靠 alpha 分强弱。
    if (spec.rings <= 0) return
    val density = LocalDensity.current
    val driftXPx = with(density) { driftX.toPx() }
    val driftYPx = with(density) { driftY.toPx() }

    val peak = ArtworkBackdrop.light(accent)

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val p = phase.value
        // ---- 有效响度：真频谱优先，拿不到就按"在播"兜底呼吸 ----
        val rawBass = amplitude.value.coerceIn(0f, 1f)
        val bass = when {
            rawBass > SPECTRUM_TRUST_FLOOR -> rawBass
            isPlaying -> (0.35f + 0.35f * sin(p * FALLBACK_BREATH_RATE * PI).toFloat())
                .coerceAtLeast(0f)
            else -> 0f
        }

        // 半径比例与布局层同源（[MusicLayout.WAVE_WIDTH_RATIO]），免得"布局按 0.55W 算、
        // 画的时候是另一个数"这种两处各写一遍的漂移。响度再让它胀 [PULSE_GAIN]。
        val radiusMax = w * MusicLayout.WAVE_WIDTH_RATIO * (1f + PULSE_GAIN * bass)

        val centers = listOf(
            Offset(
                x = -CENTER_OFFSCREEN_LEFT * w + driftXPx,
                y = h * CENTER_Y_RATIO + driftYPx,
            ) to true,
            Offset(
                x = CENTER_OFFSCREEN_RIGHT * w + driftXPx,
                y = h * CENTER_Y_RATIO + driftYPx,
            ) to false,
        )

        centers.forEach { (center, isLeft) ->
            val troughAlpha = if (isLeft) TROUGH_ALPHA_LEFT else TROUGH_ALPHA_RIGHT

            val stops = Array(WAVE_STOPS) { i ->
                val f = i.toFloat() / (WAVE_STOPS - 1).toFloat()      // 0 = 圆心，1 = 最外圈
                // 相位里减 p：p 从 0 走到 1，整条波形向外挪一个完整波长（见 WaveSpec.periodMs）。
                val crest = (cos(TWO_PI * (f * WAVE_CYCLES - p)) + 1f) * 0.5f
                val fall = (1f - f) * bass
                val alpha = (troughAlpha + (spec.alpha - troughAlpha) * crest) * fall
                f to lerp(Color.Black, peak, crest).copy(alpha = alpha.coerceIn(0f, 1f))
            }

            drawRect(
                brush = Brush.radialGradient(
                    colorStops = stops,
                    center = center,
                    radius = radiusMax,
                    // Clamp：最外圈之外保持最后一个色标（近全透明），不做"从头再来一遍"。
                    // Repeat 是给无缝贴图用的，在这里会让半径外多出一圈假的波。
                    tileMode = TileMode.Clamp,
                ),
            )
        }
    }
}

/** 径向渐变的色标数。48 段线性插值已经看不出折线，再多只是增加每帧的数组长度。 */
private const val WAVE_STOPS = 48

/**
 * 半径方向上的**完整波周期数**。规范值 3.5，与 `radiusMax` 一起决定波纹的疏密。
 *
 * 3.5 个周期铺满 0.55W 的半径：每侧露出约 3~4 道亮带，与参考视频的疏密对齐。
 * （半周期的结尾让圆心端与最外圈端落在相反的相位上，两端都不会正好卡在波峰/波谷。）
 */
private const val WAVE_CYCLES = 3.5f

/** 响度对波场半径的增益：满响度时胀 12%。 */
private const val PULSE_GAIN = 0.12f

/**
 * 高过它才认为"这是真频谱"。低于它是 `TeeAudioProcessor` 的静默底噪（或它根本没在算）——
 * 见 [WaveRings] 类注释：外部源播放时 `level` 恒 0，那种 0 不是"安静"，是"没有数据"。
 */
private const val SPECTRUM_TRUST_FLOOR = 0.02f

/**
 * 没有频谱时的兜底呼吸的相位系数：`sin(p × 4π)`。
 *
 * p 走完一个波周期（HIGH 档约 2.86 s）时呼吸正好走 **2 个完整周期**；
 * 幅度落在 0 ~ 0.7（真频谱是 0 ~ 1），即"看得见在起伏，但不抢真实频谱的戏"。
 */
private const val FALLBACK_BREATH_RATE = 4f

/** 左圆心出屏的比例（在屏幕左侧之外）。 */
private const val CENTER_OFFSCREEN_LEFT = 0.10f

/** 右圆心出屏的位置（大于 1 即屏幕右侧之外）。比左侧少一点 —— 刻意的不对称，见类注释。 */
private const val CENTER_OFFSCREEN_RIGHT = 1.06f

/** 两个圆心的纵向位置，相对高度。0.50 = 屏幕中线。 */
private const val CENTER_Y_RATIO = 0.50f

/** 左组波谷的不透明度（浓黑，参考视频的暗侧）。 */
private const val TROUGH_ALPHA_LEFT = 0.35f

/** 右组波谷的不透明度（浅黑，亮底上太浓会显脏）。 */
private const val TROUGH_ALPHA_RIGHT = 0.25f

/** 2π。写成 `val` 而不是 `const`：`PI` 本身是 Double，转 Float 不是编译期常量。 */
private val TWO_PI = (2.0 * PI).toFloat()

/** 左暗右亮光场的三个停靠明度（HSV 的 V）。见 [MusicBackdrop] 的类注释。 */
private const val LIGHT_FIELD_LEFT = 0.18f
private const val LIGHT_FIELD_MID = 0.50f
private const val LIGHT_FIELD_RIGHT = 0.86f

/** 把一堆"要画的东西"合成一层的空壳，便于页面按 z 序摆放。 */
@Composable
fun VisualLayer(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier) { content() }
}

/**
 * 淡入淡出用的 alpha 动画。
 *
 * 抽出来是为了**统一所有淡出元素的时长**：HUD、水印、圆环在同一个时刻开始淡，
 * 各写各的时长会让它们散成三段。它们**只改 alpha，绝不改布局位置** ——
 * 改位置的话每次唤醒歌词都会跟着跳一下（本项目已验证过的坑）。
 */
@Composable
fun rememberFadeAlpha(visible: Boolean, durationMs: Int = 420): State<Float> =
    animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = durationMs, easing = LinearEasing),
        label = "music-fade",
    )
