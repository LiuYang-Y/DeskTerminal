package com.qingke.desk.shell

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.kyant.backdrop.backdrops.LayerBackdrop

data class ShellItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 侧边栏：竖向排列的大触控入口 + 一块会滑动的玻璃（透镜）选中指示块。
 *
 * 这个文件现在**只剩一个入口**：真正画东西的是 [LensSideBar]，本函数负责把
 * [LensSpec.ENABLED] 这个总开关接进来，别处（[com.qingke.desk.shell.DeskRoot]）
 * 的调用一行都不用改。
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
 * 玻璃：从"滑动指示块"升级成"玻璃透镜"，再升级成"内嵌胶囊 ↔ 按下水滴"（2026-10-15）
 *
 * 需求是酷安底部栏那种：选中项上罩一块比图标略大的镜片，镜内图标被放大并染成强调色、
 * 带折射与彩色边缘；按下/拖动时镜片再放大一点，抬手回弹吸附到最近一项。
 *
 * **2026-10-15 版把透镜做成"真光学采样"**：侧栏本体整块玻璃用 `exportedBackdrop`
 * 导出成 railBackdrop，水滴透镜作为**平级兄弟**采样它 —— 水滴里是被弯折的**侧栏本体像素**
 * （含穿进去弯折的左右边框线），而不是"采内容区"或"什么都不采"。
 * 三条要点都落在 [LensSideBar] 的文件头里，这里只留索引：
 *   · **单向 DAG**：内容区 →（本体读）railBackdrop →（水滴读）；水滴自身不再导出，
 *     杜绝"玻璃采玻璃"成环（RenderThread SIGSEGV）；
 *   · **导出不含子内容**：图标/文字不会进导出层，所以水滴里的图标只能**自绘一份**
 *     （`LensGlyph`）；而描边必须画在 `onDrawFront` 里才会进导出、才会在水滴里弯折；
 *   · **水滴大小**：静止 = 条目尺寸（收在栏内）；按下放大 1.26 倍、**允许鼓出侧栏边界**
 *     （这才是"左右边框线穿进水滴"的前提）。上一版那条"按下也不越界"的约束已删。
 *
 * 降级有三层，从粗到细：
 *   1. [LensSpec.ENABLED] 常量置 false —— 整份新样式关掉，回到改动前；
 *   2. 运行时按 [com.qingke.desk.core.platform.EffectTier] 分档（用户设置里的
 *      "高/中/低/关" + Governor 的温度降级，见 [toLensTier]）——
 *      高档真折射/真色散/真模糊，中档只有真模糊，低档全自绘；**关档连放大与描边都不画**，
 *      但**侧栏胶囊与选中块仍在**（静态纯色底）—— 关掉的是采样与合成开销，不是整条侧栏；
 *   3. 放大/染色/水滴外缘描边是纯自绘的，不依赖 RuntimeShader，低档以上都在，
 *      所以最坏情况也只是"没有折射和色散"，不会退化成看不出变化。
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
@Composable
fun SideBar(
    items: List<ShellItem>,
    selectedId: String?,
    onSelect: (ShellItem) -> Unit,
    glassBackdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    availableHeightDp: Float? = null,
    /**
     * 深色底衬模式（地图漫游态专用）。
     *
     * 地图漫游态下 MapPane 铺满整块物理窗口，侧栏浮在浅色/绿色地图之上；但地图是
     * 独立 SurfaceView 图层，Compose 的玻璃背景层采不到它 —— 侧栏真玻璃背后只剩
     * 自家底色，而 OFF/LOW 档的半透明白蒙版会透出浅色地图、白底压白图标看不清。
     *
     * true 时侧栏主体改用深色磨砂底衬（deskGlassOverlay 黑底），任何档位下都高对比。
     */
    darkUnderlay: Boolean = false,
) {
    LensSideBar(
        items = items,
        selectedId = selectedId,
        onSelect = onSelect,
        backdrop = glassBackdrop,
        lensEnabled = LensSpec.ENABLED,
        modifier = modifier,
        availableHeightDp = availableHeightDp,
        darkUnderlay = darkUnderlay,
    )
}
