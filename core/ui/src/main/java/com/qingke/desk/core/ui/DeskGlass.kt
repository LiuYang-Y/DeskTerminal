package com.qingke.desk.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight

/**
 * 液态玻璃效果的封装层：**整个工程只有本文件 import 第三方玻璃库**。
 *
 * 这么做是为了让"换掉/降级/关掉这层效果"只改一个开关，而不是全工程搜一遍 import。
 * 上层（:shell）拿到的只是"一个 Modifier + 一个 LayerBackdrop 句柄"。
 *
 * ---------------------------------------------------------------------------
 * 库的版本与取舍（2026-10-11 核实，不是照抄旧示例）
 *
 * 用的是 io.github.kyant0:backdrop **2.0.0**。2.0 与 1.0 的 API 完全不同：
 *   · 不再有 1.0 里那种"整树采集 + Region/DrawScope 风格"的旧 @Composable 容器；
 *   · 改成**三段式**：rememberLayerBackdrop() 记住一块背景层 ->
 *     Modifier.layerBackdrop(backdrop) 把内容录进这块层 ->
 *     Modifier.drawBackdrop(backdrop, shape, effects) 在浮层上按效果重绘这块层。
 * 参数形态取自 2.0.0 的源码（backdrop-android-2.0.0-sources.jar），不是 1.0 的旧示例。
 *
 * 依赖形状的硬约束：lens() 需要知道圆角半径，只认 CornerBasedShape（RoundedCornerShape
 * 等）或库自带的 RoundedRectangularShape；传别的形状会直接抛异常。所以形状参数由本层的
 * 默认实现给定，上层不要自己传奇怪的 Shape。
 *
 * 性能与降级：
 *   · blur 走 RenderEffect，需要 Android 12（API 31）；
 *   · lens 走 RuntimeShader，需要 Android 13（API 33）；
 *   两者不满足时库内部直接 return（见 Blur.kt / Lens.kt），不会崩，只是没有折射感。
 *   · 代价在 GPU：每帧要在离屏 layer 上重采样背景再做模糊/折射，属于持续合成开销。
 *     终端是长期插电常亮的设备，**效果和功耗的取舍要看实测**（ observed via 帧率/温度），
 *     所以这里保留了 [DeskGlass.ENABLED] 总开关。
 * ---------------------------------------------------------------------------
 */
object DeskGlass {

    /**
     * 总开关。false 时底栏退化为完全透明（其余布局一律不变），
     * 方便同一台机器上直接 A/B 对比"有/无玻璃"。
     */
    const val ENABLED: Boolean = true

    /** 玻璃容器圆角。做成"浮动胶囊"而不是贴边长条，四个边才有折射可看。 */
    val cornerRadius: Dp = 28.dp

    /** 背景模糊半径。官方示例是 4dp，这里按 3.5x 密度的桌面距离略放大。 */
    val blurRadius: Dp = 8.dp

    /** 折射的"高度"（从边缘往内算的作用范围）。官方示例 16dp。 */
    val refractionHeight: Dp = 16.dp

    /** 折射强度。官方示例 32dp。 */
    val refractionAmount: Dp = 32.dp

    /**
     * 玻璃表层的白色蒙版不透明度。纯粹为了可读性：背景越花，图标文字越看不清，
     * 官方教程把这个数单独列成一步（"Add surface for readability"）就是这个意思。
     * TODO(目视) [待校正]：深色背景上的实际观感需要在真机上微调。
     */
    val surfaceAlpha: Float = 0.12f
}

/**
 * 记住一块背景层；它同时决定了"玻璃背后到底能采到什么"。
 *
 * [onDraw] 里先画一层底色再画内容是有讲究的：只录内容的话，内容没覆盖到的区域是**透明**的，
 * 玻璃底下就会出现透明像素（官方教程把它单独做成一步，还配了张出错的截图）。
 * 补一层底色后，内容之外的地方至少是确定的颜色，玻璃才有东西可模糊。
 *
 * @param enabled false 时返回 null，调用方后续的两个 Modifier 会自动退化为空操作，
 *                调用点不需要写 if/else。
 */
