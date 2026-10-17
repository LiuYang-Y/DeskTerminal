package com.qingke.desk.shell

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.LocalDeviceProfile
import com.qingke.desk.core.ui.SidebarFit
import com.qingke.desk.core.ui.fitSidebar
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 液态水滴侧栏 —— **纯自绘、零采样**的第三版（2026-10-18 推倒重写）。
 *
 * ---------------------------------------------------------------------------
 * 为什么又重写一次：前两版的三个死结
 *
 * 1. **地图页黑饼**：高德 `TextureMapView` 是独立图层，像素永远不进 Compose 的
 *    backdrop 录制层。只要水滴用 `drawBackdrop` 采样，地图页上它就只能在
 *    "一无所有"之上画一块底色 —— 上一版正是把那块 `#101217` 的不透明底画了出来。
 *    这不是参数没调好，是**采样这条路在地图页上根本不成立**。
 * 2. **双影重字**：旧版既有列内图标，又在镜片里用 `for` 循环画了一份 `LensGlyph`，
 *    靠 `covered(index)` 的透明度遮挡让它们"看起来只有一个"。尺寸、行高、透明度
 *    任何一项变化都会让两份错位，于是真机上出现重影。
 * 3. **API 割裂**：`RuntimeShader`（`lens()`）在 API 30 上静默失效，
 *    于是小米与一加上"同一个参数、两种画面"，调哪边都等于赌另一边。
 *
 * 本版的对策是把这三个死结**从结构上删掉**，而不是继续调参数：
 *   · **不采样任何 backdrop** —— 水滴内腔是真透明，底下是什么就透出什么
 *     （绿色地图、声波、壁纸都行），地图页自然不可能出现黑饼；
 *   · **只有一列图标** —— 不再有第二套副本，也就不存在"遮挡/漏出"这回事；
 *   · **纯 Canvas / DrawScope 绘制** —— 不碰 `RuntimeShader`、不碰 `RenderEffect`，
 *     API 30 与 API 36 的像素结果完全一致。
 *
 * ---------------------------------------------------------------------------
 * 四个模块（自上而下就是绘制顺序）
 *
 * ```
 * Box(railWidth × railHeight)          ← 不加 clip()，水滴才能鼓出底座
 *   ├─ (1) 底座        matchParentSize 的圆角胶囊 + 1dp 竖向渐变描边
 *   ├─ (2) 水滴        绝对定位在条目槽位上，横向/纵向形变由 press & velocity 驱动
 *   └─ (3) 图标列      唯一一列 shownItems；每项按"与水滴的距离"原位放大、染强调色
 * ```
 *
 * 水滴画在图标**之下**（不是之上）：图标永远压在水滴上面，永远清晰、永远不重影。
 * 这也是"内腔透明"能成立的前提 —— 水滴不需要复制任何东西，它只是一层光。
 *
 * ---------------------------------------------------------------------------
 * 动画量一律在**绘制期**读（`graphicsLayer` / `drawWithCache` 的 lambda 里），
 * 组合期只读一个布尔（"这一项要不要染强调色"），所以拖动不会引发逐帧重组。
 *
 * 「4 秒无操作暗淡到 0.22」不在这里：它由 `DeskRoot` 的 `sidebarAlpha` 统一套在
 * 侧栏与齿轮外层（见 `DeskRoot` 的"无操作自动暗淡沉浸"一节），本文件不重复实现。
 */

/**
 * 透镜的全部可调参数。数值来源两类，注释里逐条标明：
 * `[官方]` = Kyant0/AndroidLiquidGlass 的 `LiquidBottomTabs`（Apache-2.0）；
 * `[本项目]` = 为竖排侧栏或本工程的视觉语言调整。
 */
object LensSpec {

    /** 总开关。false = 只画静态底座与选中块，水滴不做任何形变。 */
    const val ENABLED: Boolean = true

    // ---- (1) 底座 ----------------------------------------------------------

    /** `[本项目]` 底座底色。深色通透磨砂，压得住白图标又不至于死黑。 */
    const val RAIL_ALPHA: Float = 0.45f

    /** `[本项目]` 地图漫游态：底下是浅色地图，0.45 压不住白图标，提到 0.62。 */
    const val RAIL_ALPHA_MAP: Float = 0.62f

    /** `[本项目]` 玻璃立体描边：1dp 竖向渐变（上亮 / 中暗 / 底回亮）。 */
    val railBorderWidth = 1.dp
    const val RAIL_BORDER_TOP = 0.28f
    const val RAIL_BORDER_MID = 0.08f
    const val RAIL_BORDER_BOTTOM = 0.18f

