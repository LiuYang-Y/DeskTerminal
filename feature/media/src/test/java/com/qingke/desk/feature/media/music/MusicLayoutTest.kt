package com.qingke.desk.feature.media.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音乐页布局推导的**证伪测试**。
 *
 * ---------------------------------------------------------------------------
 * 为什么这个文件存在
 *
 * 项目硬约束："尺寸不写死"、"换不同尺寸的机器必须进回归清单 ——
 * 只在一台机器上验，这类问题永远发现不了"。
 *
 * 音乐页的每一处尺寸都是"可用空间 → 尺寸"的纯函数，所以它**可以**被穷举。
 * 只靠真机截图验的话，验过的只有那两台机器的两个点；而页面上真正要保证的
 * 是一条**关系**：歌词块不能压到 HUD、左右两块 HUD 不能相撞。
 * 关系可以在一个尺寸矩阵上被检验，而"看起来正常"不能。
 *
 * ---------------------------------------------------------------------------
 * 结论写成可被推翻的断言
 *
 * 每一条 `assertTrue` 都配了一句"它怎么才会假"。做不到证伪的地方在下面**明说**
 * （见 [理论边界的说明][theoreticalFloor]），不用"界面正常"这类不可推翻的措辞。
 */
class MusicLayoutTest {

    // ---------------------------------------------------------------------
    // 尺寸矩阵
    // ---------------------------------------------------------------------

    private data class Panel(val label: String, val w: Float, val h: Float)

    /** 一台机器在**内容区**里的可用空间（不是屏幕尺寸，理由见 [MusicLayout.metrics] 的注释）。 */
    private fun panel(label: String, w: Float, h: Float) = Panel(label, w, h)

    /**
     * 两台在役真机的内容区。**这两个点必须永远在矩阵里** ——
     * 它们是唯一"有硬件背书"的数据，其余尺寸都是外推。
     *
     * ```
     *   一加 PKR110   794 − 挖孔 40 − 侧栏 76 − 间距 12 = 666 × 361
     *   小米 11 Ultra 914 − 挖孔 39 − 侧栏 76 − 间距 12 = 787 × 411
     * ```
     */
    private val realDevices = listOf(
        panel("一加 PKR110", 666f, 361f),
        panel("小米 11 Ultra", 786f, 411f),
    )

    /**
     * 外推尺寸。不是随便取的数：
     *  · `wm size` 模拟出的 686×361（一加的高 + 更窄的宽）—— 真机上实际跑过；
     *  · 往两头各推一段，看比例在"比在役机器更极端"时会不会翻车。
     */
    private val extrapolated = listOf(
        panel("模拟窄屏 686×361", 686f, 361f),
        panel("极窄 420×361", 420f, 361f),
        panel("矮屏下限 666×320", 666f, 320f),
        panel("高屏 786×600", 786f, 600f),
        panel("超宽 1200×411", 1200f, 411f),
    )

    private val allPanels = realDevices + extrapolated

    /** 失败信息里带一整份实测值，免得还要回去手算"到底差了多少"。 */
    private fun MusicMetrics.dump(): String = buildString {
        append("w=$widthDp h=$heightDp\n")
        append("  pad=$pad cover=$coverSide\n")
        append("  lyricSize=$lyricSizeSp lineHeight=$lyricLineHeightSp block=$lyricBlockHeight\n")
        append("  lyricTop=$lyricTopY lyricBottom=$lyricBottomY\n")
        append("  hudTop=$hudTopY gap=${hudTopY - lyricBottomY} ")
        append("(要求 >= ${MusicLayout.LYRIC_HUD_MIN_GAP})\n")
        append("  leftBlockEnd=${pad + coverSide + coverTextGap + hudTextMaxWidth} ")
        append("rightBlockStart=${widthDp - pad - hudColumnWidth}")
    }

    // ---------------------------------------------------------------------
    // 1. 主不变式：歌词块不压 HUD
    // ---------------------------------------------------------------------

