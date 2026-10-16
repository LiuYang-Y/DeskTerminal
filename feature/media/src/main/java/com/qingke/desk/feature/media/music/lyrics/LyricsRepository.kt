package com.qingke.desk.feature.media.music.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.qingke.desk.feature.media.music.NowPlaying
import com.qingke.desk.feature.media.music.online.QqCredential
import com.qingke.desk.feature.media.music.online.QqLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

private const val TAG = "DeskLyricsRepo"

/** 本地导入的结果。三种结局要在界面上说清楚，不能都糊成"失败"。 */
sealed interface ImportResult {

    data class Ok(val doc: LyricsDoc) : ImportResult

    /** 文件读到了，但里面**没有时间轴**（纯文本歌词）。 */
    data object NoTimeline : ImportResult

    data class Failed(val reason: String) : ImportResult
}

/**
 * 歌词的取数层：内存 → 本机缓存 → QQ 音乐；本地文件导入也走这里。
 *
 * 链路固定成一条顺序，界面不需要知道走的是哪一段 —— 它只要一个 [LyricsDoc]：
 *
 * ```
 * 内存 LRU（本次进程内，8 首）
 *    ↓ 没有
 * 本机缓存（filesDir/lyrics，跨进程）
 *    ↓ 没有，且不是"刚查过确实没有"
 * QQ 音乐（musicu.fcg 搜 mid → fcg_query_lyric_new.fcg）
 *    ↓ 命中
 * 写缓存 + 写内存 → 返回
 * ```
 *
 * **内存层是必要的而不是冗余的**：界面每秒都在重算"当前是哪一行"，
 * 而"要不要重新拉歌词"的判断挂在曲目变化上。没有内存层的话，
 * 每次曲目对象重建都会去碰一次磁盘（哪怕只是 stat 一个文件）。
 */
