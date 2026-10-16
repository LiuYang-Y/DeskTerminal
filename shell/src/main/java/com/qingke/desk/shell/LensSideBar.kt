package com.qingke.desk.shell

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
import androidx.compose.foundation.layout.requiredSize
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskGlass
import com.qingke.desk.core.ui.LocalDeviceProfile
import com.qingke.desk.core.ui.LocalEffectTier
import com.qingke.desk.core.ui.SidebarFit
import com.qingke.desk.core.ui.SidebarFitLevel
import com.qingke.desk.core.ui.deskGlassOverlay
import com.qingke.desk.core.ui.deskGlassSurface
import com.qingke.desk.core.ui.fitSidebar
import com.qingke.desk.core.ui.indicatorAlpha
import com.qingke.desk.shell.ShellItem
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** 列内淡出的过渡带宽（单位：格）。越小越"干脆"，越大越柔和。 */
private const val LENS_FADE_SPAN: Float = 0.30f

/**
 * 地图漫游态侧栏的深色底衬不透明度。
 *
 * 比侧栏白蒙版（DeskGlass.surfaceAlpha = 0.12）高很多、比地图浮层（overlayAlpha = 0.72）
 * 低 —— 侧栏里是图标不是小字号数字，0.55 足以压住浅色地图上的白图标对比度，
 * 又不会让侧栏退化成一块纯黑板、失去"浮在地图上"的玻璃轮廓。
 */
private const val SIDEBAR_DARK_UNDERLAY_ALPHA = 0.55f

/**
 * 玻璃透镜侧栏 —— 侧栏 [SideBar] 的实现本体。
 *
 * 需求（用户口径）：**静止时是内嵌在侧栏里的一颗胶囊，按下/拖动时鼓成一颗清澈水滴** ——
 * 水滴里能看到被光学弯折的**侧栏本体**（含左右边框线穿进去弯折），镜内图标被放大且染成强调色，
 * 高档带真色散；抬手回弹吸附最近一项。
 *
 * ---------------------------------------------------------------------------
 * 结构：**侧栏本体导出 railBackdrop + 水滴透镜作平级兄弟**（2026-10-15 重构）
 *
 * ```
 * Box(手势 + 侧栏胶囊外形, width = itemSize + innerPadding*2)
 *   ├─ (a) 侧栏本体：玻璃覆盖整条 rail，导出成 railBackdrop
 *   │        Modifier.deskGlassSurface(backdrop = 内容区backdrop,
 *   │                                 exportedBackdrop = railBackdrop,
 *   │                                 onDrawFront = { 画 rail 那条 1dp 细描边 })
 *   │        └─ Column(内边距 + 各项)
 *   └─ (b) 水滴透镜：平级兄弟，后绘制；只采 railBackdrop，自身 exportedBackdrop = null
 *            Modifier.graphicsLayer { 按压放大 }
 *                    .drawBackdrop(backdrop = railBackdrop, ...)
 * ```
 *
 * **严格单向 DAG**（这是本结构唯一的硬约束）：
 *   内容区 backdrop ──(a 读)──> railBackdrop ──(b 读)──> 水滴
 * (a) 只读"内容区 backdrop"、导出成 railBackdrop；(b) 只读 railBackdrop、**不再导出**。
 * 没有任何一环"读同一块又写同一块"，所以不会出现官方法警告的 RenderThread 自采样 SIGSEGV。
 * 上一版之所以完全不用 `exportedBackdrop`（只共用同一个源），是把成环风险一刀切掉了；
 * 现在按需求把它收窄成上面这条单向链。
 *
 * ---------------------------------------------------------------------------
 * 为什么描边必须画在 `onDrawFront` 里（而不是 `Modifier.border`）
 *
 * 库的导出（`DrawBackdropModifier.kt:316-347`）录的是
 * `onDrawBehind + drawBackdropLayer + onDrawSurface + onDrawFront` —— **没有 `drawContent()`**。
 * 也就是说：
 *   · `Modifier.border()` 画的那条线**不进导出层**，水滴里就看不到它被弯折；
 *   · 只有画进 `onDrawFront`（或 `onDrawSurface`）的描边才会进 railBackdrop、被水滴采样。
 * 所以 rail 的那条 1dp 细描边改由 [railStrokeOnFront] 画在 `onDrawFront` 里。
 *
 * ---------------------------------------------------------------------------
 * 镜内为什么**不再自绘图标**（2026-10-17 按真机录屏 + Kyant 原版口径推翻旧结论）
 *
 * 旧结论说"导出里没有子内容，镜内必须自绘一份 [LensGlyph]" —— **真机录屏证伪了它**：
 * 拖动时出现上下两个"音乐"叠在一起的重影，正是"railBackdrop 折射出的原字" +
 * "自绘的放大副本"两套同时在画。HIGHEST 档（RuntimeShader 可用）且 railBackdrop
 * 已导出时：**水滴内部完全不渲染 LensGlyph**，图标放大与边缘拉伸 100% 交给
 * `lens(depthEffect = true)` 对 railBackdrop 像素做光学扭曲。
 * 相应地，列内条目**保持正常绘制**（不再做 covered 淡出）—— 否则 railBackdrop
 * 里没东西可折射，水滴就是空的。
 * MEDIUM/LOWEST 档没有折射，仍走自绘路径（见 [LensGlyph]），否则镜里什么都没有。
 *
 * ---------------------------------------------------------------------------
 * 按压形变：静止内嵌 → 按下鼓出（各向异性，2026-10-17）
 *
 * | 状态 | 宽 | 高 | 白底 alpha |
 * |---|---|---|---|
 * | 静止 `press = 0` | `itemSize`（64dp，内嵌低调圆角块） | 同宽 | 0.14 |
 * | 按下 `press = 1` | 侧栏总宽 × 1.22 ≈ 92.7dp（**每边越框约 8.3dp**） | `itemSize × 1.15` | 0 |
 *
 * 宽高两条独立插值（横向越框是需求核心：左右边框线要穿进水滴被弯折）；
 * 白底随 press 线性清零，按住的水滴全透明。外层不做任何 clip 拦截。
 *
 * ---------------------------------------------------------------------------
 * 每帧重组会崩（2026-10-13，真机 SIGSEGV）
 *
 * 现象：手指一拖侧栏就闪退。tombstone 里三次崩溃都在 `hwuiTask` 线程、
 * 崩在 `SurfaceComposerClient::Transaction`（`setFlags` / `apply`），
 * 调用链是 `RenderNode$CompositePositionUpdateListener.positionChanged
 *   -> SurfaceControl$Transaction.show/apply`。
 *
 * 根因：**在组合期读了动画状态**。镜内内容原本按"当前位置 ± 1.2 格"取一个窗口，
 * 列内淡出量也按当前位置算 —— 两个都在组合期读 `indicatorState.value`，
 * 于是拖动时每一帧整棵玻璃子树重组一次、图形层每帧重建一次。
 *
 * 修法（两条都用上，缺一条都不放心）：
 *   1. 所有随动画变化的量只许在 `offset {}` / `graphicsLayer {}` 这类**布局或绘制
 *      阶段**的 lambda 里读，组合期一律不读。镜内因此改成"全部项都画"，
 *      窗口不再由位置决定 —— 组合结果与位置无关。水滴的白底 alpha 也只在
 *      `onDrawSurface` 的绘制期 lambda 里读 `pressAmount()` 算。
 *   2. 低版本（LOWEST）不再给水滴单独开一层 backdrop：那层在上面本来就没效果，
 *      却要多一块 SurfaceControl。用一块随按下变淡的静态白玻璃代替（见 [waterDropGlass]）。
 *
 * 判据：拖 10 个来回 + 反复切屏，`/data/tombstones` 数量不变、进程 pid 不变。
 */
