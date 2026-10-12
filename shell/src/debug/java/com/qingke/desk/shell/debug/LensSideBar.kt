package com.qingke.desk.shell.debug

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.qingke.desk.core.data.SidebarSide
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskGlass
import com.qingke.desk.core.ui.deskGlassSurface
import com.qingke.desk.core.ui.indicatorAlpha
import com.qingke.desk.shell.ShellItem
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** 列内淡出的过渡带宽（单位：格）。越小越"干脆"，越大越柔和。 */
private const val LENS_FADE_SPAN: Float = 0.30f

/**
 * 玻璃透镜侧栏 —— **原型，只在 debug 变体**（正式侧栏 [com.qingke.desk.shell.SideBar] 不动）。
 *
 * 需求（用户口径）：镜片比单个图标略大、镜内图标被放大且染成强调色、带折射与彩色边缘、
 * 按下/拖动时镜片再放大一点、拖动连续跟手、抬手回弹吸附最近一项；
 * 侧栏本体是深色半透明胶囊 + 细描边 + 高光。
 *
 * ---------------------------------------------------------------------------
 * 为什么镜内图标是"重画一份"而不是"采样侧栏的像素"
 *
 * 官方 Interactive Glass Bottom Bar 用 `drawBackdrop(..., exportedBackdrop = rail)`
 * 把底栏自身录成一块层，镜片再采样它 —— 那样镜片里是**被弯折的真实像素**，
 * 拖动时还能看到邻近项被折射进来。本项目**不这么做**，两条理由：
 *
 *   1. 真折射要 API 33（`isRuntimeShaderSupported()`，证据见
 *      docs/2026-10-13-sidebar-glass-lens-plan.md），低版本上 `lens()` 第一行就 return，
 *      采样了也看不出任何形变；
 *   2. exportedBackdrop 引入"玻璃采样玻璃"的成环风险（RenderThread 自采样 → SIGSEGV）。
 *      用同一份数据**重画一遍**替代像素副本，从根上不可能成环 ——
 *      这也是本文件不出现 exportedBackdrop 参数的原因。
 *
 * 镜内画的是"当前项 + 邻近项"（按 `i - pos` 定位），所以拖动时镜片里是**内容滚过**，
 * 与真透镜"镜内看到邻近像素"的观感接近，代价是没有色散与边缘畸变。
 *
 * ---------------------------------------------------------------------------
 * 自适应分档（用户口径：33 走最高特效，低版本降级）
 *
 * | 档位 | 判定（照库的门槛抄，不自己发明） | 镜片玻璃 | 彩色边缘 |
 * |---|---|---|---|
 * | [LensTier.HIGHEST] | `SDK_INT >= 33`（RuntimeShader） | 真折射 + 真模糊 + **真色散** | 由 shader 给出 |
 * | [LensTier.MEDIUM] | `SDK_INT >= 31`（RenderEffect） | 真模糊，无折射 | 渐变描边近似 |
 * | [LensTier.LOWEST] | 其余 | 半透明底 + 高光，无模糊无折射 | 渐变描边近似 |
 *
 * 三档**都能看到**"镜内放大 + 染色 + 镜片比项大"，因为这些是自绘的，不依赖 RuntimeShader。
 * 分档只决定"折射/色散/模糊"这层增强有没有。
 *
 * ---------------------------------------------------------------------------
 * 竖向布局的溢出：**不越界**
 *
 * 侧栏宽 76 = 项 64 + 内边距 6×2。镜片取 72（比项大 8），居中于项之后
 * 左右各占 2dp 余量 —— 仍在 76dp 的侧栏内，永远不会压到内容区。
 * 想要更大的鼓出（≥84dp）才需要讨论"朝外被窗口裁 / 朝内压内容"的取舍。
 */
object LensSpec {

    /** 总开关。false = 回到改动前的样式（同一套代码，只是不放大、不染色、镜片回到 64dp）。 */
    const val ENABLED: Boolean = true

    /** 镜片边长。项 64 + 每边 4 = 72 ≤ 侧栏 76（见文件头"竖向布局的溢出"）。 */
    val lensSize = 72.dp

    /** 镜片圆角。取 26dp 而不是 lensSize/2：留一点直边，更像"镜片"而不是"圆点"。 */
    val lensCorner = 26.dp

    /** 按下/拖动时镜片整体再放大的倍率增量（1.0 → 1.12）。 */
    const val PRESS_SCALE_EXTRA: Float = 0.12f

    /** 镜内内容的放大倍率：松手 / 按下。放大原点在镜片中心。 */
    const val GLYPH_BOOST_IDLE: Float = 1.25f
    const val GLYPH_BOOST_PRESSED: Float = 1.45f

    /** 镜片玻璃的白色蒙版不透明度。比指示块（0.26）低一点，它要"透"也要"亮"。 */
    const val LENS_SURFACE_ALPHA: Float = 0.20f