@Composable
fun rememberDeskGlassBackdrop(
    enabled: Boolean = DeskGlass.ENABLED,
    background: Color,
    onDraw: (ContentDrawScope.() -> Unit)? = null,
): LayerBackdrop? {
    if (!enabled) return null
    return rememberLayerBackdrop {
        drawRect(background)
        val extra = onDraw
        if (extra != null) extra() else drawContent()
    }
}

/**
 * 把这段内容录进背景层。**必须只套"玻璃背后的内容"** ——
 * 不能把玻璃条自己也套进来，否则每帧会把上一帧模糊过的画面再录一遍，
 * 形成自我反馈、越糊越糊。这也是官方示例把 background 源单独挂在 NavHost 上的原因。
 */
fun Modifier.deskGlassSource(backdrop: LayerBackdrop?): Modifier =
    if (backdrop == null) this else this.layerBackdrop(backdrop)

/**
 * 玻璃表面。给需要浮在内容之上的那一层用（本项目的侧边栏）。
 *
 * 同一块 [backdrop] 可以被**多层**同时使用（侧栏整块 + 里面的选中指示块），
 * 只要两者都只是"读"这块层、没有谁再 `layerBackdrop` 录一遍，就不会形成
 * glass-on-glass 的自采样循环 —— 官方教程警告的是"在 drawBackdrop 之后又
 * layerBackdrop 同一块内容"（RenderThread SIGSEGV），那是 `exportedBackdrop`
 * 参数存在的理由。本工程不碰那个参数，靠**共用同一个源**绕开整类问题。
 *
 * @param surfaceColor 蒙版色：它决定可读性，深色主题下宜用白色低透明度。
 * @param alpha 蒙版不透明度。**这是拉出层次的主要手段**：同一块背景层上，
 *              数值越大越"亮"、越像一块实体玻璃。侧栏 0.12、选中指示块 0.26。
 * @param highlight 边缘高光。默认取库的 [Highlight.Default]。
 */
fun Modifier.deskGlassSurface(
    backdrop: LayerBackdrop?,
    shape: () -> Shape,
    surfaceColor: Color = Color.White,
    alpha: Float = DeskGlass.surfaceAlpha,
    highlight: () -> Highlight = { Highlight.Default },
): Modifier {
    if (backdrop == null) return this
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = shape,
        effects = {
            // 顺序照官方教程：先提饱和，再模糊，最后是边缘折射。
            vibrancy()
            blur(DeskGlass.blurRadius.toPx())
            lens(DeskGlass.refractionHeight.toPx(), DeskGlass.refractionAmount.toPx())
        },
        highlight = highlight,
        onDrawSurface = { drawRect(surfaceColor.copy(alpha = alpha)) },
    )
}

/**
 * 选中指示块的不透明度：比侧栏底色[DeskGlass.surfaceAlpha]高一倍多，
 * 这样"一块更亮的玻璃滑到哪一项后面"才看得出来。
 *
 * 为什么不用再叠一层模糊来区分：那需要 second backdrop（glass on glass），
 * 是本工程明确要避开的坑（见 [deskGlassSurface] 的类注释）。
 */
val DeskGlass.indicatorAlpha: Float get() = 0.26f

