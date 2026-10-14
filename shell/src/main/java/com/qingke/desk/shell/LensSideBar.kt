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
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskGlass
import com.qingke.desk.core.ui.LocalDeviceProfile
import com.qingke.desk.core.ui.LocalEffectTier
import com.qingke.desk.core.ui.SidebarFit
import com.qingke.desk.core.ui.SidebarFitLevel
import com.qingke.desk.core.ui.deskGlassSurface
import com.qingke.desk.core.ui.fitSidebar
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
 * 玻璃透镜侧栏 —— 侧栏 [SideBar] 的实现本体。
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
 * 自适应分档（2026-10-14 改：档位来源是用户设置，不是 API 等级）
 *
 * 档位一律来自 [EffectTier]（用户在设置面板选的等级 + EffectGovernor 按温度/掉帧的降级），
 * 由 [toLensTier] 映射成下面的 [LensTier]；**本文件不再读 `Build.VERSION.SDK_INT` 自己推** ——
 * 之前读它，于是设置面板切"高/中/低/关"画面完全不变，因为两条线从来不相交。
 *
 * | 档位 | 来源 | 镜片玻璃 | 彩色边缘 |
 * |---|---|---|---|
 * | [LensTier.HIGHEST] | `EffectTier.HIGH`（RuntimeShader，API 33+ 才可能被 Governor 给到） | 真折射 + 真模糊 + **真色散** | 由 shader 给出 |
 * | [LensTier.MEDIUM] | `EffectTier.MEDIUM`（RenderEffect，API 31+） | 真模糊，无折射 | 渐变描边近似 |
 * | [LensTier.LOWEST] | `EffectTier.LOW` / `EffectTier.OFF` | 半透明底 + 高光，无模糊无折射 | 渐变描边近似 |
 *
 * 前两档**都能看到**"镜内放大 + 染色 + 镜片比项大"，因为这些是自绘的，不依赖 RuntimeShader。
 * 分档只决定"折射/色散/模糊"这层增强有没有。
 *
 * [EffectTier.OFF] 更进一步：**放大、染色、描边也一起关掉**（见 [LensSideBar] 里的 `active`）——
 * 用户明确选了"关"，那就不该看到任何靠玻璃撑起来的效果。此时 backdrop 为 null
 * （[rememberDeskGlassBackdrop] 在 OFF 档返回 null），`blur()/lens()` 本来也无层可画。
 * **但侧栏胶囊与选中块本身仍在**（静态纯色底，[deskGlassSurface] 对 OFF/LOW 走同一条路径）——
 * "关闭"省掉的是采样与合成开销，不该省成"侧栏整条消失"。
 *
 * ---------------------------------------------------------------------------
 * 竖向布局的溢出：**不越界**
 *
 * 侧栏宽 76 = 项 64 + 内边距 6×2。镜片取 68 而不是"看起来更能装"的 72，是两条
 * 硬约束挤出来的结果（详见 [LensSpec.lensSize] 的注释）：
 *   1. 静止时居中于项，左右各占 2dp 余量 —— 仍在 76dp 的侧栏内；
 *   2. **按下放大 1.12 倍之后也不能探出侧栏**：68 × 1.12 ≈ 76.2dp，取 72 就会在
 *      施工按下的那一瞬多出 8px，压到内容区里去。
 * 想要更大的鼓出，得先解决第 2 条（缩 press 倍率或加宽侧栏），不是改个数就行。
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
 * 旧侧栏的文件头早就写了这条（"位移要在 placement 阶段，别让玻璃每帧重建"），
 * 我加镜片时把这条忘了。
 *
 * 修法（两条都用上，缺一条都不放心）：
 *   1. 所有随动画变化的量只许在 `offset {}` / `graphicsLayer {}` 这类**布局或绘制
 *      阶段**的 lambda 里读，组合期一律不读。镜内因此改成"全部项都画"，
 *      窗口不再由位置决定 —— 组合结果与位置无关。
 *   2. 低版本（LOWEST）不再给镜片单独开一层 backdrop：那层在上面本来就没效果，
 *      却要多一块 SurfaceControl。
 *
 * 判据：拖 10 个来回 + 反复切屏，`/data/tombstones` 数量不变、进程 pid 不变。
 */
