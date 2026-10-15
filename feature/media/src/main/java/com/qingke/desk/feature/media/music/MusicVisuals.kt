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
import com.qingke.desk.feature.media.music.artwork.ArtworkBackdrop
import com.qingke.desk.feature.media.music.artwork.ArtworkVisual

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

/** 圆环的分档参数。[rings] = 0 表示这一档不画。 */
data class WaveSpec(
    val rings: Int,
    val alpha: Float,
    val strokeDp: Float,
    val periodMs: Float,
)

fun waveSpec(tier: EffectTier): WaveSpec = when (tier) {
    EffectTier.HIGH -> WaveSpec(rings = 4, alpha = 0.30f, strokeDp = 2.5f, periodMs = 2_400f)
    EffectTier.MEDIUM -> WaveSpec(rings = 3, alpha = 0.20f, strokeDp = 2.0f, periodMs = 3_200f)
    EffectTier.LOW -> WaveSpec(rings = 2, alpha = 0.13f, strokeDp = 1.5f, periodMs = 4_200f)
    EffectTier.OFF -> WaveSpec(rings = 0, alpha = 0f, strokeDp = 0f, periodMs = 0f)
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
 * 左右对称的同心声波圆环 —— **3D 凹凸光影波浪**（2026-10-15 对标参考视频重写）。
 *
 * 圆心**在屏幕两侧边缘上**（`x = 0` 与 `x = W`，`y = H/2`），所以每一侧露出来的
 * 是半个圆 —— "包裹住屏幕两侧边缘"就是靠这个：圆的另一半点在屏幕外面。
 *
 * ---------------------------------------------------------------------------
 * 为什么不是"细线圈"而是"波谷 + 波峰"成对画
 *
 * 参考视频的波纹有立体明暗起伏：每个扩散相位画**一对**环 —— 内侧一圈压暗的波谷
 * （黑），外侧一圈提亮的波峰（主色亮变体）。明暗交替让圆环从"线条"变成"有厚度的浪"。
 * 两侧还有各自的戏：
 *   · **左组**（深暗背景上）：波谷用黑 0.35，内圈再叠一根 1.2dp 的锐利亮环 ——
 *     视频左侧那道细亮圈；
 *   · **右组**（明亮背景上）：圆心一团柔和发光核，波浪换成更宽的光带
 *     （波谷压 0.25、波峰提 0.45）—— 亮侧的浪要更"胖"才看得见。
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
    val sharpPx = with(density) { SHARP_RING_DP.dp.toPx() }
    val driftXPx = with(density) { driftX.toPx() }
    val driftYPx = with(density) { driftY.toPx() }

    // 波峰色：主色提亮一档（明度 0.75 → 更亮但还没到刺眼）。
    // 波谷色就是黑 —— 立体感来自"亮环旁边必然有一圈暗影"，不是来自第二种彩色。
    val peak = ArtworkBackdrop.light(accent).copy(alpha = 0.9f)

    Canvas(modifier = modifier.fillMaxSize()) {
        val radiusMax = maxRadius.toPx()
        if (radiusMax <= 0f) return@Canvas
        val p = phase.value
        // 响度 → 半径行程与透明度。地板 0.3：完全静音时环缩到三成且近乎透明，
        // 与"暂停时相位归零、环缩没"是同向的两件事，不会互相抽梯子。
        val amp = amplitude.value.coerceIn(0f, 1f)
        val radiusScale = AMPLITUDE_RADIUS_FLOOR + (1f - AMPLITUDE_RADIUS_FLOOR) * amp

        // 左右两组。顺序即绘制顺序：先左后右，右侧的发光核最后压上去。
        val centers = listOf(
            Offset(driftXPx, size.height / 2f + driftYPx) to true,
            Offset(size.width + driftXPx, size.height / 2f + driftYPx) to false,
        )

        centers.forEach { (center, isLeft) ->
            // ---- 右组：圆心发光核 ----
            //
            // 只在右侧画：参考视频里亮侧的圆心是一团柔光，暗侧的圆心是纯暗。
            // 用 radialGradient 而不是 drawCircle 实心：边缘要化开，硬边会像贴了个饼。
            if (!isLeft) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            ArtworkBackdrop.light(accent).copy(alpha = 0.34f * amp),
                            Color.Transparent,
                        ),
                        center = center,
                        radius = radiusMax * GLOW_CORE_RATIO,
                    ),
                    radius = radiusMax * GLOW_CORE_RATIO,
                    center = center,
                )
            }

            // ---- 波浪本体：每个相位一对"波谷 + 波峰" ----
            for (i in 0 until spec.rings) {
                // 每个环错开一个 1/rings 的相位，于是"一圈圈往外扩"是连续不断的。
                val t = ((p + i.toFloat() / spec.rings) % 1f)
                val radius = radiusMax * radiusScale * t
                if (radius < 1f) continue
                // 越往外越淡：t 从 0 到 1，alpha 从满到 0。这样新环冒出、旧环散尽。
                // 再叠一层响度：安静时整组环一起暗下去。
                val fade = (1f - t) * (1f - t) * amp

                // 波谷（暗）：半径内侧一圈、笔画更宽 —— 立体感的来源。
                // 左侧用 0.35 的浓黑，右侧用 0.25 的浅黑（亮底上太浓会显脏）。
                val troughAlpha = (if (isLeft) TROUGH_ALPHA_LEFT else TROUGH_ALPHA_RIGHT) * fade
                drawCircle(
                    color = Color.Black.copy(alpha = troughAlpha.coerceIn(0f, 1f)),
                    radius = (radius - strokePx * TROUGH_INSET).coerceAtLeast(1f),
                    center = center,
                    style = Stroke(width = strokePx * TROUGH_WIDTH_SCALE, cap = StrokeCap.Round),
                )
                // 波峰（亮）：主色提亮环。右侧用更宽的光带（亮侧需要更胖的浪）。
                drawCircle(
                    color = peak.copy(alpha = (spec.alpha * fade).coerceIn(0f, 1f)),
                    radius = radius,
                    center = center,
                    style = Stroke(
                        width = if (isLeft) strokePx else strokePx * PEAK_WIDTH_SCALE,
                        cap = StrokeCap.Round,
                    ),
                )
            }

            // ---- 左组：内圈锐利细亮环 ----
            //
            // 视频左侧那道 1.2dp 的细亮圈。半径取最大半径的 22%（内圈但不缩成点），
            // 亮度跟随响度但保一个地板 —— 完全静音时它也该在，只是暗。
            if (isLeft) {
                drawCircle(
                    color = Color.White.copy(alpha = (0.42f * amp + 0.12f).coerceIn(0f, 1f)),
                    radius = radiusMax * radiusScale * SHARP_RING_RATIO,
                    center = center,
                    style = Stroke(width = sharpPx, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** 静音时圆环半径的地板比例（满幅 = 1）。见 [WaveRings] 的 `amplitude` 说明。 */
private const val AMPLITUDE_RADIUS_FLOOR = 0.3f

/** 波谷环相对波峰环的内缩量（笔画宽度的倍数）。 */
private const val TROUGH_INSET = 0.9f

/** 波谷环的笔画宽度倍数（比波峰宽才有"影"的体量）。 */
private const val TROUGH_WIDTH_SCALE = 1.8f

/** 右组波峰的笔画宽度倍数（亮侧的浪要更胖）。 */
private const val PEAK_WIDTH_SCALE = 2.4f

/** 左组波谷的不透明度（浓黑，参考视频的暗侧）。 */
private const val TROUGH_ALPHA_LEFT = 0.35f

/** 右组波谷的不透明度（浅黑，亮底上太浓会显脏）。 */
private const val TROUGH_ALPHA_RIGHT = 0.25f

/** 右组圆心发光核的半径比例（相对最大半径）。 */
private const val GLOW_CORE_RATIO = 0.28f

/** 左组锐利细亮环的笔画宽度。 */
private const val SHARP_RING_DP = 1.2f

/** 左组锐利细亮环的半径比例（相对最大半径）。 */
private const val SHARP_RING_RATIO = 0.22f

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
