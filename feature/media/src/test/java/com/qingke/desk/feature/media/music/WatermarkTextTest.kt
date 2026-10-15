package com.qingke.desk.feature.media.music

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 水印取词的取证测试。
 *
 * 2026-10-15 加了"动态取当前句首词"这条规则（对标参考视频），三条分支：
 *   ① 有歌词 → 首词（中文前 2 字 / 英文首词）；
 *   ② 无歌词 → 歌手名（优先中文段，其次首词）；
 *   ③ 都没有 → 空串（调用方不画水印）。
 *
 * 每条都带**能证伪**的断言：包括"英文撇号不拆"（`I'll` 是一个词）
 * 与"行首标点先剥"（`"Yeah"` 取 `Yeah`）这两个曾经会出错的形状。
 *
 * 2026-10-15 补：汉字与英文是**两条口径** —— 汉字按字数裁（2 字 / 4 字），
 * 英文取**整个词**（旧版统一 `take(4)` 把 `OneRepublic` 截成了 `OneR`）。
 */
class WatermarkTextTest {

    @Test
    fun `有歌词时取中文前两字`() {
        assertEquals("只会", watermarkTextOf("周慧敏", "瞎了", "只会出现在童话里"))
        assertEquals("忘不", watermarkTextOf("周慧敏", "瞎了", "忘不了这一句"))
    }

    @Test
    fun `有歌词时取英文首个单词`() {
        assertEquals("I'll", watermarkTextOf("Adele", "Someone Like You", "I'll never learn"))
        assertEquals("Oh", watermarkTextOf("Adele", "Skyfall", "Oh-oh-oh-oh"))
        assertEquals("Can't", watermarkTextOf("Adele", "Skyfall", "Can't hold back"))
        assertEquals("Yeah", watermarkTextOf("Adele", "Skyfall", "\"Yeah yeah yeah\""))
    }

    @Test
    fun `歌词行首的标点先剥掉`() {
        assertEquals("笑声", watermarkTextOf("周慧敏", "x", "（笑声）忘不了"))
        assertEquals("No", watermarkTextOf("Adele", "x", "\"No\" no no"))
    }

    @Test
    fun `空白歌词行回退歌手名`() {
        assertEquals("邓紫棋", watermarkTextOf("G.E.M. 邓紫棋", "泡沫", null))
        assertEquals("The", watermarkTextOf("The Weeknd", "Blinding Lights", ""))
        // 歌手名走老规则：整段中文最多取 4 字（105sp 一行放得下）。
        assertEquals("光年之外", watermarkTextOf("", "光年之外", null))
    }

    @Test
    fun `歌手名是英文时取整个词不截断`() {
        // 回归 2026-10-15：这里原来统一走汉字那条 `take(4)`，把 OneRepublic 截成了 "OneR"，
        // 水印就卡死在这半截词上（用户报的"背景大字卡死在 OneR"）。
        assertEquals("OneRepublic", watermarkTextOf("OneRepublic", "Counting Stars", null))
        assertEquals("Coldplay", watermarkTextOf("Coldplay", null, null))
        // 歌词行那一路同理（它本来就取整词，这条只是把口径钉住）。
        assertEquals("Dreaming", firstWordOf("Dreaming of you"))
        assertEquals("Can't", firstWordOf("Can't stop the feeling"))
    }

    @Test
    fun `英文只受防失控帽约束`() {
        // 14 字符帽是给"没有空格的长串"兜底的；正常单词（OneRepublic 11 字母）碰不到它。
        assertEquals(14, firstWordOf("abcdefghijklmnopqrst").length)
        assertEquals("abcdefghijklmn", firstWordOf("abcdefghijklmnopqrst"))
    }

    @Test
    fun `都没有时返回空`() {
        assertEquals("", watermarkTextOf("", "", null))
        assertEquals("", watermarkTextOf("   ", "  ", "  "))
    }

    @Test
    fun `firstWordOf 直接调用的边界`() {
        // 中文一律前 2 字：两字句取整句，三字以上截前 2。
        assertEquals("好听", firstWordOf("好听"))
        assertEquals("忘不", firstWordOf("忘不了这一句"))
        assertEquals("", firstWordOf("。。。"))
        assertEquals("Hello", firstWordOf("Hello world"))
    }
}