class LyricsRepository(
    context: Context,
    private val credentialSource: suspend () -> QqCredential? = { null },
) {

    /**
     * 只留 applicationContext。
     *
     * 这个对象会被 `remember` 在组合里活很久（理论上可以长过 Activity），
     * 存一个 Activity 引用就是在泄漏它。本类也确实不需要 Activity ——
     * 本地文件走 SAF 的 Uri，读权限绑在调用方那次授权上。
     */
    private val appContext: Context = context.applicationContext

    private val cache = LyricsCache(appContext)
    private val memory = LruCache<String, LyricsDoc>(MEMORY_CAPACITY)

    /**
     * 歌词走 QQ 音乐。**凭证要注入**：`DoSearchForQQMusicLite` 需要登录态
     * （见 [QqLyricsClient] 的类注释），把壳层那份凭证源接上之后 QQ 才真正生效。
     *
     * `by lazy` 的理由：这个仓库在未登录时也会被构造，而凭证源在 `LyricsRepository`
     * 构造的那一刻未必已经就绪（`QqLoginController` 是壳层另一条 `remember`）。
     * 惰性求值把"读凭证"推到第一次真正搜索时 —— 那时登录状态早就落定了。
     */
    private val qqLyrics: QqLyricsClient by lazy {
        QqLyricsClient(appContext, credentialSource)
    }

    /**
     * 取这首歌的同步歌词。
     *
     * 永远不抛异常：拿不到就返回空的 [LyricsDoc]。**"空"有三种原因**
     * （确实没有 / 网络暂时不可达 / 首次拉取中），前两种用 [LyricsDoc] 上的
     * `instrumental` / `unreachable` 区分 —— 页面要给用户不同的话，不能都糊成"未找到"。
     */
    suspend fun load(track: NowPlaying): LyricsDoc = withContext(Dispatchers.IO) {
        if (track.title.isBlank()) return@withContext LyricsDoc.Empty
        val key = track.frameKey

        memory.get(key)?.let { return@withContext it }

        cache.read(key)?.let { raw ->
            LyricsParser.parseSynced(raw, LyricsSource.CACHE, track.durationMs)?.let { doc ->
                memory.put(key, doc)
                return@withContext doc
            }
            // 缓存里的内容解析不出来了（解析器升级、文件被截断）——删掉重拉一次。
            cache.invalidate(key)
        }

        if (cache.isKnownMissing(key)) return@withContext LyricsDoc.Empty

        // 网络失败**绝不能**写 6 小时负缓存（那是 2026-10-14 真机抓到的 bug：
        // 一首明明在库里的歌，因为拉取时恰好断网，之后 6 小时全显示"未找到"）。
        // "确认没有"与"这次没问成"在这里分叉，各自落到不同时长的负缓存上。
        //
        // 取数来源：**QQ 音乐**（唯一来源，2026-10-16 起）。
        val qq = qqLyrics.fetch(
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationSec = if (track.hasDuration) track.durationMs / 1000L else 0L,
        )
        QqLog.i(TAG, "lyrics[$key] QQ -> ${verdict(qq)} (title=${track.title} artist=${track.artist})")
        when (qq) {
            is FetchResult.NotFound -> {
                cache.markMissing(key)
                LyricsDoc.Empty
            }

            is FetchResult.Unreachable -> {
                cache.markMissing(key, LyricsCache.TRANSIENT_MISSING_TTL_MS)
                LyricsDoc.Offline
            }

            is FetchResult.Found -> persistFound(key, qq.hit, track.durationMs)
        }
    }

    /** 取数结局的可读名，写进文件日志便于抓证（logcat 对本 App 恒空）。 */
    private fun verdict(r: FetchResult): String = when (r) {
        is FetchResult.Found -> "Found(${r.hit.source.label})"
        is FetchResult.NotFound -> "NotFound"
        is FetchResult.Unreachable -> "Unreachable"
    }

    /**
     * 命中处理：写缓存 + 写内存，解析失败（客户端判据与解析器不一致）按"没有"处理。
     */
    private fun persistFound(key: String, hit: LyricsHit, durationMs: Long): LyricsDoc {
        val doc = LyricsParser.parseSynced(hit.syncedLyrics, hit.source, durationMs)
        if (doc == null) {
            // 客户端判据说"有时间轴"，解析器却说"没有" —— 两者不一致时以解析器为准，
            // 并记一条日志：这是判据需要收紧的信号，不该静默。
            Log.w(TAG, "client accepted but parser rejected '${hit.matchedTrack ?: "?"}'")
            cache.markMissing(key)
            return LyricsDoc.Empty
        }
        cache.write(key, hit.syncedLyrics)
        memory.put(key, doc)
        Log.i(TAG, "lyrics ready: ${hit.matchedTrack ?: "?"} <- ${doc.source.label} (${doc.lines.size} lines)")
        return doc
    }

    /**
     * 导入本地 LRC。成功后**同时写进缓存**（键还是这首歌的 [NowPlaying.frameKey]），
     * 所以下次放到同一首歌时会自动用它，不需要再导一次。
     */
    suspend fun importLocal(track: NowPlaying, uri: Uri): ImportResult =
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.onFailure { Log.w(TAG, "open local lyrics failed: ${it.javaClass.simpleName}") }
                .getOrNull()

            if (bytes == null || bytes.isEmpty()) {
                return@withContext ImportResult.Failed("读不到这个文件，换一个试试")
            }

            val text = decodeLrc(bytes)
            if (!LyricsParser.looksSynced(text)) {
                return@withContext ImportResult.NoTimeline
            }

            val doc = LyricsParser.parseSynced(text, LyricsSource.LOCAL_FILE, track.durationMs)
                ?: return@withContext ImportResult.NoTimeline

            // 先清负缓存再写：否则"这首歌刚查过、确实没有"会一直挡在前面。
            val key = track.frameKey
            cache.invalidate(key)
            cache.write(key, text)
            memory.put(key, doc)
            Log.i(TAG, "local lyrics imported: ${track.title} (${doc.lines.size} lines)")
            ImportResult.Ok(doc)
        }

    companion object {
        /** 进程内缓存几首。8 首 × 每首几十 KB 的解析结果 —— 够覆盖"来回切"的场景。 */
        private const val MEMORY_CAPACITY = 8

        /**
         * 按字节猜编码。**中文 LRC 文件有一大批是 GBK**（老播放器导出的），
         * 直接按 UTF-8 读会得到一串 "�" —— 而那种乱码在界面上看起来像"歌词是坏的"，
         * 用户不会想到是编码问题。
         *
         * 顺序：BOM → 严格 UTF-8 → GBK → Latin-1 兜底。
         * 严格 UTF-8 是关键的一步：`String(bytes, UTF_8)` 遇到非法字节会**静默替换成 U+FFFD**，
         * 于是"这个文件不是 UTF-8"这个信息就丢了。这里用 REPORT 模式的解码器把它变成异常，
         * 才能走到 GBK 那一档。
         */
        private fun decodeLrc(bytes: ByteArray): String {
            if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            ) {
                return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            }
            if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
            }

            runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()?.let { return it }

            runCatching { return String(bytes, Charset.forName("GBK")) }
            Log.w(TAG, "GBK decode unavailable, falling back to latin-1")
            return String(bytes, Charsets.ISO_8859_1)
        }
    }
}
