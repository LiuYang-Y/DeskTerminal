package com.qingke.desk.feature.media.music.lyrics

import android.util.Log
import com.qingke.desk.feature.media.music.online.QqLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val TAG = "DeskLrcLib"

/**
 * LRCLIB 客户端（https://lrclib.net）。
 *
 * ---------------------------------------------------------------------------
 * 为什么用 OkHttp（而不是 HttpURLConnection / Retrofit）
 *
 * 这个模块整个网络面只有两个 GET（`/api/get` 与 `/api/search`），没有认证、
 * 没有拦截器。但**不能**用 `HttpURLConnection`：在小米 11 Ultra（Android 11）上，
 * 同一进程的 `HttpURLConnection` 取歌词稳定抛 `IOException`（系统 TLS 栈与该服务
 * 协商失败），而同进程的 OkHttp（库加载、取链都走它）和 `curl` 都能正常 200。
 * 所以这里直接复用与 [com.qingke.desk.feature.media.music.online.QqMusicuApi] 同一套
 * OkHttp —— 它本就是传递依赖，不存在"为此多引入一套 HTTP 栈"的问题。
 * JSON 用 `org.json`（Android 平台自带，不是依赖）。
 *
 * ---------------------------------------------------------------------------
 * 命中判据（**这一条是本文件存在的意义**）
 *
 * 判据不是 HTTP 200，而是：
 *
 *   1. `syncedLyrics` 字段**非空**；
 *   2. 归一化后**确实含有效时间戳**（[LrcTextNormalizer.hasTimestamps]）。
 *
 * 依据是实测：`一路向北 / 周杰伦` 在 `/api/get` 上返回 **HTTP 200，但
 * `syncedLyrics` 与 `plainLyrics` 全为空串**（见 docs/2026-10-14-music-phase1-prep.md）。
 * 若拿 200 当命中，症状是"识别成功、歌词区一片空白、不报错也不回退" ——
 * 正是本项目最警惕的那一类故障：**它也在工作，但工作得不对**。
 *
 * 两条判据缺一不可：只看"非空"会放进纯音乐占位串，只看"有时间戳"会放进
 * 不含同步轴的 plain 歌词。
 *
 * ---------------------------------------------------------------------------
 * 回退链
 *
 * ```
 * /api/get（精确：歌名 + 歌手 + 专辑 + 时长）
 *     ├─ 命中 → NETWORK_GET
 *     └─ 未命中（404 / 200 但空 / 网络错）
 *           → /api/search（歌名 + 歌手）→ 按"有同步轴优先、时长最接近"挑一条
 *                 ├─ 命中 → NETWORK_SEARCH
 *                 └─ 都失败 → null（页面显示"未找到同步歌词"，不回退成 plain）
 * ```
 *
 * **不回退到 plain**：主分支只做行级同步（用户口径）。拿一份没有时间轴的纯文本
 * 塞进"2 行聚焦"的界面里，既高亮不了、也滚动不了，只会显示成两句错位的字。
 *
 * @param userAgent 必须能让服务端认出是谁在调 —— LRCLIB 的使用条款要求这样。
 */