/**
 * 浮在**独立图层**之上的控件用的"玻璃外观"（静态近似）。
 *
 * 为什么不能用 [deskGlassSurface]：真玻璃靠 Compose 的背景层（`layerBackdrop`）
 * 把"玻璃背后是什么"录下来再重绘。而地图与导航画面走的是**独立 Surface/GL 图层**
 * （`MapView`、`AMapNaviView` 都是），Compose 的背景层采不到它们的像素 ——
 * 套上去只会得到一块"模糊了自家底色"的暗块，不是"模糊了地图"的玻璃。
 * 这不是推测：`:map` 的 AMapCanvas 早就因为同一原因关掉了玻璃，
 * DeskRoot 里也写着"MapView 走独立图层，Compose 的背景层采不到它的内容（会录成一片黑）"。
 *
 * ---------------------------------------------------------------------------
 * "地图浮层能不能做真高斯模糊" 的完整核实（2026-10-12，用户提出要加模糊）
 *
 * 结论：**当前结构下做不到**，且换 View 类型也救不回来。三条证据互相独立：
 *   1. `adb shell dumpsys SurfaceFlinger --list` 里有独立层
 *      `SurfaceView - com.qingke.desk/com.qingke.desk.MainActivity#1` ——
 *      地图是**打洞**的独立图层，它的像素不属于本窗口的合成内容，谁也采样不到；
 *   2. `adb shell dumpsys activity com.qingke.desk/.MainActivity` 的 `View Hierarchy`
 *      在 `com.amap.api.maps.MapView{0,0-2755,1440}` 这一级**终止**（没有更深子 View），
 *      与第 1 条吻合。（顺带更正一条旧说法：MapViewHost 注释里"高德 MapView 内部是
 *      TextureView"是当初未验证的推断，实测不成立。）
 *   3. 高德确实提供 `TextureMapView`（jar 里有，`extends FrameLayout`），但换过去也没用：
 *      `AndroidView` 的内容是在 `dispatchDraw` 阶段绘制的，而 Compose 的玻璃背景层
 *      是在 `onDraw` 期间录制的 —— 录的是"这一刻已画上去的东西"，
 *      **后画的子 View 录不进去**。这一条已被本项目实证过（第 168 行的"录成一片黑"）。
 *
 * 所以这块浮层的位置只有两种选择：静态近似（本函数），或者牺牲掉玻璃感换纯色实心板。
 * 用户抱怨的"看不清"属于**对比度**问题，不是"缺模糊"问题 —— 解法是把底做深
 * （[DeskGlass.overlayAlpha] 0.55 → 0.72）并补上磨砂的漫散射光感，而不是去伪造模糊。
 * ---------------------------------------------------------------------------
 *
 * 所以地图上的控件用这一套：**同一族圆角、同一档半透明、同一方向的内高光**，
 * 只是不做折射与模糊。观感上与侧栏那块真玻璃是同一个语言，
 * 这也是这类图层上唯一诚实的做法 —— 假装有模糊只会看起来像脏了一块。
 *
 * 用法（顺序不能换：阴影要画在裁剪之外，描边要画在圆角之内）：
 * ```
 * Modifier
 *     .shadow(6.dp, shape)
 *     .deskGlassOverlay(shape)
 * ```
 *
 * @param shape 圆角形状。与真玻璃一样由调用方给，保证同一屏里的圆角是同一套值。
 * @param alpha 半透明底的强度。浮在地图上要**比侧栏高**（0.12）—— 地图明暗变化大，
 *              太透会读不清字。
 * @param surfaceColor 默认**深色**（`Color.Black`）：整机是暗色主题、文字是浅色，
 *              用浅色玻璃的话浅字压在浅底上等于看不见。深色玻璃在白天浅色地图与
 *              夜间深色地图上都读得清，是唯一两头都成立的选择。
 */
fun Modifier.deskGlassOverlay(
    shape: Shape,
    alpha: Float = DeskGlass.overlayAlpha,
    surfaceColor: Color = Color.Black,
): Modifier = this
    .clip(shape)
    .background(surfaceColor.copy(alpha = alpha))
    // 磨砂的"光感"。真模糊做不到（原因见上），但"一块磨砂玻璃"和"一块纯色板"的
    // 区别主要不在模糊，而在**漫散射光**：上边缘受光、下边缘掠射。
    // 所以这里叠一层极淡的竖向白光（顶部 5%、底部 2%），代价是一次 drawRect，
    // 比做任何采样都便宜，而且不会随地图变色而失效。
    .drawBehind {
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = DeskGlass.frostSheenTop),
                0.5f to Color.Transparent,
                1f to Color.White.copy(alpha = DeskGlass.frostSheenBottom),
            ),
        )
    }
    .border(
        width = 1.dp,
        brush = Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.42f),
                Color.White.copy(alpha = 0.08f),
            ),
        ),
        shape = shape,
    )

