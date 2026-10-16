package com.qingke.desk.feature.media.music.lyrics

/**
 * 一行歌词。
 *
 * 时间单位一律**毫秒**、`Int`：与 MediaSession 的 `PlaybackState.position` 同一口径，
 * 也和解析库的输出一致，链路上不需要任何单位转换。
 *
 * @param syllables 逐字（音节级）时间轴。**空列表 = 这行只有行级时间**。
 *                  只有本地导入的 Enhanced LRC / TTML 才有；在线来源一律没有。
 */
data class LyricLine(
    val text: String,
    val startMs: Int,
    val endMs: Int,
    val translation: String? = null,
    val syllables: List<LyricSyllable> = emptyList(),
)

/** 逐字时间轴的一个片段。只用于本地导入的逐字歌词。 */
data class LyricSyllable(
    val text: String,
    val startMs: Int,
    val endMs: Int,
)

/**
 * 歌词从哪来。写进日志、也写进页面角标 —— "这句歌词是哪来的"是排查
 * "歌词不对/没歌词"时第一个要问的问题，而它不可见就没法回答。
 */
enum class LyricsSource(val label: String) {
    /** QQ 音乐官方歌词（musicu.fcg 搜 songmid → fcg_query_lyric_new.fcg）。 */
    QQ_MUSIC("QQ 音乐"),

    /** 用户导入的本地文件。 */
    LOCAL_FILE("本地文件"),

    /** 命中本地缓存（内容本来也来自上面二者之一，这里只表示这次没走网络）。 */
    CACHE("缓存"),

    /** 没有歌词。 */
    NONE("无"),
}

/**
 * 一次查词的三种结局。**"确认没有"与"网络不可达"必须分开**，因为调用方
 * （[LyricsRepository]）对它们的处理完全不同：前者值得写一条 6 小时的负缓存，
 * 后者只配 5 分钟 —— 把瞬时失败固化成长负缓存是真 bug（2026-10-14 真机抓到）。
 *
 * **定义在这里而不是某个 client 内部**：它是 client ↔ repository 之间的公共契约，
 * 被 [QqLyricsClient] 返回、被 [LyricsRepository] 消费，两边都直接 import 它。
 */
sealed interface FetchResult {

    /** 拿到了可用的同步歌词。 */
    data class Found(val hit: LyricsHit) : FetchResult

    /** 服务器明确回答"没有"（搜不到 / 没时间戳）。重试也是这个答案。 */
    data object NotFound : FetchResult

    /** 这次根本没问成（未登录 / 超时 / 断网 / 5xx / 限流）。答案未知，稍后应重试。 */
    data object Unreachable : FetchResult
}

/**
 * 一次命中结果。歌词文本 + 来源 + 匹配到的曲目元信息。
 *
 * 由 [QqLyricsClient] 产出、[LyricsRepository] 消费。
 */
data class LyricsHit(
    val syncedLyrics: String,
    val source: LyricsSource,
    val matchedTrack: String?,
    val matchedArtist: String?,
    val matchedAlbum: String?,
    val instrumental: Boolean,
)

/**
 * 解析好的歌词文档。**页面上唯一认的歌词类型** —— 解析库（accompanist-lyrics-core）
 * 的类型一个都不许漏出去，理由与 [com.qingke.desk.feature.media.music.PlaybackSource]
 * 一样：换解析实现时页面不动。
 *
 * @param lines 已按 [LyricLine.startMs] 升序排好。这个不变式由 [LyricsParser] 保证，
 *              [lineIndexAt] 的二分查找依赖它。
 * @param instrumental 纯音乐（LRC 里标了 `[au:instrumental]` 或整篇只有一个时间戳）。
 *                     页面据此显示"纯音乐，请欣赏"而不是一片空白 ——
 *                     **空歌词与纯音乐是两件事**，前者可能是拉取失败。
 * @param unreachable 这次没拿到是因为**网络不可达**（不是库里没有）。
 *                     "空"有三种原因（库里没有 / 纯音乐 / 没问成），页面要给三种不同的话 ——
 *                     尤其第三种：冒充"未找到同步歌词"会把用户的注意力引向错误的排查方向
 *                     （2026-10-14 真机：断网时拉取失败被负缓存固化 6 小时，症状正是如此）。
 */