object LensSpec {

    /** 总开关。false = 回到改动前的样式（同一套代码，只是不放大、不染色、水滴回到条目尺寸）。 */
    const val ENABLED: Boolean = true

    /** 水滴圆角。取 26dp 而不是 itemSize/2：留一点直边，更像"水滴/镜片"而不是"圆点"。 */
    val lensCorner = 26.dp

    /**
     * 按下时水滴**宽度** = 侧栏总宽（itemSize + 内边距×2 ≈ 76dp）× 此倍率（≈ 92.7dp）。
     * 1.22 意味着水滴每边越过侧栏边框线（半宽 38dp）约 8.3dp —— 左右边框线整段落进
     * 水滴内、被透镜真折射弯折。横向放大是**各向异性**的（纵向只多 15%），见
     * [LensSideBar] 水滴 graphicsLayer 里两条插值 —— 这也是为什么不能再一条 scale 走天下。
     */
    const val PRESS_WIDTH_OVERSIDE: Float = 1.22f

    /** 按下时水滴**高度** = itemSize × (1 + 此值)。纵向只是"鼓"，横向才是"越框"。 */
    const val PRESS_HEIGHT_EXTRA: Float = 0.15f

    /**
     * 水滴玻璃白色蒙版的基础不透明度（静止态）。
     *
     * 2026-10-17 按 Kyant 原版口径从 0.06 提到 **0.14**：静止时它是一块"内嵌在侧栏里的
     * 低调圆角块"（比选中块 0.26 更淡），按下后按 `×(1-press)` 线性清零 ——
     * 按住的水滴必须**全透明**，底下被折射的边框与图标才看得见（毛玻璃水滴是错的，
     * 参考视频里"模糊拉到最左"就是这个意思）。
     */
    const val LENS_SURFACE_ALPHA: Float = 0.14f

    /**
     * 按压折射参数（HIGHEST 档）：`base + press × delta`。
     * 参考口径 = Kyant 原版示例（16/28dp），按住后加码让弯折与色散更强。
     */
    val refractionHeightBase = 16.dp
    val refractionHeightPress = 10.dp
    val refractionAmountBase = 28.dp
    val refractionAmountPress = 20.dp

    /** 镜内内容的放大倍率：松手 / 按下。放大原点在水滴中心。**只在非 HIGHEST 档的自绘路径用**。 */
    const val GLYPH_BOOST_IDLE: Float = 1.25f
    const val GLYPH_BOOST_PRESSED: Float = 1.45f