    // ---- (2) 水滴：内腔 ----------------------------------------------------

    /** `[本项目]` 静止态内腔：柔和半透明白，给图标一点"泡在玻璃里"的托底。 */
    const val DROP_IDLE_ALPHA: Float = 0.14f

    /** `[本项目]` 按压态内腔：近乎全透明，让底下的东西 100% 原色透出。 */
    const val DROP_PRESS_ALPHA: Float = 0.03f

    // ---- (2) 水滴：内缘厚度 ------------------------------------------------

    /**
     * `[官方]` 内阴影带宽度的竖向对应物（官方是 `InnerShadow(radius = 8dp * p)`，
     * 本工程不需要 `RuntimeShader`，改用多层描边叠出同样的"边缘聚光"）。
     */
    val dropRimWidth = 3.5.dp

    /** `[官方]` 内阴影外缘亮度（官方 `alpha = p`，静止时也留一点，否则水滴是块死板）。 */
    const val DROP_RIM_ALPHA: Float = 0.32f

    // ---- (2) 水滴：反射弧 --------------------------------------------------

    /** `[官方]` 高光描边宽度。 */
    val dropHighlightWidth = 1.4.dp

    /** `[官方]` 顶部镜面高光：静止 0.55，按压时更亮（0.55 + 0.30·p）。 */
    const val DROP_TOP_HIGHLIGHT = 0.55f
    const val DROP_TOP_HIGHLIGHT_PRESS = 0.30f

    /** `[本项目]` 底部环境反光（冰青），只在按压时出现 —— 模拟液体边缘的色散。 */
    const val DROP_BOTTOM_REFLECT = 0.25f

    // ---- (2) 水滴：果冻形变 ------------------------------------------------

    /** `[本项目]` 按压时横向鼓出比例。65dp 条目 → 79dp，比 77dp 的底座每边多 1dp。 */
    const val DROP_EXPAND_X = 0.22f

    /** `[本项目]` 按压时纵向轻微胀大。 */
    const val DROP_EXPAND_Y = 0.10f

    /** `[官方]` 速度越大、垂直于运动方向越收缩（官方 0.25，本工程给 0.08 更克制）。 */
    const val DROP_SQUASH_X = 0.08f

    /** `[官方]` 速度越大、沿运动方向越拉长（官方 0.75，本工程给 0.16）。 */
    const val DROP_STRETCH_Y = 0.16f

    /** `[本项目]` 速度归一化分母（格/秒）。超过这个速度形变就封顶。 */
    const val VELOCITY_NORM = 12f

    // ---- (3) 图标列 --------------------------------------------------------

    /** `[官方]` 选中/聚焦判定半径（单位：格）。距离超过它就算"没被水滴扫到"。 */
    const val FOCUS_RADIUS = 0.85f

    /** `[官方]` 聚焦时的原位放大比例（官方 0.12~0.15 的区间）。 */
    const val FOCUS_SCALE = 0.12f

    /** `[官方]` 按压期间聚焦项额外长一点，让"水滴压过来"这件事有反馈。 */
    const val FOCUS_PRESS_SCALE = 0.10f
}

/**
 * 玻璃效果的运行时档位。
 *
 * **本文件已经不再按档位分叉**（新架构不依赖任何 RuntimeShader / RenderEffect，
 * 两台机器的像素结果一致）。这个枚举现在**只服务于 debug 变体的能力读数页**
 * （`SidebarLensPreviewActivity`），保留是为了不破坏那个诊断入口。
 */
enum class LensTier(val label: String, val detail: String) {
    HIGHEST("最高档", "真折射 + 真色散 + 真模糊（RuntimeShader，API 33+）"),
    MEDIUM("中档", "真模糊，无折射（RenderEffect，API 31+）"),
    LOWEST("最低档", "无模糊无折射；静态半透明底（API < 31）"),
}

/** 当前设备的能力档位。**只给调试预览页用** —— 正式侧栏已不依赖档位。 */
fun resolveLensTier(): LensTier = when {
    Build.VERSION.SDK_INT >= 33 -> LensTier.HIGHEST
    Build.VERSION.SDK_INT >= 31 -> LensTier.MEDIUM
    else -> LensTier.LOWEST
}

/** 全局档位 → 诊断档位（保持调用点不变；正式视觉不再读它）。 */
fun EffectTier.toLensTier(): LensTier = when (this) {
    EffectTier.HIGH -> LensTier.HIGHEST
    EffectTier.MEDIUM -> LensTier.MEDIUM
    EffectTier.LOW, EffectTier.OFF -> LensTier.LOWEST
}

