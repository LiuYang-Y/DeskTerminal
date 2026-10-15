package com.qingke.desk.feature.media.music.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChineseConverter] 的边界取证。
 *
 * ---------------------------------------------------------------------------
 * 为什么这里**不能**断言"一定转成简体"
 *
 * `feature/media/build.gradle.kts` 打开了 `unitTests.isReturnDefaultValues = true`
 * （那是给 `LyricsParser` 里的 `android.util.Log` 用的），于是本地 JVM 单测里
 * `android.icu.text.Transliterator.getInstance(...)` 走的是**桩实现**、返回 null ——
 * 转换器取不到，[ChineseConverter.toSimplified] 就按设计**原样返回**。
 *
 * 也就是说：本地能验的是**兜底那条路**（不许崩、不许把文本弄丢），
 * 而"真的转成简体"只能在真机上验（或将来引入 Robolectric 的 ICU）。
 * 所以下面用"非繁即简"这种不吃环境的断言形式，把本地能钉死的那半钉死。
 */
class ChineseConverterTest {

    @Test
    fun `空串原样返回`() {
        assertEquals("", ChineseConverter.toSimplified(""))
    }

    @Test
    fun `转换器不可用时原样返回而不是抛`() {
        // 本地 JVM：桩实现给 null → 走 `transliterator?.transliterate(...) ?: text` 这条路。
        // 真机：返回的是"我们的爱"。两种都算通过 —— 断言的是"非繁即简、绝不丢字"。
        val traditional = "我們的愛"
        val converted = ChineseConverter.toSimplified(traditional)
        assertTrue(
            "应当原样返回（本地桩）或转成简体（真机），实际=$converted",
            converted == traditional || converted == "我们的爱",
        )
    }

    @Test
    fun `转换是幂等的且不改变长度`() {
        // 繁简是**一对一**映射：ICU 不会把一个字变成两个字，也不会吞字 ——
        // 长度不变这一条在两种环境下都成立，是"没把文本弄坏"的硬证据。
        listOf("我們的愛", "頭髮", "简体本来就是简体", "mixed 中英 123", "！，。").forEach { input ->
            val once = ChineseConverter.toSimplified(input)
            assertEquals("长度不能变：$input", input.length, once.length)
            assertEquals("再转一次不能变：$input", once, ChineseConverter.toSimplified(once))
        }
    }
}