    /**
     * 怎么才假：`lyricBottomY > hudTopY − 8`。
     * 也就是歌词块的下沿越过 HUD 上沿 8 dp 以上 —— 真机上会看到歌词压住封面/进度条。
     */
    @Test
    fun `矩阵内每个尺寸的歌词块都不与 HUD 相撞`() {
        allPanels.forEach { p ->
            val m = MusicLayout.metrics(p.w, p.h)
            assertTrue(
                "${p.label} 歌词块压住了 HUD：\n${m.dump()}",
                m.lyricClearsHud,
            )
        }
    }

    /**
     * 怎么才假：把 [MusicLayout.LYRIC_SIZE_MIN] 调大、或把 `LYRIC_LINE_HEIGHT_EM` / `LYRIC_GAP_EM`
     * 调大、或让 HUD 那条带变高（如增大 `PROGRESS_TOUCH_RATIO`）—— 任一改动都会把这条压红。
     *
     * 为什么单独钉 320 dp：那是**实测出来的理论边界上方最近的一档**。
     * 见 [theoreticalFloor]。
     */
    @Test
    fun `高度下探到 320dp 时仍留有余量`() {
        listOf(666f, 786f).forEach { w ->
            val m = MusicLayout.metrics(w, 320f)
            assertTrue("$w×320 已越界：\n${m.dump()}", m.lyricClearsHud)
            // 有余量 != 恰好卡上：这里要求至少 4 dp 的富余，否则 320 这一档等于没有。
            val margin = m.hudTopY - m.lyricBottomY
            assertTrue("$w×320 余量只有 $margin dp，太薄：\n${m.dump()}", margin >= 12f)
        }
    }

    /**
     * <a name="theoreticalFloor"></a>
     * **扫出真实边界**，并把结论钉死。
     *
     * 上面两条只验了"320 dp 站得住"，但**没说边界在哪**。这一条用扫描把边界找出来：
     * 从 200 dp 往上找第一个满足不变式的高度。写死一个区间断言（必须落在 300±2）
     * 是为了让**任何改动比例的人立刻知道边界被挪动了** —— 挪动本身不是错，
     * 但一次无意识的挪动（比如为了别的目的调了 `HUD_TEXT_LINE_HEIGHT_EM`）
     * 会让矮屏机器静默失守，那条路径必须有人过目。
     *
     * 当前实测边界：**301 dp**（300 不满足，301 起满足）。
     * 这个数远低于在役机器的 361 / 411，所以**今天不是 bug，只是余量记录**。
     *
     * 它怎么才假：任何人改了 HUD 带高、歌词字号/行高/行距、或 pad 比例。
     */
    @Test
    fun `理论碰撞边界应落在 301dp 附近`() {
        val floor = (200..600).first { h ->
            MusicLayout.metrics(666f, h.toFloat()).lyricClearsHud
        }
        assertEquals(
            "歌词与 HUD 的碰撞边界被挪动了。在役机器内容区高 361/411，" +
                "当前边界远低于它们，所以这次改动**未必**是 bug —— 但请确认是有意的，" +
                "并把新边界写回本测试的注释里。",
            301L,
            floor.toLong(),
        )
    }

    // ---------------------------------------------------------------------
    // 2. 左右两块 HUD 不交叠
    // ---------------------------------------------------------------------

    /**
     * 怎么才假：左下那一块的右端越过右下控制列的左端。
     * 左侧占位 = pad + 封面 + 缝 + 文字最大宽；右侧起点 = widthDp − pad − 控制列宽。
     */
    @Test
    fun `矩阵内每个尺寸的左右 HUD 都不交叠`() {
        allPanels.forEach { p ->
            val m = MusicLayout.metrics(p.w, p.h)
            assertTrue("${p.label} 左右 HUD 交叠：\n${m.dump()}", m.hudBlocksDoNotOverlap)
        }
    }

    /**
     * 这条和上面那条**不是同一件事**：上面说"在役尺寸没问题"，
     * 这条说"窄到什么程度才出问题"。
     *
     * 怎么才假：`HUD_TEXT_RATIO` / `HUD_COLUMN_RATIO` / `COVER_MAX` 被调大。
     * 当前实测边界 **289 dp 宽**（288 不满足，289 满足）。
     */
    @Test
    fun `理论交叠边界应落在 289dp 附近`() {
        val floor = (200..900).first { w ->
            MusicLayout.metrics(w.toFloat(), 361f).hudBlocksDoNotOverlap
        }
        assertEquals(
            "左右 HUD 的交叠边界被挪动了（在役机器内容区宽 666/786，远在其之上）。" +
                "请确认是有意的，并把新边界写回本测试的注释里。",
            289L,
            floor.toLong(),
        )
    }

