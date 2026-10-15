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
            val acc = dominantColor(artwork) ?: seedColor(seedHue)
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