/**
 * 地图浮层用的基础不透明度（**小控件**：搜索条这种一行条，里面只有图标 + 短文字）。
 *
 * 演进：0.55 → 0.72（2026-10-12 用户反馈"有点看不清"）。
 * 旧注释里"0.72 会发白、读不清"这个结论**只在配白色蒙版时成立** ——
 * 那版是 `Color.White.copy(alpha = 0.72)`，浅色蒙版 + 浅色文字当然糊成一团。
 * 现在蒙版是深色（[Color.Black]），提高不透明度只会让底更沉、文字更跳出来。
 * **"看不清"要往深里走，不是往透里走。**
 */
val DeskGlass.overlayAlpha: Float get() = 0.72f

/**
 * 地图浮层里**以文字为主**的面板用的不透明度（路线预览卡、搜索结果面板）。
 *
 * 为什么比 [overlayAlpha] 高这么多（0.72 → **0.96**）：这两种控件对小字号数字的
 * 可读性要求和小控件不是一回事。搜索条里只有"搜索目的地"五个字，透出一点地图只是
 * 让底不那么平；而预览卡里是 3 行 × 3 个**必须逐位比较**的数字（75.5 / 96.1 / 82.0 公里），
 * 背景每透出一块白色 POI 标注，就有几位数字的对比度掉到读不出来。
 *
 * 这个数是**量出来的**，不是调出来的。判据：卡片内部取一块**没有文字**的区域，
 * 数它的亮度假峰峰幅度 —— 那就是"地图标注穿进卡片"的可见强度。
 *
 * | 版本            | 幽灵峰峰 | 卡片底(中位) | 结果               |
 * | --------------- | -------- | ------------ | ------------------ |
 * | 0.72 无遮罩     | 35.0     | 45           | 标注可逐字读出     |
 * | 0.90 + 遮罩     | 7.0      | 42           | 仍隐约可辨（不够） |
 * | 0.96 + 遮罩     | ~3       | 42           | 压到可辨识阈值之下 |
 *
 * 模型很简单，可以直接推算，不用每次真机试：`幽灵幅度 = (1-alpha) × (1-遮罩) × 地图峰峰`。
 * 实测地图峰峰约 125，于是 0.72 时是 0.28×125=35（对上），0.90+0.34 时是
 * 0.10×0.66×125=8.25（实测 7，对上）。**要提高可读性，alpha 比遮罩有效得多** ——
 * 两者都是线性项，但遮罩会把用户想看的底图一起压掉，代价由别人承担。
 *
 * 代价说清楚：0.96 的底基本是实心的，"能透出地图"这个玻璃特征只剩一点色调暗示，
 * 玻璃感靠**边缘高光 + 竖向漫散射光**（[frostSheenTop] 等）交代。
 * 这里选择可读性 —— 一块看不清字的玻璃不叫质感，叫故障。
 */
val DeskGlass.overlayCardAlpha: Float get() = 0.96f

/**
 * 地图浮层出现"需要聚焦的文字块"时，盖在地图上的压暗遮罩强度。
 *
 * 解决的是 [overlayCardAlpha] 解决不了的另一半：卡片的**透出量**可以压到 10%，
 * 但卡片**周围**那张高对比地图本身还在抢注意力 —— 亮底 + 花字 POI 与卡片里的
 * 小字号数字在同一视野里争对比度，眼睛会一直往亮处跑。
 *
 * 地图产品在"选路线"这种要读数字的时刻都会把底图压暗（高德/百度/Google 都是），
 * 这是同一个道理，不是本项目发明的做法。
 *
 * 只压在**地图内容区**（[NaviSearchLayer] 铺满的范围）：侧栏是它的兄弟节点，
 * 不受影响 —— 侧栏常驻是用户明确的口径，遮罩不能把入口一起压暗。
 */
val DeskGlass.mapScrimAlpha: Float get() = 0.34f

/** 磨砂高光：上边缘受光。 */
val DeskGlass.frostSheenTop: Float get() = 0.05f

/** 磨砂高光：下边缘掠射。数值比上边缘小，光才是从上打下来的。 */
val DeskGlass.frostSheenBottom: Float get() = 0.02f