    // ---------------------------------------------------------------------
    // 3. 比例内部的一致性（"两套阈值互相打架"的反例）
    // ---------------------------------------------------------------------

    /**
     * **这是本项目最看重的一类反例**：两套阈值互相打架。
     *
     * 行高**必须**由夹取后的字号回算。若有人先按 `h × LYRIC_SIZE_RATIO` 算行高、
     * 再夹字号（顺序颠倒），在矮屏上会得到"字被夹小了、行距还是按大字算的"：
     * 字很小、行很宽，而且**看不出来是算错了**。
     *
     * 怎么才假：任何版本改成"先算行高再夹字号"。
     * 造一个超高屏（1200 dp）让 `h × 0.086` 远超上限 32 sp：
     *  · 正确实现行高 = 32 × 1.30 = 41.6
     *  · 顺序颠倒则行高 = 1200 × 0.086 × 1.30 = 134.16 —— 差 3 倍，一眼可判。
     */
    @Test
    fun `行高由夹取后的字号回算`() {
        val tall = MusicLayout.metrics(786f, 1200f)
        assertEquals(
            "字号没有被上限夹住，这个测试的前提就不成立了",
            MusicLayout.LYRIC_SIZE_MAX,
            tall.lyricSizeSp,
            1e-3f,
        )
        assertEquals(
            "行高不是从夹取后的字号算出来的（顺序颠倒了）",
            MusicLayout.LYRIC_SIZE_MAX * MusicLayout.LYRIC_LINE_HEIGHT_EM,
            tall.lyricLineHeightSp,
            1e-3f,
        )

        // 另一端：矮到让字号撞下限，行高同样要跟着下限走。
        val short = MusicLayout.metrics(786f, 200f)
        assertEquals(
            "字号没有被下限夹住，这个测试的前提就不成立了",
            MusicLayout.LYRIC_SIZE_MIN,
            short.lyricSizeSp,
            1e-3f,
        )
        assertEquals(
            "行高没有跟着下限字号走",
            MusicLayout.LYRIC_SIZE_MIN * MusicLayout.LYRIC_LINE_HEIGHT_EM,
            short.lyricLineHeightSp,
            1e-3f,
        )
    }

    /**
     * HUD 左边那三行文字（歌名 / 歌手 / 导入按钮）加起来**必须矮于封面**。
     *
     * 为什么在**比例层面**验而不是逐尺寸验：三行总高 = 封面 × (Σ比例 × 行高em + 2 × 间距比例)，
     * 展开后是一个与尺寸无关的常数比 —— 也就是说这条不变式**要么在所有尺寸上成立、
     * 要么在所有尺寸上不成立**。逐尺寸断言会给出"全都过"的假安全感，
     * 却说不清"离翻车还有多远"。
     *
     * 当前：0.68 × 1.25 + 2 × 0.04 = 0.93 —— 离 1.0 还有 7% 余量。
     *
     * 怎么才假：`HUD_TEXT_LINE_HEIGHT_EM` 抬到 1.35 以上，或三个字号比例加起来被调大。
     * 症状是左下的文字块比封面还高，把整条 HUD 的预算又往上顶一截。
     */
    @Test
    fun `HUD 三行文字的合计高度必须小于封面`() {
        val ratio = (MusicLayout.TITLE_SIZE_RATIO +
            MusicLayout.ARTIST_SIZE_RATIO +
            MusicLayout.CHIP_SIZE_RATIO) * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM +
            2f * MusicLayout.HUD_TEXT_GAP_RATIO
        assertTrue(
            "HUD 三行文字合计 $ratio 倍封面高度，已经不低于封面（必须 < 1.0）",
            ratio < 1f,
        )
    }

