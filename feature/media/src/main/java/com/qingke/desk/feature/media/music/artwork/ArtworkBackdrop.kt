package com.qingke.desk.feature.media.music.artwork

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Color as AColor

/**
 * 一首歌的视觉基调：**背景渐变的两个颜色** + 底图。
 *
 * 之所以不是一个颜色：单色渐变在 900 dp 宽的屏上会显得很平，
 * 参考图那种"有层次的暗场"至少需要两个色相相近但明度不同的点。
 */
data class ArtworkVisual(
    /** 背景渐变的起点（最深的一头）。 */
    val base: Color,
    /** 背景渐变的落点（偏亮、带色彩的那一头）。 */
    val accent: Color,
    /** 缩小 + CPU 模糊后的底图。**没有封面时为 null**，页面此时纯用渐变。 */
    val backdrop: ImageBitmap?,
)

/**
 * 从封面里取色 + 做一张廉价的模糊底图。
 *
 * ---------------------------------------------------------------------------
 * 为什么是"缩小再模糊"而不是 RenderEffect / AGSL
 *
 * 当前在用的验证机是**小米 11 Ultra / Android 11 / API 30**。
 *   · `RenderEffect`（Compose 的 `Modifier.blur`）要 API 31 —— 在它上面是**静默无效果**；
 *   · `AGSL`（RuntimeShader）要 API 33 —— 更够不到。
 * 项目已经为这件事吃过一次亏（见 `EffectTier` 的注释：库在低版本上直接 return，不崩不报）。
 *
 * 所以底图走 CPU：
 *   1. 先把封面**缩到长边 [WORK_SIZE_PX]** —— 这一步本身就是最强的"模糊"，
 *      因为信息量被砍掉了两个数量级；
 *   2. 在这张小图上做几轮盒式模糊（[BLUR_RADIUS_PX]）；
 *   3. 结果交给 GPU 按双线性放大铺满全屏 —— 放大时的插值会把剩下的小方块抹平。
 *
 * 代价是"一次性的 CPU 计算"，而不是"每帧的 GPU 采样"。这正是"每首歌只算一次"的意思：
 * 它挂在曲目身份上，切歌才重算，播放中一分钱不花。
 *
 * ---------------------------------------------------------------------------
 * 白字可读性是**构造保证**的，不是调出来的
 *
 * 整个页面用的是浅色文字压在渐变上。封面可能是纯白专辑（很高明度），
 * 照搬它的颜色会得到"白字压浅底"——那是可读性事故，不是风格。
 * 所以取色结果一律过 [darkenForReadability]：把明度压到 [MAX_BACKGROUND_VALUE] 以下。
 * 于是**无论封面多亮，背景一定是暗的**，不需要在页面里再补一层压暗遮罩
 * （补遮罩会把渐变本身的色彩也一起压掉）。
 */
object ArtworkBackdrop {

    /** 底图工作尺寸（长边，像素）。48 已经足够 —— 它最终会被放大到 2000+ px。 */
    private const val WORK_SIZE_PX = 48

    /** 盒式模糊半径（在 [WORK_SIZE_PX] 那张小图上）。 */
    private const val BLUR_RADIUS_PX = 5

    /** 取色用的采样尺寸。取色不需要细节，32×32 已经能反映主色调。 */
    private const val SAMPLE_SIZE_PX = 32

    /** 背景明度上限。超过它就压下来 —— 见类注释。 */
    private const val MAX_BACKGROUND_VALUE = 0.46f

    /** 背景饱和度下限。太低会变成灰，失去"从封面取色"的意义。 */
    private const val MIN_BACKGROUND_SATURATION = 0.22f

    /**
     * 算一首歌的视觉基调。**必须在后台线程调用**（有 Bitmap 缩放与逐像素运算）。
     *
     * @param artwork 封面。null 时只用 [seed] 生成一对确定的颜色（同一首歌每次一样）。
     * @param seed 没有封面时的取色种子（用曲目的 frameKey）。必须是稳定值 ——
     *             用随机数的话每次进页面背景都在变，看起来像坏了。
     */
    fun compute(artwork: Bitmap?, seed: String): ArtworkVisual {
        // 即使有封面，也用"封面主色"与"种子色"混一下：单张封面偶尔会取到
        // 近乎无彩度的颜色（黑白封面），混一点种子色能让画面总有一点色彩倾向。
        val seedHue = hueOf(seed)
        val (accentArgb, backdrop) = if (artwork != null) {
            // 只对**封面取到的主色**提纯：`dominantColor` 失败时才回退到种子色，
            // 而种子色本来就是"没有封面时的确定性替代"，不该被封面那条整形规则再动一次
            // （否则"有封面/没封面"两首歌的种子色会走出两种结果）。
            val acc = dominantColor(artwork)?.let(::purifySaturation) ?: seedColor(seedHue)
            acc to blurredBackdrop(artwork)
        } else {
            seedColor(seedHue) to null
        }

        val accent = darkenForReadability(accentArgb)
        val base = darkenForReadability(shiftHue(accentArgb, HUE_SHIFT_DEGREES), extraValueScale = 0.55f)

        return ArtworkVisual(base = base, accent = accent, backdrop = backdrop?.asImageBitmap())
    }

