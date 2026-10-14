package com.qingke.desk.feature.media.music.lyrics

import android.util.Log
import com.mocharealm.accompanist.lyrics.core.model.ISyncedLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.AutoParser

private const val TAG = "DeskLyrics"

/**
 * 歌词解析。**全工程只有本文件 import 歌词解析库**（与 `DeskGlass` 之于玻璃库同一个原则：
 * 换实现只改一处，而不是全工程搜 import）。
 *
 * ---------------------------------------------------------------------------
 * 与库的分工（这是本节最重要的约定）
 *
 * 库负责"把文本拆成带时间轴的音节"，我们负责三件它**不做**或**做得不合我们口径**的事：
 *
 *   1. **时间戳归一化** —— 库的格式判定只认 `[mm:ss.xx]`，其余写法它返回空且不报错。
 *      见 [LrcTextNormalizer]。
 *   2. **命中判据** —— "有没有时间轴"由我们自己判（[LrcTextNormalizer.hasTimestamps]），
 *      不把库的 `canParse` 当判据。理由：`canParse` 判的是"这个格式我认不认"，
 *      而我们要判的是"这份文本最终能不能变成行级时间轴"。两者在边界上并不等价。
 *   3. **末行哨兵** —— 库把最后一行的 `end` 填成 `Int.MAX_VALUE`（表示"没有下一行"）。
 *      那是哨兵不是时间，必须夹掉，否则进度/扫过动画会算出 0 进度。
 *
 * ---------------------------------------------------------------------------
 * 为什么 `parse` 一律包 runCatching
 *
 * 库里的 `SyncedLine` / `KaraokeLine` 都有 `init { require(end >= start) }` ——
 * **时间戳乱序的 LRC 会让它抛 IllegalArgumentException**。真实文件里乱序是存在的
 * （手工改过的歌词、拼接过的翻译轨）。这里不能让它冒到 UI 线程上：一首歌解析失败
 * 的最坏结果应该是"没有歌词"，而不是"音乐页崩了"。
 */
object LyricsParser {

    /**
     * 库把"没有下一行"写成 `Int.MAX_VALUE`。真遇到这个哨兵且**不知道歌曲时长**时，
     * 退到"这一行唱 60 秒"—— 只为让进度动画有个有限的分母。
     * 知道时长时会优先夹到时长上（那才是真的）。
     */
    private const val FALLBACK_LINE_MS = 60_000

    // AutoParser 是无状态的：内部只是按顺序问每个解析器 canParse，然后委托。
    // 建成单例而不是每次调用 new 一个 —— 它内部还持有编译好的正则。
    private val autoParser = AutoParser()

    /** 这份文本看起来有没有同步时间轴。拉取层用它做命中判据（**不用** 库的 canParse）。 */
    fun looksSynced(raw: String): Boolean =
        raw.isNotBlank() && LrcTextNormalizer.hasTimestamps(LrcTextNormalizer.normalize(raw))

    /**
     * 解析成同步歌词。
     *
     * @param durationMs 已知的歌曲时长，用于夹住末行哨兵。<= 0 表示未知。
     * @return 不可用时返回 null（没有时间轴、解析抛异常、或一行有效内容都没有）；
     *         有纯音乐标记时返回 [LyricsDoc.instrumental] = true 的空文档，
     *         让页面显示"纯音乐，请欣赏"而不是"没有歌词"。
     */
    fun parseSynced(
        raw: String,
        source: LyricsSource,
        durationMs: Long = 0L,
    ): LyricsDoc? {
        if (raw.isBlank()) return null
        val normalized = LrcTextNormalizer.normalize(raw)
        if (!LrcTextNormalizer.hasTimestamps(normalized)) {
            Log.d(TAG, "no timestamps after normalize, ${raw.length} chars")
            return null
        }

        val parsed = runCatching { autoParser.parse(normalized) }
            .onFailure { Log.w(TAG, "parse failed: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull()
            ?: return null

        val lines = parsed.lines
            .mapNotNull { it.toLyricLine(durationMs) }
            .sortedBy { it.startMs }

        if (lines.isEmpty()) {
            // 有时间戳却一行文字都没有 —— 这是纯音乐的写法（有些 LRC 就是这么标的）。
            Log.i(TAG, "timestamps present but no text -> instrumental")
            return LyricsDoc(
                lines = emptyList(),
                source = source,
                title = parsed.title.takeIf { it.isNotBlank() },
                artist = parsed.artists?.joinToString("/") { it.name }?.takeIf { it.isNotBlank() },
                instrumental = true,
            )
        }

        Log.i(
            TAG,
            "parsed ${lines.size} lines from $source word=${lines.count { it.syllables.size > 1 }}",
        )
        return LyricsDoc(
            lines = lines,
            source = source,
            title = parsed.title.takeIf { it.isNotBlank() },
            artist = parsed.artists?.joinToString("/") { it.name }?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * 把库的一行翻成本项目的 [LyricLine]。
     *
     * 返回 null 表示"这一行不要"：**只有时间戳、没有文字的行是标记行**。
     * 库自己也会丢掉它们（`else if (textContent.isNotBlank())`），这里再判一次是
     * 防御性的 —— 它保证"页面上的每一行都有字"，而页面正靠着这条不变式
     * 才不需要在聚焦逻辑里再跳一次空行。
     */
    private fun ISyncedLine.toLyricLine(durationMs: Long): LyricLine? {
        val text = textOf().trim()
        if (text.isBlank()) return null

        val startMs = start.coerceAtLeast(0)
        var endMs = end

        // 末行哨兵：库把最后一行填成 Int.MAX_VALUE。夹到时长；不知道时长就退一档。
        val cap = if (durationMs in 1..Int.MAX_VALUE.toLong() && durationMs > startMs) {
            durationMs.toInt()
        } else {
            startMs + FALLBACK_LINE_MS
        }
        if (endMs > cap) endMs = cap
        if (endMs < startMs) endMs = startMs

        val syllables = (this as? KaraokeLine)
            ?.syllables
            ?.filter { it.content.isNotBlank() }
            ?.map { LyricSyllable(text = it.content, startMs = it.start, endMs = it.end) }
            .orEmpty()

        return LyricLine(
            text = text,
            startMs = startMs,
            endMs = endMs,
            translation = translationOf()?.trim()?.takeIf { it.isNotBlank() },
            syllables = syllables,
        )
    }

    private fun ISyncedLine.textOf(): String = when (this) {
        is KaraokeLine -> syllables.joinToString(separator = "") { it.content }
        is SyncedLine -> content
        else -> ""
    }

    private fun ISyncedLine.translationOf(): String? = when (this) {
        is KaraokeLine -> translation
        is SyncedLine -> translation
        else -> null
    }
}