    /**
     * 上一首/下一首键必须**小于**主键，否则三颗按钮看起来一样大，
     * 播放/暂停就不再是视觉主角。
     *
     * 怎么才假：`SIDE_CONTROL_RATIO` 被调到 >= 1。
     */
    @Test
    fun `两侧控制键小于主键且图标小于按钮`() {
        allPanels.forEach { p ->
            val m = MusicLayout.metrics(p.w, p.h)
            assertTrue(
                "${p.label}: sideControl=${m.sideControlSize} 应 < control=${m.controlSize}",
                m.sideControlSize < m.controlSize,
            )
            assertTrue(
                "${p.label}: icon=${m.controlIconSize} 应 < control=${m.controlSize}",
                m.controlIconSize < m.controlSize,
            )
            // 进度条的触控高度必须盖得住可视高度，否则"拖得动"是无源之水。
            assertTrue(
                "${p.label}: progressTouch=${m.progressTouchHeight} 应 >= progress=${m.progressHeight}",
                m.progressTouchHeight >= m.progressHeight,
            )
        }
    }

    // ---------------------------------------------------------------------
    // 4. 圆环半径：两台机器上"哪个方向先撞线"是不同的
    // ---------------------------------------------------------------------

    /**
     * 半径 = `min(W × 0.38, H × 0.72)`。这条测试把"到底哪个方向在约束"写清楚。
     *
     * 它有点反直觉，值得钉住：**两台在役机器上约束方向是相反的**。
     *  · 一加 666×361：宽向 253.1 < 高向 259.9 → **宽在约束**
     *  · 小米 786×411：宽向 298.7 > 高向 295.9 → **高在约束**
     *
     * 也就是说，如果哪天有人图省事只留一个比例（比如取 `W × 0.38`），
     * 小米上半径会从 295.9 涨到 298.7 —— 只差 2.8 dp，**眼睛绝对看不出来**，
     * 但它在"矮屏"这条路径上是错的；反过来取 `H × 0.72` 则一加会多出 6.8 dp。
     * 这就是"取 min"不能被简化掉的原因。
     *
     * 怎么才假：`min` 被换成单边比例。
     */
    @Test
    fun `圆环半径取宽高比例的较小者`() {
        val onePlus = MusicLayout.metrics(666f, 361f)
        assertEquals(
            "一加：应当由宽向约束（W×0.38 < H×0.72）",
            666f * MusicLayout.WAVE_WIDTH_RATIO,
            onePlus.waveMaxRadius,
            1e-3f,
        )
        assertTrue(
            "一加：宽向本应小于高向，否则这个测试的前提不成立",
            666f * MusicLayout.WAVE_WIDTH_RATIO < 361f * MusicLayout.WAVE_HEIGHT_RATIO,
        )

        val xiaomi = MusicLayout.metrics(786f, 411f)
        assertEquals(
            "小米：应当由高向约束（H×0.72 < W×0.38）",
            411f * MusicLayout.WAVE_HEIGHT_RATIO,
            xiaomi.waveMaxRadius,
            1e-3f,
        )
        assertTrue(
            "小米：高向本应小于宽向，否则这个测试的前提不成立",
            411f * MusicLayout.WAVE_HEIGHT_RATIO < 786f * MusicLayout.WAVE_WIDTH_RATIO,
        )
    }

    // ---------------------------------------------------------------------
    // 5. 输入退化不能崩
    // ---------------------------------------------------------------------

