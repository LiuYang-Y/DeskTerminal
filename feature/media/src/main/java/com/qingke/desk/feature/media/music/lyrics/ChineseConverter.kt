package com.qingke.desk.feature.media.music.lyrics

/**
 * 繁体 → 简体。**只做一件事，且只在显示层用**。
 *
 * ---------------------------------------------------------------------------
 * 为什么要做
 *
 * QQ 音乐上同一首歌的歌词有两种来源，繁简混杂：港台版发行物、用户上传的 LRC、
 * 以及我们自己从标题里生成的背景大字，都可能是繁体。用户口径是
 * "这块屏就该是简体" —— 所以统一在**解析层**转一次，下游（主歌词、逐字高亮、
 * 动态水印）全部自动是简体，不需要各自记得转。
 *
 * ---------------------------------------------------------------------------
 * 为什么用 `android.icu` 而不是自己维护一张转换表
 *
 * `android.icu.text.Transliterator` 的 `Traditional-Simplified` 是**系统自带的 ICU 数据**
 * （API 24+ 起在 framework 里，本项目 minSdk 26）：一简多繁、词组级消歧（"頭髮" vs "發財"）
 * 都由 ICU 的数据处理，自己写表只会在这种地方出错。也不依赖网络。
 *
 * ---------------------------------------------------------------------------
 * 为什么每一步都 `runCatching`
 *
 * 这是**显示层的锦上添花**，不能成为"歌词加载失败"的原因：
 *   · `getInstance` 在个别 ROM 上可能抛（ICU 数据被裁）；
 *   · **JVM 本地单测里 `android.icu` 是 stub，调用必抛**（`RuntimeException: Stub!`）。
 * 两处都兜住，返回原文 —— 于是"转换没生效"最多是繁简没变，绝不会是空白或崩溃。
 * 惰性初始化同理：只有真的要用时才付这一次查找的代价，且失败结果被记住（`lazy` 缓存 null）。
 */
object ChineseConverter {

    /** 转换器。取不到就是 null（见类注释），此时 [toSimplified] 原样返回。 */
    private val transliterator by lazy {
        runCatching {
            android.icu.text.Transliterator.getInstance("Traditional-Simplified")
        }.getOrNull()
    }

    /**
     * 把 [text] 里的繁体字转成简体。已经是简体 / 空串 / 转换器不可用时都原样返回。
     *
     * 不缓存结果：调用方（[LyricsParser] 解析一次、`MusicPage` 的 `remember`）
     * 本来就只在内容变化时调用一次，再加一层 LRU 只是多一份要同步失效的状态。
     */
    fun toSimplified(text: String): String {
        if (text.isEmpty()) return text
        return runCatching { transliterator?.transliterate(text) ?: text }.getOrDefault(text)
    }
}