data class LyricsDoc(
    val lines: List<LyricLine>,
    val source: LyricsSource,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val instrumental: Boolean = false,
    val unreachable: Boolean = false,
) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /** 有没有**逐字**时间轴。没有时页面走行级高亮，有则可以做逐字扫过。 */
    val hasWordTiming: Boolean get() = lines.any { it.syllables.size > 1 }

    /**
     * 当前时刻应当高亮哪一行。
     *
     * 返回值语义（与页面的"2 行聚焦"直接对应）：
     *   · `-1` —— 还没到第一行（前奏）。页面此时只画下一行做预告。
     *   · `0..lines.lastIndex` —— 正在唱/刚唱完的那一行。
     *   · `lines.size` —— 唱完了（尾奏）。页面此时把最后一行留在原位并淡出。
     *
     * **用"最后一个 start <= t 的行"而不是"start..end 区间包含 t"**：
     * LRC 的 `end` 是下一行的 `start`，两行之间没有空隙，但**最后一行之后有**——
     * 若按区间判，尾奏一到就整片空掉，看起来像歌词跑丢了。
     * 取"最后一行已经开始的"，才是播放器里那种"最后一句停在屏幕上"的行为。
     *
     * 二分而非线性扫描：歌词行进到高频重组路径上（每秒重算），
     * 线性扫 60 行虽然也不慢，但没有理由让它随歌词长度变慢。
     */
    fun lineIndexAt(positionMs: Int): Int {
        if (lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].startMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /**
     * 当前行的播放进度 `0..1`，用于逐字扫过与行内微动效。
     * 行级歌词下它仍然有意义（整行从暗到亮的渐变）。
     */
    fun lineProgress(positionMs: Int, index: Int): Float {
        val line = lines.getOrNull(index) ?: return 0f
        val span = (line.endMs - line.startMs).coerceAtLeast(1)
        return ((positionMs - line.startMs).toFloat() / span).coerceIn(0f, 1f)
    }

    /**
     * "2 行聚焦"要画的两行：`(当前行, 下一行预告)`，`-1` 表示没有。
     *
     * · 前奏（还没到第一行）：`(-1, 0)` —— 只画预告，让最上面那行先亮起来。
     * · 正唱：`(i, i+1)`；最后一行：`(last, -1)`。
     * · 空歌词：`(-1, -1)`。
     *
     * **下一行固定取 "当前行 +1"，不做任何"跳过空行"的特殊处理** ——
     * 解析阶段已经把"只有时间戳、没有文字"的标记行丢掉了（见 [LyricsParser]），
     * 所以这里再加一层过滤只会变成两套口径。
     */
    fun focusAt(positionMs: Int): Pair<Int, Int> {
        if (lines.isEmpty()) return -1 to -1
        val current = lineIndexAt(positionMs)
        val next = if (current < 0) 0 else current + 1
        // 括号**必须**有：Kotlin 里中缀函数 `to` 比 `?:` 结合更紧，不写括号会被解析成
        // `(current to next.takeIf { … }) ?: -1` —— 于是类型塌成 `Serializable`
        // （`Pair` 与 `Int` 的公共父类型），而**语义也变成"没有下一行时把当前行也抹掉"**。
        return current to (next.takeIf { it <= lines.lastIndex } ?: -1)
    }

    companion object {
        val Empty = LyricsDoc(lines = emptyList(), source = LyricsSource.NONE)

        /** "这次没问成"（网络不可达）。与 [Empty] 的区别只在页面的那句话。 */
        val Offline = LyricsDoc(lines = emptyList(), source = LyricsSource.NONE, unreachable = true)
    }
}