object LensSpec {

    /** 总开关。false = 回到改动前的样式（同一套代码，只是不放大、不染色、镜片回到 64dp）。 */
    const val ENABLED: Boolean = true

    /**
     * 镜片边长。**68dp = 项 64 + 每边 2**，取值由两条约束一起定下来：
     *
     *   1. 静止时不越出侧栏（76dp）自不必说；
     *   2. **按下放大到 1.12 倍时也要留在侧栏里**：68 × 1.12 ≈ 76.2dp —— 正好贴住
     *      [DeskDimens.sideBarWidth]，多 1dp 就会在施工按下的那一瞬露出侧栏外面去。
     *
     * 顺带解决一个容易被忽略的问题：一格的总高是 70dp（项 64 + 间距 6），
     * 镜片取 68dp < 70dp，静止时它落在自己那一行里、不会压到相邻项。
     * （第一版取 72dp，静止就比一格还高 2dp，相邻项被压掉 1dp。）
     */
    val lensSize = 68.dp

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
 * 内容区，所以镜片的横向位置与左右无关（第一版留过一个 `side` 参数，实测没有任何
 * 一处用到它，删掉 —— 留着只会让人以为这里藏着镜像逻辑）。
 *
 * @param backdrop 内容区那块 LayerBackdrop（与侧栏本体共用同一个源）。
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
    // 这里会返回 4，接着 `indicator.animateTo(4f)` 把镜片滑到列表外面去。
    // 找不到（被截掉的那一项正被选中）退回 0：宁可指示块停在第一项，也不要越界。
    val selectedIndex = shownItems.indexOfFirst { it.id == selectedId }
        .let { if (it < 0) 0 else it }

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

    // 档位由 [LocalEffectTier] 下发，本文件只做一次类型映射（见 [toLensTier]）。
    val effectTier = LocalEffectTier.current
    val tier = effectTier.toLensTier()

    // OFF 档：玻璃层根本不建（backdrop 为 null），透镜的三项增强一起关掉 ——
    // 放大、染色、描边都是"玻璃上的效果"，底下没有玻璃时它们只剩浮夸；
    // 描边同时还是侧栏胶囊的那条边，也在 `active` 里一并关掉。
    val active = lensEnabled && LensSpec.ENABLED && effectTier != EffectTier.OFF

    // 镜片尺寸：开启时比项大一点，关闭时回到条目尺寸（= 改动前的指示块）。
    //
    // 68dp 这个数原来是写死的，它的**约束**其实是"按下放大 1.12 倍之后不越出侧栏宽"，
    // 而侧栏宽 = 条目边长 + 内边距×2 —— 条目边长现在按屏幕算了，这个数也得跟着算：
    //   lensSize × 1.12 ≤ itemSize + 2×innerPadding
    // 条目 64、内边距 6 时解得 67.86 ≈ 68，与原来的值对上。
    // 另外夹一个 itemSize + 4 的上限，避免在很高的屏上镜片比条目大太多。
    val lensSize = if (active) {
        val maxByWidth = (fit.itemSize.value + DeskDimens.sideBarInnerPadding.value * 2f) /
            (1f + LensSpec.PRESS_SCALE_EXTRA)
        minOf(maxByWidth, fit.itemSize.value + 4f).dp
    } else {
        fit.itemSize
    }
    val lensSizePx = with(density) { lensSize.toPx() }
    val lensCorner = if (active) LensSpec.lensCorner else DeskDimens.sideBarIndicatorCorner