    /** 水滴外缘描边宽度（真色散不可用的档位用它近似"镜片边缘"）。 */
    val rimStroke = 1.dp

    /** 水滴内缘的一圈柔光，模拟透镜聚光（纯装饰，不参与降级判定）。**只在非 HIGHEST 自绘路径用**。 */
    const val innerGlowAlpha: Float = 0.10f

    /**
     * **水滴的模糊半径 = 0（2026-10-17）** —— 水滴是清澈凸透镜，不是毛玻璃圆饼。
     * MEDIUM 档仍要一点模糊（它没有折射，blur 是唯一的深度线索），用这个值；
     * HIGHEST 档的 effects 里**不再出现 blur**。
     */
    val dropBlurRadiusMedium = 8.dp

    /** 侧栏本体（Rail）的轻模糊半径：胶囊要能透出内容区，但不能糊成雾。 */
    val railBlurRadius = 4.dp

    /** 侧栏本体的深色半透明底（2026-10-17 按参考视频口径：深底 + 亮边，突出被折射的内容）。 */
    const val RAIL_SURFACE_ALPHA: Float = 0.28f
}

/**
 * 玻璃效果的运行时档位。门槛照 `backdrop` 2.0.0 的内部判定抄
 * （`PlatformKt.isRenderEffectSupported()` = SDK_INT >= 31、
 *   `isRuntimeShaderSupported()` = SDK_INT >= 33，来自对 aar 的反编译）。
 */
enum class LensTier(val label: String, val detail: String) {
    HIGHEST("最高档", "真折射 + 真色散 + 真模糊（RuntimeShader，API 33+）"),
    MEDIUM("中档", "真模糊，无折射；水滴外缘用渐变描边近似（RenderEffect，API 31+）"),
    LOWEST("最低档", "无模糊无折射；放大/染色/描边全部自绘（API < 31）"),
}

/** 当前设备的档位。**只给调试预览页用** —— 正式链路由 [EffectTier] 下发，见 [toLensTier]。 */
fun resolveLensTier(): LensTier = when {
    Build.VERSION.SDK_INT >= 33 -> LensTier.HIGHEST
    Build.VERSION.SDK_INT >= 31 -> LensTier.MEDIUM
    else -> LensTier.LOWEST
}

/**
 * 全局档位 → 本文件的视觉档位。
 *
 * **全局唯一权威是 [EffectTier]**（用户在设置里选的等级 + EffectGovernor 按温度/掉帧的降级），
 * 本文件不再自己按 `SDK_INT` 推 —— 那正是"用户在设置里切档、画面毫无变化"的根因：
 * 设置面板改的是 [EffectTier]，而这里读的是 `Build.VERSION.SDK_INT`，两者永不相关。
 *
 * 映射口径（与 [EffectTier.minApi] 一一对应）：
 *   · [EffectTier.HIGH]   → [LensTier.HIGHEST]（RuntimeShader 可用，真折射 + 真色散）
 *   · [EffectTier.MEDIUM] → [LensTier.MEDIUM]（只有 RenderEffect，真模糊）
 *   · [EffectTier.LOW]、[EffectTier.OFF] → [LensTier.LOWEST]（自绘，无模糊无折射）
 *
 * OFF 额外由调用方关掉"放大 / 染色 / 描边"三项增强，见 [LensSideBar] 里的 `active`。
 */
fun EffectTier.toLensTier(): LensTier = when (this) {
    EffectTier.HIGH -> LensTier.HIGHEST
    EffectTier.MEDIUM -> LensTier.MEDIUM
    EffectTier.LOW, EffectTier.OFF -> LensTier.LOWEST
}