class LrcLibClient(
    private val userAgent: String = "DeskTerminal/0.1.0",
) {

    /**
     * 网络栈改走 OkHttp（与 [com.qingke.desk.feature.media.music.online.QqMusicuApi] 同一套）。
     *
     * 原来用 `HttpURLConnection`，但在小米 11 Ultra（Android 11）上，`HttpURLConnection` 走系统 TLS
     * 栈取歌词**稳定失败**（IOException），而同一个进程的 OkHttp（库加载、取链都走它）和 `curl`
     * 都能正常 200。换成 OkHttp 后走与库加载一致的 TLS/连接栈，歌词取数才真正能联网。
     * 备注：OkHttp 在本模块本来就是传递依赖（库加载在用），这里只是把"第二套 HTTP 栈"收掉。
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 一次命中结果。 */
    data class Hit(
        val syncedLyrics: String,
        val source: LyricsSource,
        val matchedTrack: String?,
        val matchedArtist: String?,
        val matchedAlbum: String?,
        val instrumental: Boolean,
    )

    /**
     * 查一次歌词。**必须在 IO 线程外调用**（本函数自己切到 [Dispatchers.IO]）。
     *
     * @param durationSec 秒。LRCLIB 的 `/api/get` 要求它，且只接受与库里记录相差
     *                    ±2 秒以内的；差得多就是 404 —— 那是"这条路的正常失败"，
     *                    直接走搜索。0 或负数表示不知道时长，跳过 `/api/get`。
     */
    suspend fun fetch(
        title: String,
        artist: String,
        album: String?,
        durationSec: Long,
    ): FetchResult = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext FetchResult.NotFound
        try {
            if (durationSec > 0L) {
                exactGet(title, artist, album, durationSec)?.let { return@withContext FetchResult.Found(it) }
            }
            when (val hit = search(title, artist, album, durationSec)) {
                null -> FetchResult.NotFound
                else -> FetchResult.Found(hit)
            }
        } catch (e: NetworkUnavailable) {
            FetchResult.Unreachable
        }
    }

    // ------------------------------------------------------------------ /api/get

    private fun exactGet(
        title: String,
        artist: String,
        album: String?,
        durationSec: Long,
    ): Hit? {
        val url = buildString {
            append(BASE)
            append("/api/get?track_name=").append(enc(title))
            if (artist.isNotBlank()) append("&artist_name=").append(enc(artist))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            // duration 是必填项，且是**整数秒**。带小数会被当成格式错误。
            append("&duration=").append(durationSec)
        }

        val body = get(url) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val hit = json.toHit(LyricsSource.NETWORK_GET) ?: return null
        Log.i(TAG, "get hit: ${hit.matchedTrack} / ${hit.matchedArtist}")
        return hit
    }

    // --------------------------------------------------------------- /api/search

    /**
     * 搜索回退。**两段式**，第二段只在第一段空手而归时才跑：
     *
     *  1. `track_name + artist_name` —— 最准。歌手名在库里是原样时一次就中。
     *  2. `track_name` —— 兜底。**必须有这一段**，依据是真机实测（2026-10-14）：
     *     LRCLIB 对一批中文歌手存的是**罗马字**（`麻雀` 的歌手存成 `Ronghao Li`），
     *     会话里给的是 `李荣浩`，带上 `artist_name` 一票过滤，14 条结果全部被滤光，
     *     返回 `[]` —— 界面上就成了"未找到同步歌词"，而歌词明明就在库里。
     *
     * 第二段的候选会混进同名别家的歌，所以排序键（见 [rankOf]）把**歌手相似度**
     * 与**专辑相似度**排在时长前面，尽量把"同名的别家歌"压下去。
     */
    private fun search(title: String, artist: String, album: String?, durationSec: Long): Hit? {
        searchOnce(title, artist, album, durationSec, withArtist = true)?.let { return it }
        return searchOnce(title, artist, album, durationSec, withArtist = false)
    }

    private fun searchOnce(
        title: String,
        artist: String,
        album: String?,
        durationSec: Long,
        withArtist: Boolean,
    ): Hit? {
        val url = buildString {
            append(BASE)
            append("/api/search?track_name=").append(enc(title))
            if (withArtist && artist.isNotBlank()) append("&artist_name=").append(enc(artist))
        }

        val body = get(url) ?: return null
        val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
        if (array.length() == 0) {
            Log.i(TAG, "search returned 0 items for '$title' (artist=$withArtist)")
            return null
        }

        // 第一道闸：**必须有有效同步轴**。LRCLIB 上一个歌名可能挂着十几条结果，
        // 其中大多数只有 plain 歌词（用户手工传的），不先滤掉的话后面的排序毫无意义。
        val candidates = (0 until array.length())
            .mapNotNull { i -> runCatching { array.getJSONObject(i) }.getOrNull() }
            .map { it to it.optString("syncedLyrics", "") }
            .filter { (_, synced) -> LyricsParser.looksSynced(synced) }

        if (candidates.isEmpty()) {
            Log.i(TAG, "search hit ${array.length()} items but none has synced lyrics")
            return null
        }

        val best = candidates.minByOrNull { (json, _) -> rankOf(json, artist, album, durationSec) }
            ?.first ?: return null

        val hit = best.toHit(LyricsSource.NETWORK_SEARCH) ?: return null
        Log.i(
            TAG,
            "search hit: ${hit.matchedTrack} / ${hit.matchedArtist} " +
                "(artist=$withArtist, ${array.length()} items, ${candidates.size} with synced)",
        )
        return hit
    }

    /**
     * 候选的排序键（**越小越好**）。
     *
     * 三级，按可信度从高到低：
     *  1. **歌手相似度**（0 = 匹配 / 1 = 不匹配）—— 会话里给的歌手名与候选能对上，
     *     是"这就是同一首"最强的证据；罗马字那类对不上的，落 1，交给后两级。
     *  2. **专辑相似度** —— 歌手名对不上时它往往是唯一能用的信号（实测 `麻雀` 这条：
     *     会话专辑 `麻雀`，候选专辑 `麻雀`，而同名的郭静那些歌专辑完全不同）。
     *  3. **时长接近** —— 最后的裁决者。`durationSec <= 0`（会话没给时长）时恒 0，
     *     等于放弃这一级。
     */
    private fun rankOf(candidate: JSONObject, artist: String?, album: String?, durationSec: Long): Long {
        var rank = 0L
        if (!artist.isNullOrBlank() &&
            !similar(candidate.optString("artistName", ""), artist)
        ) {
            rank += 1_000_000_000L
        }
        if (!album.isNullOrBlank() && !similar(candidate.optString("albumName", ""), album)) {
            rank += 100_000_000L
        }
        if (durationSec > 0L) {
            val d = candidate.optLong("duration", 0L)
            rank += if (d <= 0L) {
                Long.MAX_VALUE / 4
            } else {
                kotlin.math.abs(d - durationSec)
            }
        }
        return rank
    }

    /** 忽略大小写与空白的"包含即算匹配"。双向：`Ronghao Li` vs `Li` 也能对上。 */
    private fun similar(a: String, b: String): Boolean {
        val x = a.trim().lowercase().replace(" ", "")
        val y = b.trim().lowercase().replace(" ", "")
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    // -------------------------------------------------------------------- 公共部分

    /** 一个 LRCLIB 对象 → [Hit]。判据在这里落地，调用方不要各自再判一次。 */
    private fun JSONObject.toHit(source: LyricsSource): Hit? {
        val synced = optString("syncedLyrics", "")
        if (synced.isBlank()) return null
        if (!LyricsParser.looksSynced(synced)) {
            // 有内容但没有有效时间轴 —— 实测存在（把 plain 塞进 synced 字段的上传）。
            Log.d(TAG, "syncedLyrics present but no valid timestamps, len=${synced.length}")
            return null
        }
        return Hit(
            syncedLyrics = synced,
            source = source,
            matchedTrack = optString("trackName", "").takeIf { it.isNotBlank() },
            matchedArtist = optString("artistName", "").takeIf { it.isNotBlank() },
            matchedAlbum = optString("albumName", "").takeIf { it.isNotBlank() },
            instrumental = optBoolean("instrumental", false),
        )
    }

    /**
     * 一次 GET。
     *
     * 返回 null 表示"服务器明确回答了没有"（404 / 其它 4xx）。
     * **网络层失败不返回 null，而是抛 [NetworkUnavailable]** —— 这是本次修复的关键：
     * "没问成"和"问到了、答案是没"是两种事实，混在一起会把瞬时失败固化成长负缓存。
     *
     *   · [java.io.IOException]（含超时、DNS 失败）→ 抛；
     *   · 5xx / 429（服务端临时问题、被限流）→ 抛；
     *   · 404 → null（/api/get 对"库里没有"的标准回答，正常路径）；
     *   · 其它 4xx → null（请求本身有问题，重试也是同样的答案）。
     */
    private fun get(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val code = response.code
                if (code == 404) {
                    // /api/get 对"库里没有"就是 404，属于正常路径，不当错误记。
                    Log.d(TAG, "404 $url")
                    return null
                }
                if (code !in 200..299) {
                    if (code >= 500 || code == 429) {
                        // 临时性失败 —— 调用方应当稍后重试，而不是记住"没有"。
                        QqLog.w(TAG, "transient http $code $url")
                        throw NetworkUnavailable("http $code")
                    }
                    QqLog.w(TAG, "client http $code $url")
                    return null
                }
                response.body.string().orEmpty()
            }
        } catch (e: IOException) {
            // 桌面终端可能长期离线（没插网线/没连 Wi-Fi）。落到文件日志便于抓证。
            QqLog.w(TAG, "io error: ${e.javaClass.simpleName} ${e.message}")
            throw NetworkUnavailable(e.message, e)
        } catch (e: NetworkUnavailable) {
            throw e
        } catch (e: Exception) {
            QqLog.w(TAG, "unexpected: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    /** 网络层没走通。它不是"答案"，所以不能被当成"没有歌词"缓存下来。 */
    private class NetworkUnavailable(
        message: String? = null,
        cause: Throwable? = null,
    ) : Exception(message, cause)

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val BASE = "https://lrclib.net"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
    }
}

/**
 * 一次查词的三种结局。**"确认没有"与"网络不可达"必须分开**，因为调用方
 * （[LyricsRepository]）对它们的处理完全不同：前者值得写一条 6 小时的负缓存，
 * 后者只配 5 分钟 —— 把瞬时失败固化成长负缓存是真 bug（2026-10-14 真机抓到）。
 *
 * 定义在 `LrcLibClient` **外面**（虽然它引用了类的嵌套类型 [LrcLibClient.Hit]）：
 * 它是一个跨层的契约（client → repository），被后者直接 import；
 * 塞进类里会让 repository 写成 `LrcLibClient.FetchResult.Found`，多一层没有意义的限定。
 */
sealed interface FetchResult {

    /** 拿到了可用的同步歌词。 */
    data class Found(val hit: LrcLibClient.Hit) : FetchResult

    /** 服务器明确回答"没有"（404 / 搜到了但全部没有同步轴）。重试也是这个答案。 */
    data object NotFound : FetchResult

    /** 这次根本没问成（超时 / 断网 / 5xx / 限流）。答案未知，稍后应重试。 */
    data object Unreachable : FetchResult
}