/**
 * 侧栏。
 *
 * @param backdrop 内容区的玻璃层。**参数保留只为调用点兼容，本文件一行都不读它** ——
 *        水滴不做任何采样，这正是地图页不再出现黑饼的原因。
 * @param lensEnabled 效果总开关。false → 水滴退化成一块静态指示块（不做形变）。
 * @param availableHeightDp 侧栏**真正**可用的纵向空间（dp）。调用方用 `BoxWithConstraints`
 *        量容器得到 —— 不读 `DeviceProfile.heightDp`（那是窗口上界，比可画范围大）。
 * @param darkUnderlay 地图漫游态：底座不透明度 0.45 → 0.62，压住浅色地图。
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun LensSideBar(
    items: List<ShellItem>,
    selectedId: String?,
    onSelect: (ShellItem) -> Unit,
    backdrop: LayerBackdrop?,
    lensEnabled: Boolean,
    modifier: Modifier = Modifier,
    availableHeightDp: Float? = null,
    darkUnderlay: Boolean = false,
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val profile = LocalDeviceProfile.current

    // ---- 条目尺寸按可用高度均分（2026-10-13 自适应框架，未改动）------------
    val railHeightDp = (availableHeightDp ?: profile.heightDp.toFloat()).coerceAtLeast(1f)
    val fit = remember(railHeightDp, items.size) {
        fitSidebar(availableHeightDp = railHeightDp, itemCount = items.size)
    }
    val shownItems = if (fit.fitting) items else items.take(fit.suggestedMaxItems)
    if (shownItems.isEmpty()) return

    val railWidth = fit.itemSize + DeskDimens.sideBarInnerPadding * 2
    // 容器高度 = 内容列高 + 上下内边距（必须与 Column 实际高度逐像素一致）。
    val railHeight = fit.itemSize * shownItems.size +
        fit.gap * (shownItems.size - 1) +
        DeskDimens.sideBarInnerPadding * 2

    val stepPx = with(density) { (fit.itemSize + fit.gap).toPx() }
    val innerPaddingPx = with(density) { DeskDimens.sideBarInnerPadding.toPx() }
    val lastIndex = shownItems.lastIndex

    val selectedIndex = shownItems.indexOfFirst { it.id == selectedId }
        .let { if (it < 0) 0 else it }

    val enabled = lensEnabled && LensSpec.ENABLED

    // ---- 三个驱动量：位置（格） / 按压（0..1） / 速度（格每秒）--------------
    //
    // 三者都是"绘制期读"的量。**不要**给这些 remember 加任何 key ——
    // 加了 key 之后 selectedId 一变就换一个新的 State 实例，而协程闭包捕获的是旧实例，
    // 于是"点按切屏了、水滴却不动"（2026-10-18 真机踩过这个坑）。
    val indicator = remember { Animatable(selectedIndex.toFloat()) }
    val press = remember { Animatable(0f) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var lastDragUptime by remember { mutableLongStateOf(0L) }

    val scope = rememberCoroutineScope()
    val onSelectNow by rememberUpdatedState(onSelect)
    val itemsNow by rememberUpdatedState(shownItems)

    // 已经"落定"的项（拖动结束立刻记账，避免重复回调）。不参与组合，只做去重。
    var committedIndex by remember { mutableIntStateOf(selectedIndex) }

    // 外部选中变化（点按条目 / 程序化切屏）→ 水滴滑过去。
    LaunchedEffect(selectedIndex) {
        committedIndex = selectedIndex
        if (abs(indicator.value - selectedIndex) > 0.001f) {
            indicator.animateTo(
                targetValue = selectedIndex.toFloat(),
                animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
            )
        }
    }

    // ---- 拖动收尾：吸附到最近一项 + 按压回落 + 速度衰减 ---------------------
    val settleDrag: () -> Unit = {
        val target = indicator.value.roundToInt().coerceIn(0, lastIndex)
        // 先回调（手感立刻跟上），再跑动画。父层 selectedId 随之更新，
        // 上面的 LaunchedEffect 也只是滑向同一个目标，不会打架。
        if (target != committedIndex) {
            committedIndex = target
            itemsNow.getOrNull(target)?.let(onSelectNow)
        }
        scope.launch { press.animateTo(0f, tween(180)) }
        scope.launch {
            animate(
                initialValue = velocity,
                targetValue = 0f,
                animationSpec = tween(260),
            ) { value, _ -> velocity = value }
        }
        scope.launch {
            indicator.animateTo(
                targetValue = target.toFloat(),
                animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
            )
        }
    }

    // ---- 手势：挂在整条 rail 上（不消费点按，子项 clickable 照常工作）-------
    val dragModifier = if (!enabled) {
        Modifier
    } else {
        Modifier.pointerInput(stepPx, lastIndex) {
            detectDragGestures(
                onDragStart = {
                    lastDragUptime = 0L
                    scope.launch { press.animateTo(1f, tween(120)) }
                },
                onDragCancel = { settleDrag() },
                onDragEnd = { settleDrag() },
            ) { change, dragAmount ->
                change.consume()
                val cellDelta = dragAmount.y / stepPx

                // 速度（格/秒）：用相邻两次指针事件的真实时间差，再指数平滑。
                // 直接拿"每帧位移"会随采样率抖动，果冻形变会一抽一抽的。
                val now = change.uptimeMillis
                if (lastDragUptime != 0L) {
                    val dtSec = (now - lastDragUptime).coerceAtLeast(8L) / 1000f
                    val instant = (cellDelta / dtSec).coerceIn(-40f, 40f)
                    velocity = velocity * 0.6f + instant * 0.4f
                }
                lastDragUptime = now

                val target = (indicator.value + cellDelta).coerceIn(0f, lastIndex.toFloat())
                scope.launch { indicator.snapTo(target) }
            }
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val idleTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)

    Box(
        modifier = modifier
            .width(railWidth)
            .height(railHeight)
            .then(dragModifier),
    ) {
        // ------------------------------------------------------------------
        // (1) 底座：圆角胶囊 + 竖向渐变描边。
        // 只有一层，且只在参数变化时重绘 —— 不参与任何动画。
        // ------------------------------------------------------------------
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    val corner = CornerRadius(DeskDimens.sideBarCorner.toPx())
                    val fill = if (darkUnderlay) LensSpec.RAIL_ALPHA_MAP else LensSpec.RAIL_ALPHA
                    drawRoundRect(
                        color = Color(0xFF12151E).copy(alpha = fill),
                        cornerRadius = corner,
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = LensSpec.RAIL_BORDER_TOP),
                            0.5f to Color.White.copy(alpha = LensSpec.RAIL_BORDER_MID),
                            1f to Color.White.copy(alpha = LensSpec.RAIL_BORDER_BOTTOM),
                        ),
                        cornerRadius = corner,
                        style = Stroke(width = LensSpec.railBorderWidth.toPx()),
                    )
                },
        )

        // ------------------------------------------------------------------
        // (2) 水滴滑块：真透明内腔 + 内缘厚度 + 上下反射弧 + 果冻形变。
        //
        // **画在图标之下**。它不复制任何内容，只是一层"光"：内腔 alpha 最低可到
        // 0.03，底下的地图/声波/壁纸原色透出来 —— 地图页因此不可能出现黑块。
        // ------------------------------------------------------------------
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = DeskDimens.sideBarInnerPadding)
                .size(width = fit.itemSize, height = fit.itemSize)
                .graphicsLayer {
                    translationY = innerPaddingPx + indicator.value * stepPx
                    val p = if (enabled) press.value else 0f
                    val v = if (enabled) {
                        (abs(velocity) / LensSpec.VELOCITY_NORM).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    // 横向鼓出底座（1.22 × 65dp ≈ 79dp，比 77dp 的底座每边多 1dp）；
                    // 纵向随速度拉长、横向随速度收窄 —— 果冻的"squash & stretch"。
                    scaleX = 1f + LensSpec.DROP_EXPAND_X * p - LensSpec.DROP_SQUASH_X * v
                    scaleY = 1f + LensSpec.DROP_EXPAND_Y * p + LensSpec.DROP_STRETCH_Y * v
                }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val radius = min(w, h) / 2f
                    val rim = LensSpec.dropRimWidth.toPx()
                    val stroke = LensSpec.dropHighlightWidth.toPx()

                    onDrawBehind {
                        val p = press.value
                        val idle = 1f - p

                        // 1. 内腔：静止 0.14 柔和托底 → 按压 0.03 近乎全透明。
                        val fill = if (enabled) {
                            LensSpec.DROP_PRESS_ALPHA +
                                (LensSpec.DROP_IDLE_ALPHA - LensSpec.DROP_PRESS_ALPHA) * idle
                        } else {
                            0.26f
                        }
                        drawRoundRect(
                            color = Color.White.copy(alpha = fill),
                            cornerRadius = CornerRadius(radius),
                        )

                        // 2. 内缘厚度：由外向内 6 圈描边，alpha 二次衰减 ——
                        //    近似官方的 InnerShadow（"边缘聚光、中心通透"），
                        //    但只用 DrawScope，API 30 与 API 36 完全一致。
                        val rings = 6
                        for (i in 0 until rings) {
                            val t = i / (rings - 1f)
                            val inset = rim * t
                            val alpha = LensSpec.DROP_RIM_ALPHA * (1f - t) * (1f - t)
                            if (alpha <= 0.002f) continue
                            drawRoundRect(
                                color = Color.White.copy(alpha = alpha),
                                topLeft = Offset(inset, inset),
                                size = Size(w - inset * 2f, h - inset * 2f),
                                cornerRadius = CornerRadius(max(0f, radius - inset)),
                                style = Stroke(width = rim / rings * 1.7f),
                            )
                        }

                        // 3. 顶部镜面高光弧：静止 0.55，按压更亮（0.55 + 0.30·p）。
                        val topAlpha = LensSpec.DROP_TOP_HIGHLIGHT +
                            LensSpec.DROP_TOP_HIGHLIGHT_PRESS * p
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = topAlpha),
                                0.30f to Color.White.copy(alpha = topAlpha * 0.35f),
                                0.46f to Color.Transparent,
                                startY = 0f,
                                endY = h,
                            ),
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(w - stroke, h - stroke),
                            cornerRadius = CornerRadius(max(0f, radius - stroke / 2f)),
                            style = Stroke(width = stroke),
                        )

                        // 4. 底部环境反光（冰青）：只有按压时出现，模拟液体边缘折光。
                        if (p > 0.01f) {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    0.60f to Color.Transparent,
                                    1f to Color(0xFFBFE9FF)
                                        .copy(alpha = LensSpec.DROP_BOTTOM_REFLECT * p),
                                    startY = 0f,
                                    endY = h,
                                ),
                                topLeft = Offset(stroke / 2f, stroke / 2f),
                                size = Size(w - stroke, h - stroke),
                                cornerRadius = CornerRadius(max(0f, radius - stroke / 2f)),
                                style = Stroke(width = stroke),
                            )
                        }
                    }
                },
        )

        // ------------------------------------------------------------------
        // (3) 图标列：**唯一的一列**。没有副本，也就没有"漏出双影"的可能。
        //
        // 每一项只做两件事：
        //   · 绘制期按"与水滴的距离"原位放大（水滴滑过谁，谁就凸起）；
        //   · 跨过 focus 门限时换强调色（组合期只订阅这一个布尔，不逐帧重组）。
        // ------------------------------------------------------------------
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(DeskDimens.sideBarInnerPadding),
            verticalArrangement = Arrangement.spacedBy(fit.gap),
        ) {
            shownItems.forEachIndexed { index, item ->
                RailItem(
                    item = item,
                    index = index,
                    fit = fit,
                    indicator = indicator,
                    press = press,
                    enabled = enabled,
                    tint = idleTint,
                    accent = accent,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

/**
 * 单个条目：图标 +（放得下时的）文字标签。
 *
 * 缩放与染色的分界线：**连续量（距离、按压力）在绘制期读**，组合期只读一个布尔
 * （"要不要染强调色"）。所以拖动全程零重组，只有"水滴跨过某项中点"的那一帧会重组一次。
 */