    /** 彩色边缘的描边宽度（LOWEST/MEDIUM 档用它近似色散）。 */
    val chromaStroke = 1.4.dp

    /** 镜片内缘的一圈柔光，模拟透镜聚光（纯装饰，不参与降级判定）。 */
    const val innerGlowAlpha: Float = 0.10f

    /** HIGHEST/MEDIUM 档的背景模糊半径。 */
    val blurRadius = 8.dp

    /** 折射的"高度"与强度（HIGHEST 档才真的生效）。 */
    val refractionHeight = 14.dp
    val refractionAmount = 26.dp

    /** 镜内额外渲染的邻近项范围（单位：格）。1.2 足够覆盖镜片视野加一点余量。 */
    const val NEIGHBOR_SPAN: Float = 1.2f
}

/**
 * 玻璃效果的运行时档位。门槛照 `backdrop` 2.0.0 的内部判定抄
 * （`PlatformKt.isRenderEffectSupported()` = SDK_INT >= 31、
 *   `isRuntimeShaderSupported()` = SDK_INT >= 33，来自对 aar 的反编译）。
 */
enum class LensTier(val label: String, val detail: String) {
    HIGHEST("最高档", "真折射 + 真色散 + 真模糊（RuntimeShader，API 33+）"),
    MEDIUM("中档", "真模糊，无折射；彩色边缘用渐变描边近似（RenderEffect，API 31+）"),
    LOWEST("最低档", "无模糊无折射；放大/染色/描边全部自绘（API < 31）"),
}

/** 当前设备的档位。 */
fun resolveLensTier(): LensTier = when {
    Build.VERSION.SDK_INT >= 33 -> LensTier.HIGHEST
    Build.VERSION.SDK_INT >= 31 -> LensTier.MEDIUM
    else -> LensTier.LOWEST
}