    // **动画的量一律不许在组合期读**（2026-10-13 崩溃复盘，见文件头"每帧重组会崩"）：
    // 下面两个只是"取值函数"，必须放进 offset{} / graphicsLayer{} 这类**布局或绘制阶段**
    // 的 lambda 里调用。写在组合期 = 拖动时每帧重组整棵玻璃子树 = 每帧重建图形层，
    // 真机上 hwui 的 SurfaceComposerClient 事务会直接 SIGSEGV。
    val pressAmount: () -> Float = { if (active) pressState.value else 0f }
    // 被镜片罩住的程度：0 = 完全在镜外（列内原样画），1 = 完全在镜内（列内隐藏）。
    // 只由 index 与当前位置决定，读状态这件事发生在调用它的 graphicsLayer lambda 里。
    val halfSpan = (lensSizePx / 2f) / stepPx
    val covered: (Int) -> Float = { index ->
        if (!active) 0f
        else ((halfSpan + 0.05f - abs(indicatorState.value - index)) / LENS_FADE_SPAN)
            .coerceIn(0f, 1f)
    }

    Box(
        modifier = modifier
            // 宽度跟着**实际**条目尺寸走（原来取写死的 sideBarWidth 76dp）。
            .width(fit.itemSize + DeskDimens.sideBarInnerPadding * 2)
            .deskGlassSurface(
                backdrop = backdrop,
                shape = { RoundedCornerShape(DeskDimens.sideBarCorner) },
            )
            .border(
                // 这条描边是"深色半透明胶囊 + 细描边"里的一分子，属于新样式，
                // 所以跟着 active 一起开关：总开关关掉后要回到**改动前**的侧栏，
                // 连描边也不该多出来。
                width = if (active) 1.dp else 0.dp,
                brush = railStrokeBrush,
                shape = RoundedCornerShape(DeskDimens.sideBarCorner),
            )
            .padding(DeskDimens.sideBarInnerPadding)
            // key 里放的是 **shownItems** 而不是 items：截断后条数变了，手势闭包里
            // 的越界判断（lastIndex）与取项（shownItems[target]）都要跟着重建，
            // 否则 pointerInput 会一直握着旧列表的那个 lambda。
            .pointerInput(shownItems, stepPx, lastIndex) {
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
        // 镜片画在条目**下层**：它是"选中项后面的一块透镜"，镜内副本由它自己带。
        //
        // （纵向定位的说明见下；条目尺寸现在由 [fit] 给，不读 DeskDimens.sideBarItemSize。）
        //
        // 纵向要减掉 overhang：镜片比项高 8dp，不减会让它整体偏下 4dp，
        // 镜内副本就会与它"罩住"的那一项错开半个身位。
        // 外层先占一个"跟条目一样大"的盒子，跟着 indicator 走；镜片再在这个盒子里
        // 用 Alignment.Center 居中 —— **横向/纵向都不手算偏移量**。
        //
        // 手算的版本连错两次，记录在这里免得再回头写：
        //   第一版 `size(68dp)` 被父级约束夹成 224px 宽（分不清它是"多大"），
        //         配 (itemSize - lensSize)/2 的偏移就整体偏左 4dp；
        //   第二版改成 requiredSize，宽度对了，但 `-overhang` 那个偏移在渲染时
        //         实际落了一份两倍的位移（实测中心 262.5 vs 条目中心 269.5，偏左 2dp）。
        // 交给 Alignment 居中，就不存在"两套算术对不上"的机会。
        Box(
            modifier = Modifier
                .offset { IntOffset(0, (indicatorState.value * stepPx).roundToInt()) }
                .size(fit.itemSize),
            contentAlignment = Alignment.Center,
        ) {
        Box(
            modifier = Modifier
                // requiredSize 而不是 size：这块要**故意比条目大**，不能被父约束夹回去。
                .requiredSize(lensSize)
                .graphicsLayer {
                    val s = 1f + LensSpec.PRESS_SCALE_EXTRA * pressAmount()
                    scaleX = s
                    scaleY = s
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
                            val b = LensSpec.GLYPH_BOOST_IDLE +
                                (LensSpec.GLYPH_BOOST_PRESSED - LensSpec.GLYPH_BOOST_IDLE) *
                                pressAmount()
                            scaleX = b
                            scaleY = b
                            transformOrigin = TransformOrigin.Center
                        },
                ) {
                    // **全部项都画，不按当前位置裁窗口**。
                    //
                    // 第一版按 `ceil(pos - 1.2) .. floor(pos + 1.2)` 取一个窗口，看着省事，
                    // 但那个范围是在**组合期**由 pos 算出来的 —— pos 每帧变，这个循环
                    // 就每帧重组，正是崩机的根因（见文件头）。现在窗口恒为 0..lastIndex，
                    // 组合结果与位置无关；某一项画在哪，交给下面 offset 的 lambda 去算。
                    //
                    // 远处的项会被镜片的 clip 裁掉，只是白布局一下（5 个小图标，
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
                                                lensSizePx / 2f +
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

        Column(
            verticalArrangement = Arrangement.spacedBy(fit.gap),
        ) {
            shownItems.forEachIndexed { index, item ->
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
}

/**
 * 镜片玻璃。**按运行时档位选实现**：
 *
 * - [LensTier.HIGHEST]：自己调库的 `drawBackdrop`，把 `chromaticAberration`/`depthEffect`
 *   两个开关打开 —— `core:ui` 的 [deskGlassSurface] 是统一封装，没开这两个开关，
 *   而"彩色边缘"正是靠色散给的。注意这两个 boolean **只能按位置传**：
 *   jar 里没有形参名（LocalVariableTable 为空），具名参数编译不过。
 * - [LensTier.MEDIUM]：退回 [deskGlassSurface]（真模糊生效、无折射 —— 它内部只开
 *   `vibrancy + blur`，因为 lens() 在 API 31/32 上本来就 return）。
 * - [LensTier.LOWEST]：**不再多开一层 backdrop**，用普通 Compose 画同款观感
 *   （半透明底 + 下面那层径向柔光）。低版本上 blur()/lens() 会静默 return，
 *   这层玻璃画出来**本来就没有折射也没有模糊**，却要多申请一块 SurfaceControl；
 *   真机实测每帧多这一块就把 hwui 的事务压崩了（见文件头"每帧重组会崩"）。
 *
 * `active=false` 有两种来源，两者都只画与改动前一致的指示块玻璃：
 *   · [LensSpec.ENABLED] / 调用方的透镜开关被关掉；
 *   · **档位是 [EffectTier.OFF]** —— 此时 [rememberDeskGlassBackdrop] 返回 null，
 *     [deskGlassSurface] 走 OFF/LOW 的静态路径，画出**一块纯色半透明底**
 *     （2026-10-14 改；此前 OFF 是"什么都不画"，结果是侧栏与选中块一起隐形）。
 *     所以 OFF 档看到的是"朴素的侧栏"，而不是"没有侧栏"。
 */
@Composable
private fun Modifier.lensGlass(
    backdrop: LayerBackdrop?,
    shape: Shape,
    tier: LensTier,
    active: Boolean,
): Modifier {
    if (!active) {
        return this.deskGlassSurface(
            backdrop = backdrop,
            shape = { shape },
            alpha = DeskGlass.indicatorAlpha,
        )
    }
    // 低版本：**不申请第二块 backdrop**。理由见本函数文档 —— 这一层在低版本上
    // 没有任何效果（blur/lens 静默 return），只有成本（多一块 SurfaceControl）。
    if (backdrop == null || tier == LensTier.LOWEST) {
        return this.background(
            color = Color.White.copy(alpha = LensSpec.LENS_SURFACE_ALPHA),
            shape = shape,
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