@Composable
private fun RailItem(
    item: ShellItem,
    index: Int,
    fit: SidebarFit,
    indicator: Animatable<Float, *>,
    press: Animatable<Float, *>,
    enabled: Boolean,
    tint: Color,
    accent: Color,
    onClick: () -> Unit,
) {
    // 门限取 FOCUS_RADIUS 的一半：水滴走到两项中间时，两项都不算被选中。
    val focused by remember(index, indicator) {
        derivedStateOf {
            enabled && abs(indicator.value - index) < LensSpec.FOCUS_RADIUS * 0.5f
        }
    }
    val color = if (focused) accent else tint

    Box(
        modifier = Modifier
            .size(fit.itemSize)
            .graphicsLayer {
                val dist = abs(indicator.value - index)
                val focus = (1f - dist / LensSpec.FOCUS_RADIUS).coerceIn(0f, 1f)
                val p = if (enabled) press.value else 0f
                val s = 1f + focus * (LensSpec.FOCUS_SCALE + LensSpec.FOCUS_PRESS_SCALE * p)
                scaleX = s
                scaleY = s
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = color,
                modifier = Modifier.size(fit.iconSize),
            )
            // 放不下文字标签时（ICON_ONLY）整段不画 —— 半截标签比没有标签更难看。
            if (fit.showLabel) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = item.label,
                    fontSize = fit.labelSize,
                    color = color,
                    maxLines = 1,
                )
            }
        }
    }
}