/**
 * 透镜侧栏原型。
 *
 * @param side 侧栏在屏幕哪一侧。只影响镜片的横向偏移符号（左右镜像的唯一差异）。
 * @param backdrop 内容区那块 LayerBackdrop（与正式侧栏共用同一个源）。
 * @param lensEnabled 透镜效果开关。false 时视觉回到改动前。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LensSideBar(
    items: List<ShellItem>,
    selectedId: String?,
    onSelect: (ShellItem) -> Unit,
    side: SidebarSide,
    backdrop: LayerBackdrop?,
    lensEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val stepPx = with(density) {
        (DeskDimens.sideBarItemSize + DeskDimens.sideBarItemGap).toPx()
    }
    val itemSizePx = with(density) { DeskDimens.sideBarItemSize.toPx() }
    val innerPaddingPx = with(density) { DeskDimens.sideBarInnerPadding.toPx() }
    val lastIndex = items.lastIndex

    val selectedIndex = items.indexOfFirst { it.id == selectedId }.let { if (it < 0) 0 else it }

    // 位置单位是"第几项"的浮点值，与正式侧栏一致：条目尺寸变了也不用重算动画。
    val indicator = remember { Animatable(0f) }
    val indicatorState = remember(indicator) { indicator.asState() }
    // 按下/拖动的强度 0..1。镜片缩放与镜内放大量都由它插值 —— 一条弹簧驱动全部形变。
    val press = remember { Animatable(0f) }
    val pressState = remember(press) { press.asState() }
    val scope = rememberCoroutineScope()

    var dragging by remember { mutableStateOf(false) }
    val draggingNow by rememberUpdatedState(dragging)
    val selectedIndexNow by rememberUpdatedState(selectedIndex)

    // 与正式侧栏同一套拖拽基准：绝对差（不累加）、floor 取按下的那一格。
    val dragOrigin = remember { mutableFloatStateOf(0f) }
    val dragDownY = remember { mutableFloatStateOf(0f) }
    val dragRaw = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(selectedIndex) {
        if (!draggingNow) {
            indicator.animateTo(
                targetValue = selectedIndex.toFloat(),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
            )
        }
    }

    // 抬手回弹：press 归零，镜片缩回 1.0。与位置吸附是两条独立的弹簧。
    LaunchedEffect(dragging) {
        press.animateTo(
            targetValue = if (dragging) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    val active = lensEnabled && LensSpec.ENABLED
    val tier = remember { resolveLensTier() }

    // 镜片尺寸：开启时 72dp（比项大 8），关闭时回到 64dp（= 改动前的指示块）。
    val lensSize = if (active) LensSpec.lensSize else DeskDimens.sideBarItemSize
    val lensOverhangPx = with(density) {
        ((lensSize - DeskDimens.sideBarItemSize) / 2).toPx()
    }
    val lensSizePx = with(density) { lensSize.toPx() }
    val lensCorner = if (active) LensSpec.lensCorner else DeskDimens.sideBarIndicatorCorner

    val pressScale = 1f + (if (active) LensSpec.PRESS_SCALE_EXTRA * pressState.value else 0f)
    val glyphBoost = LensSpec.GLYPH_BOOST_IDLE +
        (LensSpec.GLYPH_BOOST_PRESSED - LensSpec.GLYPH_BOOST_IDLE) * pressState.value

    // 镜片横向偏移。左右两栏的项都居中于侧栏内容区，所以偏移量相同 ——
    // "镜像"体现在整条侧栏贴着屏幕的另一边，纵向行为（拖拽轴）与左右无关。
    val lensDxPx = -lensOverhangPx

    Box(
        modifier = modifier
            .width(DeskDimens.sideBarWidth)
            .deskGlassSurface(
                backdrop = backdrop,
                shape = { RoundedCornerShape(DeskDimens.sideBarCorner) },
            )
            .border(
                width = 1.dp,
                brush = railStrokeBrush,
                shape = RoundedCornerShape(DeskDimens.sideBarCorner),
            )
            .padding(DeskDimens.sideBarInnerPadding)
            .pointerInput(items, stepPx) {
                detectVerticalDragGestures(
                    onDragStart = { down ->
                        dragging = true
                        val base = floor((down.y - innerPaddingPx) / stepPx)
                            .coerceIn(0f, lastIndex.toFloat())
                        dragOrigin.value = base
                        dragDownY.value = down.y
                        dragRaw.value = base
                        scope.launch { indicator.snapTo(base) }
                    },
                    onDragCancel = {
                        dragging = false
                        scope.launch { indicator.animateTo(selectedIndexNow.toFloat()) }
                    },
                    onDragEnd = {
                        dragging = false
                        val target = dragRaw.value.roundToInt().coerceIn(0, lastIndex)
                        onSelect(items[target])
                        scope.launch { indicator.animateTo(target.toFloat()) }
                    },
                    onVerticalDrag = { change, _ ->
                        dragRaw.value = (
                            dragOrigin.value +
                                (change.position.y - dragDownY.value) / stepPx
                            ).coerceIn(0f, lastIndex.toFloat())
                        scope.launch { indicator.snapTo(dragRaw.value) }
                    },
                )
            },
    ) {
        // 镜片画在条目**下层**：它是"选中项后面的一块透镜"，镜内副本由它自己带。
        //
        // 纵向要减掉 overhang：镜片比项高 8dp，不减会让它整体偏下 4dp，
        // 镜内副本就会与它"罩住"的那一项错开半个身位。
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = lensDxPx.roundToInt(),
                        y = (indicatorState.value * stepPx - lensOverhangPx).roundToInt(),
                    )
                }
                .size(lensSize)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                    transformOrigin = TransformOrigin.Center
                }
                .lensGlass(
                    backdrop = backdrop,
                    shape = RoundedCornerShape(lensCorner),
                    tier = tier,
                    active = active,
                )
                .border(
                    width = if (active) LensSpec.chromaStroke else 0.dp,
                    brush = if (active) chromaBrush else invisibleBrush,
                    shape = RoundedCornerShape(lensCorner),
                )
                .clip(RoundedCornerShape(lensCorner)),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                // 镜内柔光：中心一点白，边缘收住，像玻璃弧面的聚光。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = LensSpec.innerGlowAlpha),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )

                // **镜内不是"一项"，而是"当前项 + 邻近项"的放大副本**。
                //
                // 每一项用**绝对定位**放在"它相对镜片中心的位置"上：
                //   centerY_i = lensSize/2 + (i - pos) * step
                // 所以拖动时这些副本会整体滚过镜片，某一项进入中心就被放大 ——
                // 而不是"一格一跳地换图标"。
                //
                // 为什么不用"整列 + 一个大 offset"：那样 Column 的布局高度仍是镜片高，
                // 被 offset 推出去的内容不在 layer 边界内，实测整块不渲染（镜片里空的）。
                // 改成一格一格绝对定位后，每项的位移都只有一两格，稳。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = glyphBoost
                            scaleY = glyphBoost
                            transformOrigin = TransformOrigin.Center
                        },
                ) {
                    val center = indicatorState.value
                    val from = ceil(center - LensSpec.NEIGHBOR_SPAN).toInt().coerceAtLeast(0)
                    val to = floor(center + LensSpec.NEIGHBOR_SPAN).toInt().coerceAtMost(lastIndex)
                    for (i in from..to) {
                        key(i) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .offset {
                                        IntOffset(
                                            x = 0,
                                            y = (
                                                lensSizePx / 2f +
                                                    (i - center) * stepPx -
                                                    itemSizePx / 2f
                                                ).roundToInt(),
                                        )
                                    },
                            ) {
                                LensGlyph(item = items[i], tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(DeskDimens.sideBarItemGap),
        ) {
            items.forEachIndexed { index, item ->
                val selected = item.id == selectedId
                // 开启透镜时，镜外一律原色（染色只发生在镜内）；
                // 关闭时恢复改动前的规则：选中项在列内染强调色。
                val tint = if (!active && selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                // **被镜片视野罩住的那些项，在列内淡出**：
                // 镜片里已经有它们的放大副本，列内原样画就会叠成"双影"。
                // 范围取镜片半高折成的格数 + 半个淡出带宽，且是**连续**的 ——
                // 拖动时是渐变而不是整格突变。
                val halfSpan = (lensSizePx / 2f) / stepPx
                val covered = if (active) {
                    ((halfSpan + 0.05f - abs(indicatorState.value - index)) / LENS_FADE_SPAN)
                        .coerceIn(0f, 1f)
                } else {
                    0f
                }
                LensRailItem(
                    item = item,
                    tint = tint,
                    alpha = 1f - covered,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

/**
 * 镜片玻璃。**按运行时档位选实现**：
 *
 * - [LensTier.HIGHEST]：自己调库的 `drawBackdrop`，把 `chromaticAberration`/`depthEffect`
 *   两个开关打开 —— `core:ui` 的 [deskGlassSurface] 是统一封装，没开这两个开关，
 *   而"彩色边缘"正是靠色散给的。注意这两个 boolean **只能按位置传**：
 *   jar 里没有形参名（LocalVariableTable 为空），具名参数编译不过。
 * - 其余档位：退回 [deskGlassSurface]，低版本上库内部会静默跳过 blur/lens，
 *   剩下的半透明底 + 高光就是"最低档"的玻璃。
 *
 * `active=false`（总开关关掉）时只画与改动前一致的指示块玻璃。
 */