    /**
     * `BoxWithConstraints` 在某些时刻（首帧、窗口 resize、侧栏切换）会给出 0 或负的约束。
     * [MusicLayout.metrics] 里 `coerceAtLeast(1f)` 兜住了它，这一条把那个兜底钉住。
     *
     * 怎么才假：把 `coerceAtLeast(1f)` 删掉 —— 会出现 NaN（0/0）或负尺寸，
     * 而负尺寸传给 `Modifier.size` 会直接抛异常。
     */
    @Test
    fun `零尺寸与负尺寸不产生 NaN 或负值`() {
        listOf(0f to 0f, -1f to -1f, 0f to 361f, 666f to 0f).forEach { (w, h) ->
            val m = MusicLayout.metrics(w, h)
            listOf(
                "pad" to m.pad,
                "cover" to m.coverSide,
                "lyricSize" to m.lyricSizeSp,
                "lyricLineHeight" to m.lyricLineHeightSp,
                "watermark" to m.watermarkSizeSp,
                "wave" to m.waveMaxRadius,
            ).forEach { (name, v) ->
                assertTrue("$w×$h 的 $name = $v，既不能是 NaN 也不能为负", v.isFinite() && v >= 0f)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 登录浮层（Phase 1，2026-10-14）
    //
    // 与上面同一套思路：浮层尺寸也是"可用空间 → 尺寸"的纯函数，所以同样可以被穷举。
    // 它要保证的关系只有一条：**卡片守得住上下安全区与左右边界**。
    // ---------------------------------------------------------------------

    private fun LoginPaneMetrics.dump(): String = buildString {
        append("w=$widthDp h=$heightDp\n")
        append("  outerY=$outerY padX=$padX padY=$padY\n")
        append("  cardW=$cardW cardH=$cardH qrSide=$qrSide\n")
        append("  title=$titleSizeSp status=$statusSizeSp btnH=$buttonHeight\n")
        append("  chrome=$cardH−$qrSide\n")
        append("  约束：cardH+2*outerY=${cardH + outerY * 2}（可用 $heightDp）")
    }

    /**
     * 矩阵内每个尺寸，浮层都要落在安全区之内。
     *
     * 怎么才假：把 [MusicLayout.LOGIN_QR_WIDTH_RATIO] 调小、或把标题/按钮比例调大，
     * 使 `qrBudget` 触到 [MusicLayout.LOGIN_MIN_QR] 的下限 —— 那时 `cardH + 2*outerY > h`，
     * 卡片会压到上下边缘。
     *
     * 实测这条边界的**位置**：解出 `qrBudget = 0.66h − 48` 可知 `h ≈ 150dp` 时触下限 ——
     * 远低于矩阵里最矮的 320dp。所以这条断言在现有矩阵上应当全绿，
     * 而它红的时刻就是"有人把某个比例改大了"。
     */
    @Test
    fun `矩阵内每个尺寸的登录浮层都不越出安全区`() {
        allPanels.forEach { p ->
            val m = MusicLayout.loginPane(p.w, p.h)
            assertTrue(
                "${p.label} ${p.w}×${p.h} 的登录浮层越界：\n${m.dump()}",
                m.fitsInside,
            )
        }
    }

    /**
     * 二维码边长必须**由高度预算决定**，而不是被宽度兜底决定。
     *
     * 这是设计意图的断言，不是自明的事实：横屏矮屏上唯一紧的约束是高度，
     * 若哪天 `w × 0.34` 比 `qrBudget` 还小，说明宽度比例被改小了 ——
     * 那时二维码会白白变小，而"看起来还行"发现不了。
     *
     * 怎么才假：把 [MusicLayout.LOGIN_QR_WIDTH_RATIO] 降到 0.30 以下
     * （一加 666×361 上 `qrBudget = 191`，`666 × 0.30 = 199.8`，仍大于 191；
     * 要降到 0.28 才会反过来 —— 0.34 → 0.28 是一次真实可发生的误改）。
     */
    @Test
    fun `在役机器上二维码边长由高度预算决定`() {
        realDevices.forEach { p ->
            val m = MusicLayout.loginPane(p.w, p.h)
            val widthFallback = p.w * MusicLayout.LOGIN_QR_WIDTH_RATIO
            assertTrue(
                "${p.label}：qrSide=${m.qrSide} 宽度兜底=$widthFallback，" +
                    "说明主约束已经变成宽度，二维码被白缩小了\n${m.dump()}",
                m.qrSide < widthFallback,
            )
        }
    }

    /**
     * 二维码不得小于可扫性下限。
     *
     * 在矩阵里它恒成立（最矮 320dp 时 `qrSide ≈ 164`），这一条守的是**将来**：
     * 若有人加进来一个 200dp 高的极端尺寸，这条会先红，
     * 而不是等到真机上出现一个扫不动的二维码。
     */
    @Test
    fun `二维码边长不低于可扫性下限`() {
        allPanels.forEach { p ->
            val m = MusicLayout.loginPane(p.w, p.h)
            assertTrue(
                "${p.label} 的二维码只有 ${m.qrSide}dp，低于下限 ${MusicLayout.LOGIN_MIN_QR}dp\n${m.dump()}",
                m.qrSide >= MusicLayout.LOGIN_MIN_QR,
            )
        }
    }

    /**
     * 按钮高度不得低于触控下限 48dp。
     *
     * 这一条防的是一个很具体的坑：矮屏上 `h × 0.115` 会小于 48（361dp 时是 41.5），
     * 若少了 `maxOf`，按钮会缩到 41.5dp —— 在车上单手点二维码旁边的按钮会变得难受。
     * 怎么才假：把 [MusicLayout.LOGIN_BUTTON_MIN] 从 `maxOf` 里拿掉。
     */
    @Test
    fun `浮层按钮高度不低于触控下限`() {
        allPanels.forEach { p ->
            val m = MusicLayout.loginPane(p.w, p.h)
            assertTrue(
                "${p.label} 的按钮只有 ${m.buttonHeight}dp，低于 48dp 触控下限\n${m.dump()}",
                m.buttonHeight >= 48f,
            )
        }
    }

    /** 浮层也要扛得住 0 / 负约束（首帧、resize），与音乐页同一条兜底。 */
    @Test
    fun `零尺寸下浮层不产生 NaN 或负值`() {
        listOf(0f to 0f, -1f to -1f, 0f to 361f, 666f to 0f).forEach { (w, h) ->
            val m = MusicLayout.loginPane(w, h)
            listOf(
                "outerY" to m.outerY,
                "padX" to m.padX,
                "padY" to m.padY,
                "cardW" to m.cardW,
                "cardH" to m.cardH,
                "qrSide" to m.qrSide,
                "titleSize" to m.titleSizeSp,
                "buttonHeight" to m.buttonHeight,
            ).forEach { (name, v) ->
                assertTrue("$w×$h 的 $name = $v，既不能是 NaN 也不能为负", v.isFinite() && v >= 0f)
            }
        }
    }

    // ---------------------------------------------------------------------
    // 选歌抽屉（Phase 1 第 2 步，2026-10-14）
    //
    // 与上面同一套思路：抽屉尺寸也是"可用空间 → 尺寸"的纯函数，同样可以被穷举。
    // 它要保证的关系是：**抽屉不超宽、行内元素不自相矛盾、每屏至少 3 行**。
    // ---------------------------------------------------------------------

    private fun DrawerMetrics.dump(): String = buildString {
        append("w=$widthDp h=$heightDp\n")
        append("  outerY=$outerY drawerW=$drawerW\n")
        append("  rowH=$rowH rowGap=$rowGap cover=$coverSide\n")
        append("  title=$titleSizeSp artist=$artistSizeSp visibleRows=$visibleRows")
    }

    /**
     * 矩阵内每个尺寸，抽屉都要守得住边界。
     *
     * 怎么才假：把 [MusicLayout.DRAWER_COVER_RATIO] 调到 ≥1（封面超过行高），
     * 或把 [MusicLayout.DRAWER_TITLE_RATIO] 调到超过行高。
     * 注意 `drawerW ≤ w` 在**极窄**（w < 约 523dp）时会假 —— 那是 220dp 下限本身
     * 大于内容区宽。矩阵里最窄的 420dp 仍然成立；更窄的屏（< 523dp 内容区）
     * 需要的是把侧栏收起来，不是把抽屉压成一条线。这条断言红的时刻就是提示那句话。
     */
    @Test
    fun `矩阵内每个尺寸的抽屉都不越界`() {
        allPanels.forEach { p ->
            val m = MusicLayout.trackDrawer(p.w, p.h)
            assertTrue("${p.label} ${p.w}×${p.h} 的抽屉越界：\n${m.dump()}", m.fitsInside)
        }
    }

    /**
     * 在役两台机器上的**逐项精确值**（设计文档 §6.3 代入原值）。
     *
     * 怎么才假：任何人改了 §6.3 的任一项比例。改比例本身不是 bug，
     * 但"文档表格与实现漂开"是 —— 这个测试就是钉住"文档里的数必须还能算出来"。
     */
    @Test
    fun `在役机器上抽屉尺寸与设计文档逐项相等`() {
        val onePlus = MusicLayout.trackDrawer(666f, 361f)
        assertEquals("一加 drawerW", 279.72f, onePlus.drawerW, 1e-2f)
        assertEquals("一加 rowH（被 48 下限托住）", 48f, onePlus.rowH, 1e-3f)
        assertEquals("一加 cover", 34.56f, onePlus.coverSide, 1e-2f)
        assertEquals("一加 title", 14.4f, onePlus.titleSizeSp, 1e-2f)
        assertEquals("一加 artist", 11.232f, onePlus.artistSizeSp, 1e-2f)
        assertEquals("一加 每屏行数", 6, onePlus.visibleRows)

        val xiaomi = MusicLayout.trackDrawer(786f, 411f)
        assertEquals("小米 drawerW", 330.12f, xiaomi.drawerW, 1e-2f)
        assertEquals("小米 rowH", 53.43f, xiaomi.rowH, 1e-2f)
        assertEquals("小米 cover", 38.4696f, xiaomi.coverSide, 1e-2f)
        assertEquals("小米 title", 16.029f, xiaomi.titleSizeSp, 1e-2f)
        assertEquals("小米 artist", 12.50262f, xiaomi.artistSizeSp, 1e-2f)
        assertEquals("小米 每屏行数", 6, xiaomi.visibleRows)
    }

    /**
     * 行高必须被 48dp 触控下限托住。
     *
     * 矮屏（361dp）上 `h × 0.13 = 46.93` 本来低于下限 —— 没有 `maxOf` 的话
     * 行高会变成 46.93，封面 33.8dp、标题 14.1sp：**眼睛看不出来，但手指头知道**。
     * 怎么才假：把 `maxOf(MIN_TOUCH_DP, …)` 里的下限拿掉。
     */
    @Test
    fun `矮屏上行高不低于触控下限`() {
        listOf(666f to 361f, 666f to 320f, 786f to 300f).forEach { (w, h) ->
            val m = MusicLayout.trackDrawer(w, h)
            assertTrue("$w×$h 的 rowH=${m.rowH} 低于 ${MusicLayout.MIN_TOUCH_DP}dp", m.rowH >= MusicLayout.MIN_TOUCH_DP)
        }
        // 高屏上反过来：比例接管，行高随高度长（不能永远钉在 48）。
        val tall = MusicLayout.trackDrawer(786f, 600f)
        assertTrue("600dp 高时 rowH 仍是下限：${tall.rowH}", tall.rowH > MusicLayout.MIN_TOUCH_DP)
    }

    /**
     * 每屏行数的下限兜底。
     *
     * 怎么才假：把 `coerceAtLeast(DRAWER_MIN_VISIBLE_ROWS)` 拿掉，
     * 且构造一个 `outerY×2` 几乎吃掉整块高度的尺寸（矮屏 + 大 outerY 不可能，
     * 但退化输入可以 —— 见下一条）。
     */
    @Test
    fun `每屏行数不低于 3`() {
        allPanels.forEach { p ->
            val m = MusicLayout.trackDrawer(p.w, p.h)
            assertTrue("${p.label} 每屏只有 ${m.visibleRows} 行", m.visibleRows >= MusicLayout.DRAWER_MIN_VISIBLE_ROWS)
        }
    }

    /** 抽屉也要扛得住 0 / 负约束（首帧、resize），与浮层同一条兜底。 */
    @Test
    fun `零尺寸下抽屉不产生 NaN 或负值`() {
        listOf(0f to 0f, -1f to -1f, 0f to 361f, 666f to 0f).forEach { (w, h) ->
            val m = MusicLayout.trackDrawer(w, h)
            listOf(
                "outerY" to m.outerY,
                "drawerW" to m.drawerW,
                "rowH" to m.rowH,
                "rowGap" to m.rowGap,
                "cover" to m.coverSide,
                "title" to m.titleSizeSp,
                "artist" to m.artistSizeSp,
            ).forEach { (name, v) ->
                assertTrue("$w×$h 的 $name = $v，既不能是 NaN 也不能为负", v.isFinite() && v >= 0f)
            }
            assertTrue("$w×$h 的 visibleRows=${m.visibleRows} 低于下限", m.visibleRows >= 3)
        }
    }
}