    // ------------------------------------------------------------------ 底图

    /**
     * 缩到 [WORK_SIZE_PX] 后再模糊。
     *
     * 缩放用 `createScaledBitmap(..., filter = true)`：双线性会被保留下来，
     * 于是缩小这一步本身就带一点低通 —— 比 `filter = false` 的最近邻好得多
     * （后者会在放大后显出原图的网格）。
     */
    private fun blurredBackdrop(source: Bitmap): Bitmap? {
        val scaled = runCatching {
            val maxSide = maxOf(source.width, source.height)
            if (maxSide <= 0) return null
            val scale = WORK_SIZE_PX.toFloat() / maxSide.toFloat()
            val w = (source.width * scale).toInt().coerceAtLeast(1)
            val h = (source.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, w, h, true)
        }.getOrNull() ?: return null

        val w = scaled.width
        val h = scaled.height
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurred = boxBlur(boxBlur(pixels, w, h, BLUR_RADIUS_PX), w, h, BLUR_RADIUS_PX)

        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(blurred, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }

    /**
     * 两轮盒式模糊（横 + 竖各一轮）。
     *
     * 在 48×48 上做朴素卷积：48×48×11×2 ≈ 50k 次加法，微秒级。
     * 用不着积分图那种优化 —— 那是给大图准备的，在这里只会让代码更难读。
     * 边界用 clamp（而不是补零）：补零会在四边留下暗边，铺满全屏时就是一圈可见的黑框。
     */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dx in -radius..radius) {
                    val p = src[y * w + (x + dx).coerceIn(0, w - 1)]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
                tmp[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                var n = 0
                for (dy in -radius..radius) {
                    val p = tmp[(y + dy).coerceIn(0, h - 1) * w + x]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
                out[y * w + x] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ 取色

    /**
     * 主色：**"出现得多"与"颜色够艳"两者相乘**的最大值，而不是单纯的平均色。
     *
     * 平均色在真实封面上经常是灰的（一张专辑封面里天空、墙壁、衣服各占一块，
     * 平均下来互相抵消）。乘以饱和度之后，画面里那块真正有颜色的小面积
     * （往往是乐器、logo）才会被选出来。
     *
     * 量化到 4×4×4 的色格统计：既压掉噪声，又让"数量"这件事变得可数。
     */
    private fun dominantColor(source: Bitmap): Int? {
        return runCatching {
            val scaled = Bitmap.createScaledBitmap(source, SAMPLE_SIZE_PX, SAMPLE_SIZE_PX, true)
            val n = SAMPLE_SIZE_PX * SAMPLE_SIZE_PX
            val pixels = IntArray(n)
            scaled.getPixels(pixels, 0, SAMPLE_SIZE_PX, 0, 0, SAMPLE_SIZE_PX, SAMPLE_SIZE_PX)

            var bestScore = 0f
            var bestColor: Int? = null
            for (p in pixels) {
                if (((p ushr 24) and 0xFF) < 128) continue // 透明像素不参与
                val hsv = FloatArray(3)
                AColor.colorToHSV(p, hsv)
                // 明度太低的不选：它们在暗背景上看不出区别，等于没取到色。
                if (hsv[2] < 0.06f) continue
                val score = hsv[1] * hsv[2]
                if (score > bestScore) {
                    bestScore = score
                    bestColor = p
                }
            }
            bestColor
        }.getOrNull()
    }

    /**
     * **饱和度提纯**：把"泥巴色"（低饱和）抬进干净的颜色区间。
     *
     * ---------------------------------------------------------------------------
     * 为什么需要它
     *
     * [dominantColor] 的评分是 `S × V`，所以它挑出来的永远是**这一张封面上最艳的那块**——
     * 但"最艳"是相对的：一张黑白写真加一小块暗红 logo，主色就是那个暗红，
     * 饱和度可能只有 0.35。这种颜色配上后面 [darkenForReadability] 的明度压制，
     * 落到屏幕上就是**灰扑扑的泥巴色**，看起来像取色失败，而不是"从封面取到色"。
     *
     * 所以低于 [PURIFY_TRIGGER_SATURATION] 的颜色一律抬进 [PURIFY_MIN_SATURATION, PURIFY_MAX_SATURATION]。
     *
     * ---------------------------------------------------------------------------
     * 为什么是 0.58–0.82
     *
     *   · **下限 0.58**：HSV 里 0.58 上下才开始明显"看得出是有颜色的"。
     *     再低（0.4 以下）在暗背景上就已经读不出色相了，等同于灰。
     *   · **上限 0.82**：再往上就是荧光色。整屏都是高饱和的紫/绿会很扎眼，
     *     和"暗场里透出一束光"的参考观感相反。
     *
     * 写成 `coerceIn(下限, 上限)` 而不是直接 `= 0.58`：这两个数是这条式子的**契约**
     * （提纯结果永远落在 [0.58, 0.82]），上下限一起表达"提纯后应该在哪"。
     * 当前触发线 0.55 低于下限，所以实际上抬起来的就是 0.58 —— 但如果以后把触发线
     * 调高（比如想连中等饱和也一起收一收），上限仍然接着生效，不用再改这里。
     *
     * 色相不动：提纯只是"离开灰 / 别到荧光"，不是换颜色。
     *
     * 只有**明度**没被碰过：[darkenForReadability] 后面会把它压到
     * [MAX_BACKGROUND_VALUE] 以下，那里的饱和度下限是 0.22（比 0.58 低），
     * 所以提纯后的值能原样穿过去，不会被压回灰。
     */
    private fun purifySaturation(argb: Int): Int {
        val hsv = FloatArray(3)
        AColor.colorToHSV(argb, hsv)
        if (hsv[1] >= PURIFY_TRIGGER_SATURATION) return argb
        hsv[1] = hsv[1].coerceIn(PURIFY_MIN_SATURATION, PURIFY_MAX_SATURATION)
        return AColor.HSVToColor(hsv)
    }

    /** 低于它就认为取到的是"泥巴色"。0.55 是"能看出颜色"与"偏灰"之间的分界。 */
    private const val PURIFY_TRIGGER_SATURATION = 0.55f

    /** 提纯后饱和度的下限。离开灰。 */
    private const val PURIFY_MIN_SATURATION = 0.58f

    /** 提纯后饱和度的上限。别到荧光。 */
    private const val PURIFY_MAX_SATURATION = 0.82f

    /** 没有封面（或封面取色失败）时的确定性颜色。 */
    private fun seedColor(hue: Float): Int =
        AColor.HSVToColor(floatArrayOf(hue, 0.55f, 0.62f))

    /**
     * 由任意字符串得到一个稳定的色相。
     *
     * 用字符串哈希的绝对值取模 360 —— 不需要"好看"，只需要**同一首歌每次一样**。
     * 用 `hashCode()` 而不是更强的散列：它在本进程内稳定，而缓存键本身也是
     * 同一份字符串，两者同源就不会出现"缓存命中了但颜色变了"。
     */
    private fun hueOf(seed: String): Float {
        val h = seed.hashCode().toLong() and 0xFFFFFFFFL
        return (h % 360L).toFloat()
    }

    // ------------------------------------------------------------- 可读性整形

    /**
     * 把颜色压成"能做背景"的颜色：明度不超过 [MAX_BACKGROUND_VALUE]、
     * 饱和度不低于 [MIN_BACKGROUND_SATURATION]。
     *
     * 这两条都是为了同一件事 —— **浅色文字压在它上面要始终读得清**。
     * 明度上限管"不刺眼"，饱和度下限管"别退化成灰"。
     */
    private fun darkenForReadability(argb: Int, extraValueScale: Float = 1f): Color {
        val hsv = FloatArray(3)
        AColor.colorToHSV(argb, hsv)
        hsv[1] = hsv[1].coerceAtLeast(MIN_BACKGROUND_SATURATION)
        hsv[2] = (hsv[2] * extraValueScale).coerceAtMost(MAX_BACKGROUND_VALUE)
        // 渐变的暗端不能真到 0：纯黑会让整屏显得像没画东西。
        hsv[2] = hsv[2].coerceAtLeast(0.06f)
        return Color(AColor.HSVToColor(hsv))
    }

    /** 色相偏移，用来从一个颜色派生另一个"同族"的颜色（渐变的两端）。 */
    private fun shiftHue(argb: Int, degrees: Float): Int {
        val hsv = FloatArray(3)
        AColor.colorToHSV(argb, hsv)
        hsv[0] = (hsv[0] + degrees + 360f) % 360f
        return AColor.HSVToColor(hsv)
    }

/** 渐变两端之间的色相偏移量。小一点才像"同一束光"，大一点就成撞色了。 */
    private const val HUE_SHIFT_DEGREES = 24f

    // ------------------------------------------------------------- 派生色（音乐页光场）

    /**
     * 由主色派生一个**指定明度**的同色相颜色（左暗右亮光场的三个停靠点用它）。
     *
     * 明度直接给目标值而不是乘系数：光场要的是"左端 0.18、右端 0.86"这种**绝对**
     * 控制 —— 乘系数的话，封面本身很暗时右端永远亮不起来（乘 2 也还是暗），
     * 封面很亮时左端永远暗不下去。色相与饱和度跟着主色走，画面才像"同一束光"。
     *
     * @param value 目标明度（HSV 的 V），0..1。
     * @param saturationScale 饱和度缩放。浅亮字色用它把饱和度降下来（接近白但有色调）。
     */
    internal fun shade(accent: Color, value: Float, saturationScale: Float = 1f): Color {
        val hsv = FloatArray(3)
        AColor.colorToHSV(accent.toArgbCompat(), hsv)
        hsv[1] = (hsv[1] * saturationScale).coerceIn(0f, 1f)
        hsv[2] = value.coerceIn(0f, 1f)
        return Color(AColor.HSVToColor(hsv))
    }

    /** 主色的**高明度浅亮变体**。中央歌词的主字色 —— 有色调的浅色，不是刺眼纯白。 */
    internal fun light(accent: Color): Color = shade(accent, LYRIC_VALUE, LYRIC_SATURATION_SCALE)

    /**
     * 中央歌词的**主字色**：主色同一色相下、绝对 S=[LYRIC_INK_SATURATION]、V=[LYRIC_INK_VALUE] 的浅亮色。
     *
     * ---------------------------------------------------------------------------
     * 为什么不用 [light] 那种"乘系数"
     *
     * [light] 是把主色的饱和度**乘** 0.35。乘系数在封面本身有颜色时没问题，
     * 但封面主色可能是提纯前就很灰的那种（S≈0.2），乘完只剩 0.07 —— 那就是白，
     * 色相信息整个丢掉，歌词与"同色系"这件事断了。
     *
     * 所以字色给的是**绝对坐标**：无论封面多灰多艳，歌词永远是"同色相 + S 0.28 + V 0.95"。
     * 歌词是整屏唯一的阅读对象，它的颜色应该稳定 —— 不该随封面漂移。
     *
     * ---------------------------------------------------------------------------
     * 为什么是 0.28 / 0.95
     *
     *   · **S 0.28**：低到"远看就是白色"，近看才透出一点色相。再高（0.4+）字会被读成
     *     彩色字，和"歌词是主体、花色只是氛围"的主次关系反了。
     *   · **V 0.95**：比 [light] 的 0.93 再亮一档 —— 字要在压暗的背景上立住，
     *     接近白但不到纯白（纯白在暗场上会有边缘光晕，字反而显得脏）。
     *
     * [light] 保留不动：新声波圆的波峰色还要它（那里要的是"跟着封面走的亮色"，
     * 与字色"稳定的浅色"是两种诉求）。
     */
    internal fun lyricInk(accent: Color): Color {
        val hsv = FloatArray(3)
        AColor.colorToHSV(accent.toArgbCompat(), hsv)
        hsv[1] = LYRIC_INK_SATURATION
        hsv[2] = LYRIC_INK_VALUE
        return Color(AColor.HSVToColor(hsv))
    }

    /** 歌词主字的饱和度（绝对值，不是缩放系数）。 */
    private const val LYRIC_INK_SATURATION = 0.28f

    /** 歌词主字的明度（绝对值）。 */
    private const val LYRIC_INK_VALUE = 0.95f

    /** 歌词主字的明度。压到 0.93 以下会显灰，再高就接近纯白、失去"从封面取色"的意义。 */
    private const val LYRIC_VALUE = 0.93f

    /** 歌词主字的饱和度缩放。0.35 是"看得出色调但不抢字"的位置。 */
    private const val LYRIC_SATURATION_SCALE = 0.35f

    /** 仅用于单元测试/预览：把 [ArtworkVisual] 画成一张预览位图。 */
    internal fun previewSwatch(visual: ArtworkVisual, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawRect(
            0f, 0f, size.toFloat(), size.toFloat(),
            Paint().apply { color = visual.accent.toArgbCompat() },
        )
        return bmp
    }
}

/** 与 Compose 的 `toArgb()` 同义，单独写一个避免在这个文件里 import Compose 的扩展。 */
private fun Color.toArgbCompat(): Int = AColor.argb(
    (alpha * 255f).toInt(),
    (red * 255f).toInt(),
    (green * 255f).toInt(),
    (blue * 255f).toInt(),
)