@Composable
private fun Modifier.lensGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    tier: LensTier,
    active: Boolean,
): Modifier {
    if (backdrop == null) return this
    if (!active) {
        return this.deskGlassSurface(
            backdrop = backdrop,
            shape = { shape },
            alpha = DeskGlass.indicatorAlpha,
        )
    }
    if (tier != LensTier.HIGHEST) {
        return this.deskGlassSurface(
            backdrop = backdrop,
            shape = { shape },
            alpha = LensSpec.LENS_SURFACE_ALPHA,
        )
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            blur(LensSpec.blurRadius.toPx())
            // 位置参数：lens(height, amount, chromaticAberration, depthEffect)
            lens(
                LensSpec.refractionHeight.toPx(),
                LensSpec.refractionAmount.toPx(),
                true,
                true,
            )
        },
        highlight = { Highlight.Default },
        onDrawSurface = { drawRect(Color.White.copy(alpha = LensSpec.LENS_SURFACE_ALPHA)) },
    )
}

/**
 * 镜内的图标 + 文字副本：与列内条目布局完全相同，只是被染色、且由外层的
 * graphicsLayer 统一放大（放大原点在镜片中心，所以"越靠中心越大"是天然出来的）。
 */
@Composable
private fun LensGlyph(
    item: ShellItem,
    tint: Color,
) {
    Box(
        modifier = Modifier.size(DeskDimens.sideBarItemSize),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(DeskDimens.sideBarIconSize),
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = item.label,
                fontSize = DeskDimens.sideBarLabel,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/** 列内条目：与正式侧栏的 [com.qingke.desk.shell] 版本同款（无涟漪、单击即切屏）。 */
@Composable
private fun LensRailItem(
    item: ShellItem,
    tint: Color,
    alpha: Float,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(DeskDimens.sideBarItemSize)
            .graphicsLayer { this.alpha = alpha }
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
                tint = tint,
                modifier = Modifier.size(DeskDimens.sideBarIconSize),
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = item.label,
                fontSize = DeskDimens.sideBarLabel,
                color = tint,
                maxLines = 1,
            )
        }
    }
}

/**
 * 彩色边缘。HIGHEST 档由真色散给出（shader 里的 `chromaticAberration`）；
 * 低版本用**对角三色渐变描边**近似同一观感：品红 → 蓝紫 → 青。
 */
private val chromaBrush: Brush
    get() = Brush.linearGradient(
        colors = listOf(
            Color(0xFFFF4FA3),
            Color(0xFF7B6CFF),
            Color(0xFF3FE0D0),
        ),
    )

/** 关掉透镜时用来"抹掉"描边的透明画刷（保持 modifier 链形状一致，避免分支里形状不同）。 */
private val invisibleBrush: Brush
    get() = Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))

/** 侧栏胶囊的细描边：上亮下暗，配合高光做出"边缘有一层薄玻璃"的意思。 */
private val railStrokeBrush: Brush
    get() = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.22f),
            Color.White.copy(alpha = 0.05f),
        ),
    )
