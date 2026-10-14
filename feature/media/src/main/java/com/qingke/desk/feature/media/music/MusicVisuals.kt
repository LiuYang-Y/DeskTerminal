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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.feature.media.music.artwork.ArtworkVisual

/**
 * 背景层。四档的差别**全部落在这里**（见 [BackgroundSpec]）。
 *
 * 它铺满整页、在最底下，不参与命中测试 —— 页面的点击唤醒是在外层统一处理的。
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
                    Brush.linearGradient(
                        colors = listOf(visual.base, visual.accent, visual.base),
                        start = Offset.Zero,
                        // 落点跟着漂移走：一张完全静止的渐变在 OLED 上同样会留下底色。
                        end = Offset(1200f + driftX.value, 700f + driftY.value),
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
    EffectTier.HIGH -> BackgroundSpec(false, backdropAlpha = 0.55f, scrimAlpha = 0.30f, vignetteAlpha = 0.55f)
    EffectTier.MEDIUM -> BackgroundSpec(false, backdropAlpha = 0.38f, scrimAlpha = 0.36f, vignetteAlpha = 0.45f)
    EffectTier.LOW -> BackgroundSpec(false, backdropAlpha = 0.00f, scrimAlpha = 0.00f, vignetteAlpha = 0.28f)
    EffectTier.OFF -> BackgroundSpec(true, backdropAlpha = 0f, scrimAlpha = 0f, vignetteAlpha = 0f)
}

// ---------------------------------------------------------------------------------
// 声波圆环
// ---------------------------------------------------------------------------------

/** 圆环的分档参数。[rings] = 0 表示这一档不画。 */
data class WaveSpec(
    val rings: Int,
    val alpha: Float,
    val strokeDp: Float,
    val periodMs: Float,
    /**
     * 有没有外发光。
     *
     * 只有 HIGH 有：发光是 `Brush.radialGradient` 的一次额外跨屏填充，
     * 在没有真模糊的老设备上尤其贵 —— 它承担的就是"高档"这两个字。
     */
    val glow: Boolean,
)

fun waveSpec(tier: EffectTier): WaveSpec = when (tier) {
    EffectTier.HIGH -> WaveSpec(rings = 4, alpha = 0.30f, strokeDp = 2.5f, periodMs = 2_400f, glow = true)
    EffectTier.MEDIUM -> WaveSpec(rings = 3, alpha = 0.20f, strokeDp = 2.0f, periodMs = 3_200f, glow = false)
    EffectTier.LOW -> WaveSpec(rings = 2, alpha = 0.13f, strokeDp = 1.5f, periodMs = 4_200f, glow = false)
    EffectTier.OFF -> WaveSpec(rings = 0, alpha = 0f, strokeDp = 0f, periodMs = 0f, glow = false)
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
 * 左右对称的同心声波圆环。
 *
 * 圆心**在屏幕两侧边缘上**（`x = 0` 与 `x = W`，`y = H/2`），所以每一侧露出来的
 * 是半个圆 —— "包裹住屏幕两侧边缘"就是靠这个：圆的另一半点在屏幕外面。
 *
 * `phase` 与 `amplitude` 都在绘制期读取（见 [rememberEffectPhase] 与
 * [com.qingke.desk.feature.media.music.spectrum.TeeAudioProcessor.level]），
 * **不会触发重组**。`amplitude` = 1 是"没有频谱数据时的满幅观感"
 * （壳层没挂 QQ 自播源时就是这样），0 是静默。
 */
@Composable
fun WaveRings(
    spec: WaveSpec,
    maxRadius: Dp,
    accent: Color,
    phase: State<Float>,
    amplitude: State<Float>,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
) {
    if (spec.rings <= 0) return
    val density = LocalDensity.current
    val strokePx = with(density) { spec.strokeDp.dp.toPx() }
    val driftXPx = with(density) { driftX.toPx() }
    val driftYPx = with(density) { driftY.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val radiusMax = maxRadius.toPx()
        if (radiusMax <= 0f) return@Canvas
        val p = phase.value
        // 响度 → 半径行程与透明度。地板 0.3：完全静音时环缩到三成且近乎透明，
        // 与"暂停时相位归零、环缩没"是同向的两件事，不会互相抽梯子。
        val amp = amplitude.value.coerceIn(0f, 1f)
        val radiusScale = AMPLITUDE_RADIUS_FLOOR + (1f - AMPLITUDE_RADIUS_FLOOR) * amp

        val centers = listOf(
            Offset(driftXPx, size.height / 2f + driftYPx),
            Offset(size.width + driftXPx, size.height / 2f + driftYPx),
        )

        centers.forEach { center ->
            if (spec.glow) {
                // 发光只在最外圈附近，跟着最大半径走 —— 固定半径的话矮屏上会糊满整页。
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = spec.alpha * 0.55f), Color.Transparent),
                        center = center,
                        radius = radiusMax * 0.85f * radiusScale,
                    ),
                    radius = radiusMax * 0.85f * radiusScale,
                    center = center,
                )
            }

            for (i in 0 until spec.rings) {
                // 每个环错开一个 1/rings 的相位，于是"一圈圈往外扩"是连续不断的。
                val t = ((p + i.toFloat() / spec.rings) % 1f)
                val radius = radiusMax * radiusScale * t
                if (radius < 1f) continue
                // 越往外越淡：t 从 0 到 1，alpha 从满到 0。这样新环冒出、旧环散尽。
                // 再叠一层响度：安静时整组环一起暗下去。
                val ringAlpha = spec.alpha * (1f - t) * (1f - t) * amp
                drawCircle(
                    color = accent.copy(alpha = ringAlpha.coerceIn(0f, 1f)),
                    radius = radius,
                    center = center,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** 静音时圆环半径的地板比例（满幅 = 1）。见 [WaveRings] 的 `amplitude` 说明。 */
private const val AMPLITUDE_RADIUS_FLOOR = 0.3f

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