/**
 * 透镜侧栏原型。
 *
 * 左右镜像**不经过这里**：整条侧栏由外层 Row 摆到屏幕另一侧，项本来就居中于侧栏
 * 内容区，所以水滴的横向位置与左右无关。
 *
 * @param backdrop 内容区那块 LayerBackdrop。**只给侧栏本体读**（本体再导出成 railBackdrop 给水滴）。
 * @param lensEnabled 透镜效果开关。false 时视觉回到改动前。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LensSideBar(
    items: List<ShellItem>,
    selectedId: String?,
    onSelect: (ShellItem) -> Unit,
    backdrop: LayerBackdrop?,
    lensEnabled: Boolean,
    modifier: Modifier = Modifier,
    /**
     * 侧栏**真正**能用的纵向空间（dp）。调用方用 `BoxWithConstraints` 量出来传进来。
     *
     * 为什么不直接读 [com.qingke.desk.core.platform.DeviceProfile.heightDp]：
     * 那是**窗口范围**（上界），比真实可画范围大 —— 基准机上是 411 dp vs 395 dp，
     * 而 `wm size` 模拟 800×360 dp 那组差到 360 dp vs 344 dp。
     * 按上界均分就会把最后一项排到屏幕外（实测被裁掉一截，见 step 6 截图）。
     *
     * 为 null 时退回档案值 —— 只在"没有确定容器可量"的调试页里发生。
     */
    availableHeightDp: Float? = null,
    /**
     * 深色底衬模式（地图漫游态专用）。见 [SideBar] 的同名参数。
     * true 时侧栏主体玻璃改用深色磨砂底，不再采样 [backdrop]（地图采不到），
     * 也因此**不做水滴采样**（没有可采样的 Compose 背景，rail 层无从导出）。
     */
    darkUnderlay: Boolean = false,
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current

    // ---- 条目尺寸按可用高度均分（2026-10-13 自适应框架）--------------------
    //
    // 原来 `sideBarItemSize` 是写死的 64dp，配套的算术是
    // "5×64 + 4×6 + 2×6 = 356 ≤ 361" —— 只在 361dp 高的机器上成立。
    // 换一台矮一点的机器这列就比屏幕高（第五项被挤掉），换高的又留一大段空。
    //
    // 现在交给 fitSidebar()：先均分，够不到"带文字"的 64dp 就隐藏文字只留图标，
    // 连"纯图标"的 54dp 都够不到时它返回 fitting=false，
    // 这里按它给的 suggestedMaxItems 少显示几个入口（历史上 6 项 → 5 项就是这么做的：
    // 宁可少一个入口，也不做点不中的小按钮）。
    // 优先用**量出来的**容器高度（调用方给），没有才退回档案里的窗口上界。
    val profile = LocalDeviceProfile.current
    val railHeightDp = (availableHeightDp ?: profile.heightDp.toFloat()).coerceAtLeast(1f)
    val fit = remember(railHeightDp, items.size) {
        fitSidebar(
            availableHeightDp = railHeightDp,
            itemCount = items.size,
        )
    }
    // fitting=false 时按"纯图标一格（54dp）还能排几个"截掉尾部入口。
    // 被截掉的是最后几项（ newest 优先保留前几个主入口）。
    val shownItems = if (fit.fitting) items else items.take(fit.suggestedMaxItems)
    if (shownItems.isEmpty()) return

    val stepPx = with(density) { (fit.itemSize + fit.gap).toPx() }
    val itemSizePx = with(density) { fit.itemSize.toPx() }
    val innerPaddingPx = with(density) { DeskDimens.sideBarInnerPadding.toPx() }
    val lastIndex = shownItems.lastIndex

    // 选中项的位置**必须在 [shownItems] 上找**，不能在完整的 [items] 上找：
    // 入口被截断时（fitting=false）下标会越界 —— 5 项截成 3 项、而当前屏对应第 5 项时，
    // 这里会返回 4，接着 `indicator.animateTo(4f)` 把水滴滑到列表外面去。
    // 找不到（被截掉的那一项正被选中）退回 0：宁可水滴停在第一项，也不要越界。
    val selectedIndex = shownItems.indexOfFirst { it.id == selectedId }
        .let { if (it < 0) 0 else it }

    // 位置单位是"第几项"的浮点值，与正式侧栏一致：条目尺寸变了也不用重算动画。
    val indicator = remember { Animatable(0f) }
    val indicatorState = remember(indicator) { indicator.asState() }
    // 按下/拖动的强度 0..1。水滴缩放与镜内放大量都由它插值 —— 一条弹簧驱动全部形变。
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

    // 抬手回弹：press 归零，水滴缩回 1.0。与位置吸附是两条独立的弹簧。
    LaunchedEffect(dragging) {
        press.animateTo(
            targetValue = if (dragging) 1f else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }

    // 档位由 [LocalEffectTier] 下发，本文件只做一次类型映射（见 [toLensTier]）。
    val effectTier = LocalEffectTier.current
    val tier = effectTier.toLensTier()

    // OFF 档：玻璃层根本不建（backdrop 为 null），透镜的三项增强一起关掉 ——
    // 放大、染色、描边都是"玻璃上的效果"，底下没有玻璃时它们只剩浮夸；
    // 描边同时还是侧栏胶囊的那条边，也在 `active` 里一并关掉。
    val active = lensEnabled && LensSpec.ENABLED && effectTier != EffectTier.OFF

    // ---- 侧栏本体导出的那一层（单向 DAG 的中间节点）----------------------
    //
    // 只在**正常态且 active** 时使用导出：darkUnderlay（地图漫游态）没有可采样的 Compose
    // 背景（地图是独立 SurfaceView 图层），rail 层导出出来也是空的，保持现有
    // deskGlassOverlay 深色底衬路径不变、不做水滴采样。
    val railBackdrop = rememberLayerBackdrop()
    val exportRail = !darkUnderlay && active
    // **真折射自给自足**模式：HIGHEST 档（RuntimeShader）+ railBackdrop 已导出。
    // 水滴靠 lens(depthEffect) 折射 railBackdrop 里的原始条目 —— 此时：
    //   · 水滴内**不画** LensGlyph（自绘副本 + 折射像 = 真机录屏里的"双影重字"）；
    //   · 列内条目**不做 covered 淡出**（淡出了 railBackdrop 里就没内容可折射）。
    val selfRefract = tier == LensTier.HIGHEST && exportRail

    // 水滴要采样的 backdrop：
    //   · 正常态 + active：采 railBackdrop（侧栏本体导出的玻璃 + 描边，这才是"水滴"）；
    //   · darkUnderlay / 未 active：退回改动前那条路径（直接采内容区 backdrop / 静态指示块）。
    val dropBackdrop = if (exportRail) railBackdrop else backdrop

    // 水滴圆角：开启时大一点（镜片），关闭时回到指示块圆角。
    val lensCorner = if (active) LensSpec.lensCorner else DeskDimens.sideBarIndicatorCorner

    // **动画的量一律不许在组合期读**（2026-10-13 崩溃复盘，见文件头"每帧重组会崩"）：
    // 下面两个只是"取值函数"，必须放进 offset{} / graphicsLayer{} / onDrawSurface 这类
    // **布局或绘制阶段**的 lambda 里调用。写在组合期 = 拖动时每帧重组整棵玻璃子树 =
    // 每帧重建图形层，真机上 hwui 的 SurfaceComposerClient 事务会直接 SIGSEGV。
    val pressAmount: () -> Float = { if (active) pressState.value else 0f }
    // 被水滴罩住的程度：0 = 完全在镜外（列内原样画），1 = 完全在镜内（列内隐藏）。
    // 只由 index 与当前位置决定，读状态这件事发生在调用它的 graphicsLayer lambda 里。
    val halfSpan = (itemSizePx / 2f) / stepPx
    val covered: (Int) -> Float = { index ->
        // selfRefract 时恒 0：列内必须原样画着，railBackdrop 里才有图标可供水滴折射
        //（见文件头"镜内为什么不再自绘图标"—— 淡出列内 = 折射不出东西 = 空水滴）。
        if (!active || selfRefract) 0f
        else ((halfSpan + 0.05f - abs(indicatorState.value - index)) / LENS_FADE_SPAN)
            .coerceIn(0f, 1f)
    }

    // 容器高度 = 内容列高 + 上下内边距。取**显式高度**而不是让它 wrap：
    // 本体用 fillMaxSize() 铺满容器（玻璃要盖到内边距外面那圈），显式高度才让 fillMaxSize
    // 有确定的边界；这个值必须与 Column 的实际高度一致，否则玻璃会与条目错位。
    val railHeight = fit.itemSize * shownItems.size +
        fit.gap * (shownItems.size - 1) +
        DeskDimens.sideBarInnerPadding * 2

    Box(
        modifier = modifier
            // 宽度跟着**实际**条目尺寸走（原来取写死的 sideBarWidth 76dp）。
            .width(fit.itemSize + DeskDimens.sideBarInnerPadding * 2)
            .height(railHeight)
            // 手势仍挂在容器上：拖动/点击的坐标系就是"侧栏胶囊外框"，与水滴的定位基准一致。
            //
            // key 里放的是 **shownItems** 而不是 items：截断后条数变了，手势闭包里的
            // 越界判断（lastIndex）与取项（shownItems[target]）都要跟着重建，
            // 否则 pointerInput 会一直握着旧列表的那个 lambda。
            .pointerInput(shownItems, stepPx, lastIndex) {
                detectVerticalDragGestures(
                    onDragStart = { down ->
                        dragging = true
                        // down.y 是相对**容器（含内边距）**的坐标，减掉上内边距才落到内容列。
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
                        // 取的是**显示出来的**那一项：target 本来就被夹在 shownItems.lastIndex 内，
                        // 这里再用 items 去取就是拿小下标查大列表 —— 越界在截断时必然发生。
                        onSelect(shownItems[target])
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
        // ------------------------------------------------------------------
        // (a) 侧栏本体：玻璃盖满整条 rail，并把"自己"导出成 railBackdrop 给水滴采样。
        //
        // 描边画在 onDrawFront（不是 Modifier.border）—— 只有进 front 的那条才会被导出，
        // 水滴里才看得到左右边框线穿进去弯折。理由见文件头。
        // ------------------------------------------------------------------
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (darkUnderlay) {
                        // 地图漫游态：深色磨砂底衬。deskGlassOverlay 自带描边与漫散射光，
                        // 所以不叠 rail 描边（否则是双描边）。
                        Modifier.deskGlassOverlay(
                            shape = RoundedCornerShape(DeskDimens.sideBarCorner),
                            alpha = SIDEBAR_DARK_UNDERLAY_ALPHA,
                        )
                    } else {
                        // 2026-10-17 按参考视频口径：深色半透明底（0.28）+ 4dp 轻模糊
                        // + 1dp 亮边描边（描边在 railStrokeOnFront）。深底让被水滴折射的
                        // 边框线与条目在镜内对比度足够；4dp 是"轻度"——再糊就成雾了。
                        Modifier.deskGlassSurface(
                            backdrop = backdrop,
                            shape = { RoundedCornerShape(DeskDimens.sideBarCorner) },
                            surfaceColor = Color.Black,
                            alpha = LensSpec.RAIL_SURFACE_ALPHA,
                            blurRadius = LensSpec.railBlurRadius,
                            exportedBackdrop = if (exportRail) railBackdrop else null,
                            onDrawFront = if (active) railStrokeOnFront else null,
                        )
                    },
                )
                // LOW 档（active 但走静态路径、不会调 onDrawFront）补一条描边，
                // 别让侧栏在低端机上丢了轮廓。MEDIUM/HIGH 档描边由 onDrawFront 负责，
                // 这里 width=0 不画，避免双描边。
                .then(
                    if (!darkUnderlay && active && tier == LensTier.LOWEST) {
                        Modifier.border(
                            width = 1.dp,
                            brush = railStrokeBrush,
                            shape = RoundedCornerShape(DeskDimens.sideBarCorner),
                        )
                    } else {
                        Modifier
                    },
                ),
        ) {
            Column(
                modifier = Modifier.padding(DeskDimens.sideBarInnerPadding),
                verticalArrangement = Arrangement.spacedBy(fit.gap),
            ) {
                shownItems.forEachIndexed { index, item ->
                    val selected = item.id == selectedId
                    // 选中项染强调色（2026-10-17：静止态的"内嵌低调块"口径 —— 水滴停在
                    // 选中项上时，透过去的图标与文字是强调色；其余项一律原色）。
                    val tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    // **被水滴视野罩住的那些项，在列内淡出**：
                    // 镜里已经有它们的放大副本，列内原样画就会叠成"双影"。
                    // 范围取水滴半高折成的格数 + 半个淡出带宽，且是**连续**的 ——
                    // 拖动时是渐变而不是整格突变。
                    //
                    // 淡出量同样只在 graphicsLayer 的 lambda 里算（[covered] 内部读
                    // indicatorState），否则又变成每帧重组。
                    Box(
                        modifier = Modifier.graphicsLayer { alpha = 1f - covered(index) },
                    ) {
                        LensRailItem(
                            item = item,
                            tint = tint,
                            fit = fit,
                            onClick = { onSelect(item) },
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // (b) 水滴透镜：平级兄弟、后绘制。只读 railBackdrop，自身不导出（DAG 单向）。
        //
        // 外层锚盒子 = 一个"跟条目一样大"的盒子，用 offset 跟着 indicator 走；
        // 水滴再在锚盒子里用 Alignment.Center 居中、requiredSize 到 itemSize 以上。
        // 横向/纵向都不手算偏移量（手算的版本连错两次，见旧版注释的教训）。
        // ------------------------------------------------------------------
        Box(
            modifier = Modifier
                // X 偏移补上侧栏内边距（sideBarInnerPadding），让水滴**横向居中**于侧栏内容区
            // （与 Column 的 padding 对齐），这样按下鼓出时左右边框才会被对称地弯折；
            // 原来 X=0 会把水滴顶到侧栏左缘，鼓出只发生在左侧、右侧留一大段空。
            .offset {
                IntOffset(
                    innerPaddingPx.roundToInt(),
                    (innerPaddingPx + indicatorState.value * stepPx).roundToInt(),
                )
            }
                .size(fit.itemSize)
                .align(Alignment.TopStart),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    // requiredSize 而不是 size：水滴按压时要**故意鼓出**锚盒子与侧栏边界，
                    // 不能被父约束夹回去。
                    .requiredSize(fit.itemSize)
                    .graphicsLayer {
                        // 形变只在这里（绘制阶段）读 pressState，且**各向异性**：
                        //   宽：itemSize → 侧栏总宽 × 1.22（≈92.7dp，每边越框 8.3dp，
                        //        左右边框线整段穿进水滴被真折射）；
                        //   高：itemSize → itemSize × 1.15（只是"鼓"，不越上下邻项太远）。
                        // 一条 scale 走天下做不到这件事 —— 横向鼓出量是相对**侧栏宽**算的。
                        val railWidthPx = itemSizePx + innerPaddingPx * 2f
                        val targetSx = railWidthPx * LensSpec.PRESS_WIDTH_OVERSIDE / itemSizePx
                        val targetSy = 1f + LensSpec.PRESS_HEIGHT_EXTRA
                        val p = pressAmount()
                        scaleX = 1f + (targetSx - 1f) * p
                        scaleY = 1f + (targetSy - 1f) * p
                        transformOrigin = TransformOrigin.Center
                    }
                    .waterDropGlass(
                        backdrop = dropBackdrop,
                        shape = RoundedCornerShape(lensCorner),
                        corner = lensCorner,
                        tier = tier,
                        active = active,
                        pressAmount = pressAmount,
                    )
                    // 外缘描边：真色散可用的高档位交给 shader（chromaticAberration），
                    // 中/低档位用一条同族语言的半透明白光渐变近似。
                    .border(
                        width = if (active && tier != LensTier.HIGHEST) LensSpec.rimStroke else 0.dp,
                        brush = lensRimBrush,
                        shape = RoundedCornerShape(lensCorner),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 镜内自绘内容**只在非 selfRefract 档位出现**：
                // HIGHEST + railBackdrop 已导出时，水滴里的一切（放大图标、弯折边框、
                // 色散）全部由 lens(depthEffect) 折射 railBackdrop 得到 —— 再画一份
                // LensGlyph 就是真机录屏里的"双影重字"。此时这里就是一个空的透明透镜 Box。
                if (active && !selfRefract) {
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

                    // **镜内不是"一项"，而是"全部项的放大副本"**（导出不含子内容，故自绘）。
                    //
                    // 每一项用**绝对定位**放在"它相对水滴中心的位置"上：
                    //   centerY_i = itemSize/2 + (i - pos) * step
                    // 所以拖动时这些副本会整体滚过水滴，某一项进入中心就被放大 ——
                    // 而不是"一格一跳地换图标"。
                    //
                    // 为什么不用"整列 + 一个大 offset"：那样 Column 的布局高度仍是水滴高，
                    // 被 offset 推出去的内容不在 layer 边界内，实测整块不渲染（镜里空的）。
                    // 改成一格一格绝对定位后，每项的位移都只有一两格，稳。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val b = LensSpec.GLYPH_BOOST_IDLE +
                                    (LensSpec.GLYPH_BOOST_PRESSED - LensSpec.GLYPH_BOOST_IDLE) *
                                    pressAmount()
                                scaleX = b
                                scaleY = b
                                transformOrigin = TransformOrigin.Center
                            }
                            // 只裁"镜内内容"：把滚过水滴的相邻项裁在镜片圆角内，避免双影溢出镜外；
                            // 这是**内层**裁切，外层的 graphicsLayer 放大发生在它之外，
                            // 所以水滴整体仍能鼓出侧栏边界（见上面删掉的 `.clip`）。
                            .clip(RoundedCornerShape(lensCorner)),
                    ) {
                        // **全部项都画，不按当前位置裁窗口**。
                        //
                        // 第一版按 `ceil(pos - 1.2) .. floor(pos + 1.2)` 取一个窗口，看着省事，
                        // 但那个范围是在**组合期**由 pos 算出来的 —— pos 每帧变，这个循环
                        // 就每帧重组，正是崩机的根因（见文件头）。现在窗口恒为 0..lastIndex，
                        // 组合结果与位置无关；某一项画在哪，交给下面 offset 的 lambda 去算。
                        //
                        // 远处的项会被 clip 裁掉，只是白布局一下（5 个小图标，
                        // 比每帧重建玻璃子树便宜得多）。
                        for (i in 0..lastIndex) {
                            key(i) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopCenter)
                                        .offset {
                                            IntOffset(
                                                x = 0,
                                                y = (
                                                    itemSizePx / 2f +
                                                        (i - indicatorState.value) * stepPx -
                                                        itemSizePx / 2f
                                                    ).roundToInt(),
                                            )
                                        },
                                ) {
                                    LensGlyph(
                                        item = shownItems[i],
                                        tint = MaterialTheme.colorScheme.primary,
                                        fit = fit,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 水滴玻璃。**按运行时档位选实现**（2026-10-17 按参考视频口径重写）：
 *
 * - [LensTier.HIGHEST]：`drawBackdrop` + **只开 vibrancy 与 lens**（无 blur！静止态连
 *   lens 也不开）。lens 参数随 press 插值：`height = 16+10p dp`、`amount = 28+20p dp`、
 *   `depthEffect = true`（图标放大/边缘拉伸全靠它对 railBackdrop 像素做光学扭曲）、
 *   `chromaticAberration = true`（真色散）。白蒙版 `0.14 × (1-press)` 线性清零 ——
 *   按住的水滴是**全透明凸透镜**，不是毛玻璃圆饼。注意 lens 的两个 boolean
 *   **只能按位置传**（jar 里没有形参名）。
 * - [LensTier.MEDIUM]：保留 blur（这一档没有折射，blur 是唯一的深度线索），
 *   镜内自绘副本照旧。
 * - [LensTier.LOWEST]：不给水滴单独开一层 backdrop，用一块随按下变淡的静态白玻璃代替。
 *
 * 采样的目标由调用方给（[backdrop]）：正常态是侧栏本体导出的 `railBackdrop`，
 * darkUnderlay / 未 active 时退回内容区 backdrop。**本函数不导出**（`exportedBackdrop`
 * 不传 = null），保证 "读 rail 的人不再写 rail" 这条单向 DAG。
 *
 * `active=false` 有两种来源，两者都只画与改动前一致的指示块玻璃（见 [LensSideBar]）。
 */
@Composable
private fun Modifier.waterDropGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    corner: Dp,
    tier: LensTier,
    active: Boolean,
    pressAmount: () -> Float,
): Modifier {
    if (!active) {
        return this.deskGlassSurface(
            backdrop = backdrop,
            shape = { shape },
            alpha = DeskGlass.indicatorAlpha,
        )
    }
    // 低版本 / 没有可采样的层：**不申请多余的一层 backdrop**。理由见本函数文档 ——
    // 这一层在低版本上没有任何效果（blur/lens 静默 return），只有成本（多一块 SurfaceControl）。
    // 白底 alpha 仍随按下变淡，但读它发生在**绘制期**的 drawBehind lambda 里。
    if (backdrop == null || tier == LensTier.LOWEST) {
        return this.drawBehind {
            drawRoundRect(
                color = Color.White.copy(alpha = dropSurfaceAlpha(pressAmount())),
                cornerRadius = CornerRadius(corner.toPx()),
            )
        }
    }
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            // 两个 boolean 是位置参数：lens(refractionHeight, refractionAmount, depthEffect, chromaticAberration)
            if (tier == LensTier.HIGHEST) {
                vibrancy()
                // **没有 blur** —— 清澈凸透镜。静止（press≈0）也不开 lens：
                // 静止态的口径是"侧栏内嵌的低调圆角块"，不是一颗常驻放大镜。
                val p = pressAmount()
                if (p > 0.01f) {
                    val h = LensSpec.refractionHeightBase +
                        LensSpec.refractionHeightPress * p
                    val a = LensSpec.refractionAmountBase +
                        LensSpec.refractionAmountPress * p
                    lens(
                        h.toPx(),
                        a.toPx(),
                        true,
                        true,
                    )
                }
            } else {
                vibrancy()
                blur(LensSpec.dropBlurRadiusMedium.toPx())
            }
        },
        highlight = { Highlight.Default },
        // 白底蒙版：0.14 × (1 - press)，按住即全透明。alpha 只在绘制期 lambda 里
        // 读 pressAmount() 算，组合期不读 —— 见文件头"每帧重组会崩"。
        onDrawSurface = { drawRect(Color.White.copy(alpha = dropSurfaceAlpha(pressAmount()))) },
    )
}

/**
 * 水滴白蒙版的不透明度：`0.14 × (1 - press)` 线性清零（2026-10-17 按参考口径，
 * 旧的 smoothstep 两段式删了 —— 需求要的是"按住的瞬间开始向全透明收敛"，
 * 线性即正确语义；静止态 0.14 是"内嵌低调块"的可见度下限）。
 */
private fun dropSurfaceAlpha(press: Float): Float =
    LensSpec.LENS_SURFACE_ALPHA * (1f - press)

/**
 * 侧栏本体那条 1dp 细描边，画在 `onDrawFront` 里 —— 只有画在 front 的描边会进导出层，
 * 被水滴采样后在水滴里发生光学弯折（理由见文件头）。
 *
 * 用**顶层常量 lambda** 而不是内联闭包：它的引用必须跨重组稳定，否则每次重组
 * 都会让库的 `DrawBackdropElement.equals` 判不等 → 重建节点 + `invalidateDrawCache()`。
 */
private val railStrokeOnFront: DrawScope.() -> Unit = {
    val strokePx = 1.dp.toPx()
    // 内缩半个描边宽，让整条线都落在节点内（避免外侧半像素被导出层裁掉）。
    drawRoundRect(
        brush = railStrokeBrush,
        topLeft = Offset(strokePx / 2f, strokePx / 2f),
        size = Size(size.width - strokePx, size.height - strokePx),
        cornerRadius = CornerRadius(
            (DeskDimens.sideBarCorner.toPx() - strokePx / 2f).coerceAtLeast(0f),
        ),
        style = Stroke(width = strokePx),
    )
}

/**
 * 镜内的图标 + 文字副本：与列内条目布局完全相同，只是被染色、且由外层的
 * graphicsLayer 统一放大（放大原点在水滴中心，所以"越靠中心越大"是天然出来的）。
 *
 * 为什么还要自绘：库的导出**不含子内容**（见文件头），若只采样 railBackdrop，
 * 水滴里就是一个"清澈但空"的玻璃，没有任何图标。这条自绘补的正是这块。
 */
@Composable
private fun LensGlyph(
    item: ShellItem,
    tint: Color,
    fit: SidebarFit,
) {
    Box(
        modifier = Modifier.size(fit.itemSize),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(fit.iconSize),
            )
            // 放不下文字标签时（[SidebarFitLevel.ICON_ONLY]）这一行整段不画 ——
            // 不是把字号缩到看不见，而是**只留图标**：半截标签比没有标签更难看。
            if (fit.showLabel) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = item.label,
                    fontSize = fit.labelSize,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 列内条目：无涟漪、单击即切屏。
 *
 * 淡出**不在这里做**（曾经是这里接一个 alpha 参数）：那样每帧要把新值传进组合，
 * 等于每帧重组。改由调用方在 `graphicsLayer { alpha = ... }` 里读位置算。
 */
@Composable
private fun LensRailItem(
    item: ShellItem,
    tint: Color,
    fit: SidebarFit,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(fit.itemSize)
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
                modifier = Modifier.size(fit.iconSize),
            )
            if (fit.showLabel) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = item.label,
                    fontSize = fit.labelSize,
                    color = tint,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 水滴外缘的一圈柔光描边：上亮下暗，与 [deskGlassOverlay] 的描边同款语言。
 *
 * HIGHEST 档不用它 —— 那里靠 shader 的 `chromaticAberration` 给真色散边缘。
 */
private val lensRimBrush: Brush
    get() = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.42f),
            Color.White.copy(alpha = 0.08f),
        ),
    )

/** 侧栏胶囊的细描边：上亮下暗，配合高光做出"边缘有一层薄玻璃"的意思。 */
private val railStrokeBrush: Brush
    get() = Brush.verticalGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.22f),
            Color.White.copy(alpha = 0.05f),
        ),
    )
