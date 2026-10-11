package com.qingke.desk.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskGlass
import com.qingke.desk.core.ui.deskGlassSurface
import com.qingke.desk.core.ui.indicatorAlpha
import kotlinx.coroutines.launch
import kotlin.math.floor
import kotlin.math.roundToInt

data class ShellItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 侧边栏：竖向排列的大触控入口 + 一块会滑动的玻璃选中指示块。
 *
 * ---------------------------------------------------------------------------
 * 为什么是竖排、以及为什么是 5 项（2026-10-12 定稿）
 *
 * 横屏只有 361 dp 高。6 个入口竖排需要 6 × 64 = 384 dp，**物理上放不下**
 * （361 ÷ 6 = 60.2 dp 是理论上限，还没算间距）。三个出路里：
 *   · 缩小间距 —— 间距归零也只够 361/6 = 60.2 dp，仍然小于 64dp 底线；
 *   · 压小触控目标 —— 直接违背"手臂距离外也能一次点中"这条核心观感；
 *   · 减少入口 —— 5 × 64 + 4 × 6 + 2 × 6 = 356 dp ≤ 361，达标。
 *     （间距取 6 而不是 8：8 时整列 364 dp，比屏幕高 3 dp，第五项会被挤掉 10px；
 *      见 DeskDimens 文件头的"历史坑 4"。）
 * 选了第三条：砍掉**尚未实现**的「智能家居」（id 与分支都保留，见 [ShellIds.SMART_HOME]）。
 *
 * ---------------------------------------------------------------------------
 * 玻璃滑块（竖向改写自官方 Interactive Glass Bottom Bar）
 *
 * 库本身**与方向无关**：`drawBackdrop` 只认 shape，圆角是从 shape 的四角分别读的，
 * 所以竖向胶囊完全合法，不需要改库。竖向改写的真实工作量在手势与动画：
 *
 *  1. 位置用 `Animatable<Float>`，单位是"第几项"（0f..lastIndex），
 *     而不是像素 —— 条目尺寸变化（换机器、改常量）时不用重算动画。
 *  2. 位移走 `Modifier.offset { }` 的 **lambda 版本**：它作用在 placement 阶段，
 *     动画每帧只重新定位、**不触发重组**。若写成 `offset(y = state.value.dp)`，
 *     每帧都要重组整棵子树，玻璃每帧重建，帧率会塌。
 *  3. 拖拽跟手用 `snapTo`，松手再 `animateTo` 到就近的整数项；
 *     判定用 `roundToInt()` —— 拖过一半就算切过去，符合手指的直觉。
 *
 * **风险只有一个，且已绕开**：glass-on-glass 自采样。官方教程警告"在 drawBackdrop
 * 之后再 layerBackdrop 同一块内容会形成反馈循环，RenderThread 直接 SIGSEGV"。
 * 本实现的做法是让侧栏容器和指示块**共用同一个 LayerBackdrop 源**（内容区那块），
 * 两者都只"读"不"录"，指示块靠更高的 surface 蒙版不透明度
 * （[DeskGlass.indicatorAlpha]）拉出层次，而不是二次采样侧栏的渲染结果。
 * 循环不存在，`exportedBackdrop` 那个参数全程没碰。
 *
 * 其余已知代价：拖拽期间每帧都要重采样 + 模糊 + 折射（采样区约 64×64 dp），
 * 常亮设备必须实测帧率与温度；`DeskGlass.ENABLED` 总开关留着做降级。
 *
 * ---------------------------------------------------------------------------
 * 挖孔：**侧栏玻璃块不碰挖孔区**（2026-10-12 改定）
 *
 * 先试过让侧栏背景往挖孔那一条延伸过去、把它吃掉做成"一整块"。
 * 真机上看着像贴歪了：玻璃块的圆角不在屏幕边上，有一半压着一条纯装饰色带，
 * 侧栏框本身就显得是歪的。现在改成——挖孔条始终由外层单独铺色，
 * 侧栏框规规矩矩待在挖孔条**旁边**，两块各是各的。
 * 代价是屏幕上多一条竖直分界，换来的是两个元素的边界都干净。
 *
 * ---------------------------------------------------------------------------
 * 左右切换不在这里（2026-10-12 改）
 *
 * 曾经把"切到屏幕另一侧"挂在侧栏长按上，真机上误触太多：侧栏是**切屏**的主入口，
 * 而长按与单击共用同一根手指，想点"地图"却把侧栏整条甩到另一边的代价太大。
 * 现在改到设置浮层里（右上角齿轮 → 侧栏位置），侧栏只负责选中，不再有长按动作。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SideBar(
    items: List<ShellItem>,
    selectedId: String?,
    onSelect: (ShellItem) -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val stepPx = with(density) {
        (DeskDimens.sideBarItemSize + DeskDimens.sideBarItemGap).toPx()
    }
    val lastIndex = items.lastIndex

    // 第一项顶边距容器顶边的距离（内边距）。拖拽时要靠它把"手指按在哪"翻译成"第几项"。
    val innerPaddingPx = with(density) { DeskDimens.sideBarInnerPadding.toPx() }

    val selectedIndex = items.indexOfFirst { it.id == selectedId }.let {
        if (it < 0) 0 else it
    }

    // 位置单位是"第几项"的浮点值。范围钉死在 0..lastIndex，
    // 拖到边界外也不会滑出去 —— Animatable 的 updateBounds 只在这里设一次。
    val indicator = remember { Animatable(0f) }
    val indicatorState = remember(indicator) { indicator.asState() }
    val scope = rememberCoroutineScope()

    var dragging by remember { mutableStateOf(false) }
    val draggingNow by rememberUpdatedState(dragging)
    // pointerInput 的 lambda 只捕获一次，索引要读最新值，不能直接用 selectedIndex。
    val selectedIndexNow by rememberUpdatedState(selectedIndex)

    // 拖拽过程中的临时量。必须是 state：pointerInput 的 lambda 只捕获一次，
    // 普通局部变量读到的会是首次重组时的旧值。
    //
    // 位移用**按下点与当前点的绝对差**，不是逐帧累加 dragAmount ——
    // 累加会在事件被合并/丢弃时漏掉一段，手指走了 1.6 格却只记到 1.3 格，
    // 松手吸附就落到前一项上。绝对差不会累积误差。
    val dragOrigin = remember { mutableFloatStateOf(0f) }
    val dragDownY = remember { mutableFloatStateOf(0f) }
    val dragRaw = remember { mutableFloatStateOf(0f) }

    // 选中项变化 → 滑过去。拖拽中不接受外部跳转（否则手指会被抢走）。
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

    Box(
        modifier = modifier
            // 宽度固定：玻璃块**不含**挖孔那一条（挖孔条由外层单独铺色）。
            // 让它延伸过去盖住挖孔条的做法试过，真机上看着像贴歪了 ——
            // 圆角不在屏幕边上，左半边压着一条纯装饰色带。
            .width(DeskDimens.sideBarWidth)
            .deskGlassSurface(
                backdrop = glassBackdrop,
                shape = { RoundedCornerShape(DeskDimens.sideBarCorner) },
            )
            .padding(DeskDimens.sideBarInnerPadding)
            .pointerInput(items, stepPx) {
                detectVerticalDragGestures(
                    onDragStart = { down ->
                        dragging = true
                        // 基准**取手指按下那一项**，不能取"当前选中项"。
                        //
                        // 一开始写的是 dragOrigin = indicator.value（当前选中项），
                        // 真机上立刻暴露：选中停在第 4 项、手指从第 1 项往下拖两格，
                        // 结果算出来是 4 + 2 = 6 → 被夹到第 5 项（投屏），
                        // 直接把 DiPlay 拉起来了。手指按在哪一格，拖拽就该从那一格算起。
                        //
                        // 用 floor 不用 round：按在某一项的下半部分时，
                        // 用户仍然认为自己"按的是这一项"，round 会先跳一格。
                        val base = floor((down.y - innerPaddingPx) / stepPx)
                            .coerceIn(0f, lastIndex.toFloat())
                        dragOrigin.value = base
                        dragDownY.value = down.y
                        dragRaw.value = base
                        // 指示块先跟到手指按下那一项，随后跟随位移。
                        scope.launch { indicator.snapTo(base) }
                    },
                    onDragCancel = {
                        dragging = false
                        // 拖拽被打断（系统手势抢占、多指）：回到**当前选中项**，
                        // 而不是回到按下那一格 —— 按下那格只是拖拽基准，不一定是选中项。
                        scope.launch { indicator.animateTo(selectedIndexNow.toFloat()) }
                    },
                    onDragEnd = {
                        dragging = false
                        // 抬手才吸附：**选最近的一项**。
                        //
                        // 目标是 dirty-free 的：用最后一次算出的连续位置 [dragRaw]，
                        // 既不读 indicator.value（跟手的 snapTo 丢在协程里执行，
                        // 读到的不一定是最后一帧），也不在这里重算位移。
                        // 拖过一半就算切过去（roundToInt），符合手指的直觉。
                        val target = dragRaw.value
                            .roundToInt()
                            .coerceIn(0, lastIndex)
                        onSelect(items[target])
                        // 选中项没变时（拖回原格再松手）LaunchedEffect 不会触发，
                        // 这里兜底把指示块归位到整数格。两个动画打同一个目标，幂等。
                        scope.launch { indicator.animateTo(target.toFloat()) }
                    },
                    onVerticalDrag = { change, _ ->
                        // 位移 = 当前点 - 按下点，单位是"第几项"。
                        //
                        // 不用回调自带的 dragAmount 逐帧累加：它是"相对上一帧"的增量，
                        // 一旦事件被合并或丢弃就永久漏掉一段，手指走了 1.6 格只记到 1.3 格，
                        // 抬手就吸附到前一项去了。绝对差不会累积这种误差。
                        //
                        // **拖动过程中跟手连续，允许停在两格之间**（用户对两种做法都试过）：
                        // 试过每帧取整（一档一档吸附地跳），手感是"跳格"，
                        // 而且手指压在两项中间时指示块会来回抖。现在改回连续：
                        // 指示块跟着手指走，想停哪儿停哪儿，抬手那一刻才吸附。
                        dragRaw.value = (dragOrigin.value +
                            (change.position.y - dragDownY.value) / stepPx)
                            .coerceIn(0f, lastIndex.toFloat())
                        scope.launch { indicator.snapTo(dragRaw.value) }
                    },
                )
            },
    ) {
        // 指示块画在条目**下层**：它是"选中项后面的一块玻璃"。
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(0, (indicatorState.value * stepPx).roundToInt())
                }
                .size(DeskDimens.sideBarItemSize)
                .deskGlassSurface(
                    backdrop = glassBackdrop,
                    shape = { RoundedCornerShape(DeskDimens.sideBarIndicatorCorner) },
                    alpha = DeskGlass.indicatorAlpha,
                ),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(DeskDimens.sideBarItemGap),
        ) {
            items.forEach { item ->
                SideBarButton(
                    item = item,
                    selected = item.id == selectedId,
                    onClick = { onSelect(item) },
                )
            }
        }
    }
}

@Composable
private fun SideBarButton(
    item: ShellItem,
    selected: Boolean,
    onClick: (ShellItem) -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = Modifier
            .size(DeskDimens.sideBarItemSize)
            // 普通单击即可，不再挂长按（理由见 [SideBar] 的"左右切换不在这里"）。
            // indication/interactionSource 传空：侧栏条目被按住时不泛涟漪 ——
            // 那会让人以为按下会打开新的东西，而它只是切一屏。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onClick(item) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
