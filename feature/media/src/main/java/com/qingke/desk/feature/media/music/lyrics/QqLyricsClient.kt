package com.qingke.desk.feature.media.music.lyrics

import android.content.Context
import android.util.Base64
import android.util.Log
import com.qingke.desk.feature.media.music.online.QqCredential
import com.qingke.desk.feature.media.music.online.QqDeviceFactory
import com.qingke.desk.feature.media.music.online.QqDeviceProfile
import com.qingke.desk.feature.media.music.online.QqLog
import com.qingke.desk.feature.media.music.online.QqMusicuApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

private const val TAG = "DeskQqLyrics"

/**
 * QQ 音乐官方歌词客户端。
 *
 * ---------------------------------------------------------------------------
 * 链路
 *
 * 两步，**都在登录之后**：
 *
 *   1. `u.y.qq.com/cgi-bin/musicu.fcg` · `music.search.SearchCgiService /
 *      DoSearchForQQMusicLite` 按"歌名"搜出 `songmid`。走 [QqMusicuApi.call]
 *      那套带凭证的 POST（见下方"为什么搜索必须复用传输层"）。
 *   2. `c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid={mid}&format=json&nobase64=1`
 *      （**必须带 `Referer: https://y.qq.com/`**，否则返回空）取 LRC，解析 `data.lyric`
 *      —— 它是 Base64，即便带 `nobase64=1` 接口行为也不稳，所以两种都兜住。
 *
 * ---------------------------------------------------------------------------
 * 为什么搜索必须复用 [QqMusicuApi] 的传输层，而不是自己拼 GET URL
 *
 * 这一条是 2026-10-16 的根因：旧实现自己拼
 * `u.y.qq.com/cgi-bin/musicu.fcg?data={comm,req:{…}}`，方法名写成了
 * `DoSearchForQQMusicDesktop` —— **那是错的**（见 `docs/2026-10-14-music-phase0-qq-vip-check.md`
 * §3 证伪二：应用侧真实搜索走的是 `DoSearchForQQMusicLite`，且**需要凭证**）。
 * 错方法名 + 无凭证 → 上游返回空 → `searchSongMid` 静默 null → 永远取不到 QQ 歌词；
 * 中文歌词若走了别的源常匹配到别的版本，用户看到的就是"歌词不同步"。
 *
 * 所以搜索这一步与取链接/取歌单走**同一个** `musicu.fcg` POST 传输层：
 * 同一份 `comm`（QIMEI / OpenUDID / authst）、同一个 `Cookie: qm_keyst=…`、
 * 同一套错误码判据。`DoSearchForQQMusicLite` 需要凭证这条也在那里被满足。
 *
 * ---------------------------------------------------------------------------
 * 为什么歌词**只**走 QQ 音乐
 *
 * QQ 音乐自家的歌词最全、时间戳最准，而且**自带繁简统一**（我们在解析层之外先转一次简体）。
 * 它播放的歌，歌词也来自同一家 —— 版本天然一致，不会出现"歌词不同步"。
 *
 * ---------------------------------------------------------------------------
 * 凭证从哪来（以及"没登录"时怎么办）
 *
 * 唯一入口是 [credentialSource]（壳层注入 [QqLoginController.credentialOrNull]，
 * 与 [ExoPlayerPlaybackSource] 同一条路：**每次读盘、判过期，本类不缓存明文**）。
 *
 * 注入了 source 但返回 null（未登录 / 已过期） → 如实返回 [FetchResult.Unreachable]
 * 而不是 NotFound：**"没问成"和"库里没有"必须是两件事** —— 前者不该写负缓存，
 * 否则用户登录之后那首歌会在缓存期内一直显示"未找到"。
 *
 * 没注入 source（单测 / 预览） → 沿用历史上那条免登录的 GET 搜索路径。
 * 它在新版上游下**大概率搜不到**，但作为降级它不会 crash，也不会假装成功。
 *
 * ---------------------------------------------------------------------------
 * 命中判据
 *
 * 返回文本**必须含有效时间戳**（`LyricsParser.looksSynced`）才认；否则宁可返回"未找到"，
 * 也不要把一份"没有时间轴的歌词"塞进界面 —— 那是本项目最警惕的"它在工作、但工作得不对"。
 *
 * ---------------------------------------------------------------------------
 * 网络实现走 OkHttp（与库加载/取链同一套 TLS/连接栈）：
 *
 * @param context 只用来取设备档案（[QqDeviceFactory]，首次会写一份合成档案）。
 *                本类只留 `applicationContext` —— 它被 `remember` 在组合里活很久。
 *                可以传 null（单测 / 预览）：那时搜索降级到免登录 GET，不崩。
 * @param credentialSource 凭证来源。壳层注入 [QqLoginController.credentialOrNull]。
 *                         **每次搜索前读一次**，本类不缓存明文（与 [ExoPlayerPlaybackSource] 同一条纪律）。
 */
class QqLyricsClient(
    context: Context? = null,
    private val credentialSource: suspend () -> QqCredential? = { null },
    private val userAgent: String = "DeskTerminal/0.1.0",
) {

    private val appContext: Context? = context?.applicationContext

    /**
     * 歌词取数统一走 OkHttp（与库加载/取链同一套 TLS/连接栈）。
     * 原 `HttpURLConnection` 在小米 11 Ultra 上取歌词稳定 IOException，换成 OkHttp 才真正联网。
     */
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** 带凭证的 `musicu.fcg` 传输层。搜索走它；未登录时它是 null（见 [searchSongMid]）。 */
    private val musicu = QqMusicuApi(httpClient)

    /**
     * 设备档案只在内存里留一份。**惰性创建**：这条路径在未登录时根本不会被用到，
     * 而 `loadOrCreate` 会写盘（首次生成一份合成档案）—— 没必要为一次没走成的搜索做这件事。
     */
    @Volatile private var device: QqDeviceProfile? = null

    /**
     * 查一次歌词。**必须在 IO 线程外调用**（本函数自己切到 [Dispatchers.IO]）。
     *
     * @return [FetchResult.Found] 命中可用同步歌词；[FetchResult.NotFound] 库里没有或没时间戳；
     *         [FetchResult.Unreachable] 这次没问成（未登录 / 超时 / 断网 / 5xx / 限流）。
     */
    suspend fun fetch(
        title: String,
        artist: String,
        album: String?,
        durationSec: Long,
    ): FetchResult = withContext(Dispatchers.IO) {
        if (title.isBlank()) return@withContext FetchResult.NotFound
        try {
            val mid = searchSongMid(title, artist) ?: return@withContext FetchResult.NotFound
            Log.d(TAG, "search hit: mid=$mid for '$title' / '$artist'")
            val raw = queryLyric(mid) ?: return@withContext FetchResult.NotFound
            // 繁体统一转简（解析层之外先转一次，因为 Base64 解码后也是原文）。
            val simplified = ChineseConverter.toSimplified(raw)
            if (!LyricsParser.looksSynced(simplified)) {
                Log.d(TAG, "lyric has no valid timestamps, mid=$mid len=${simplified.length}")
                return@withContext FetchResult.NotFound
            }
            FetchResult.Found(
                LyricsHit(
                    syncedLyrics = simplified,
                    source = LyricsSource.QQ_MUSIC,
                    matchedTrack = title,
                    matchedArtist = artist.ifBlank { null },
                    matchedAlbum = album,
                    instrumental = false,
                ),
            )
        } catch (e: NetworkUnavailable) {
            FetchResult.Unreachable
        }
    }

    /**
     * 第一步：搜 `songmid`。返回第一个命中曲目的 `mid`，或 null（没搜到）。
     *
     * 两条路径：
     *  · **有凭证（正常路径）**：走 [QqMusicuApi.call] 的 `musicu.fcg` POST，
     *    `music.search.SearchCgiService / DoSearchForQQMusicLite`。
     *  · **凭证源缺失（降级路径）**：沿用旧的手拼 GET（免登录）。它搜不到也不该崩 ——
     *    但**它不被当成"库里没有"**：见 [fetch] 的 `credentialSource` 说明。
     */
    private suspend fun searchSongMid(title: String, artist: String): String? {
        val credential = runCatching { credentialSource() }.getOrNull()
        val list = if (credential != null) {
            searchViaMusicu(title, credential)
        } else {
            QqLog.i(TAG, "搜索降级到免登录 GET（凭证源缺失）：'$title'")
            searchAnonymous(title)
        }
        if (list == null) {
            QqLog.w(TAG, "搜索失败（'$title' / '$artist'）：无返回或被上游拒绝")
            return null
        }
        QqLog.i(TAG, "搜索返回 ${list.length()} 条（'$title' / '$artist'）")
        if (list.length() == 0) return null
        // 优先挑歌手名能对上的那一条（同名别家歌压下去）。
        // 上游字段名两代之间有漂移，`mid` 与 `songmid` 都兜住。
        for (i in 0 until list.length()) {
            val item = runCatching { list.getJSONObject(i) }.getOrNull() ?: continue
            val mid = midOf(item)
            if (mid.isEmpty()) continue
            val singerName = singerNamesOf(item)
            val matched = artist.isNotBlank() && singerName.contains(artist, ignoreCase = true)
            QqLog.i(TAG, "候选[$i] mid=$mid singer='$singerName'${if (matched) " ← 歌手命中" else ""}")
            if (matched) return mid
        }
        // 一条也没对上歌手名：退而取第一条。这是最后一档候选，
        // **不是"命中"** —— 它仍然要过 `looksSynced` 判据。
        val first = midOf(runCatching { list.getJSONObject(0) }.getOrNull() ?: return null)
        if (first.isEmpty()) {
            QqLog.w(TAG, "搜索结果里没有可用的 songmid 字段")
            return null
        }
        QqLog.i(TAG, "没有歌手名对上的候选，退回第一条 mid=$first")
        return first
    }

    /**
     * 带凭证的搜索。`music.search.SearchCgiService / DoSearchForQQMusicLite`。
     *
     * 参数名按 Lite 端点的形状给：`query` / `search_type` / `num_per_page` / `page_num`。
     * **上游返回空时不要靠猜改参数**：先看日志里这一条的 `QqLog`（`搜索失败` / `返回 0 条`）
     * 与 `QqMusicu.call` 的 `call(search-lyrics)` 记录，再按观测改一次。
     */
    private suspend fun searchViaMusicu(title: String, credential: QqCredential): JSONArray? {
        val profile = ensureDevice() ?: run {
            QqLog.w(TAG, "设备档案不可用，搜索放弃")
            return null
        }
        return try {
            val data = musicu.call(
                phase = "search-lyrics",
                module = MODULE_SEARCH,
                method = "DoSearchForQQMusicLite",
                param = JSONObject().apply {
                    put("query", title)
                    put("search_type", SEARCH_TYPE_SONG)
                    put("num_per_page", SEARCH_PAGE_SIZE)
                    put("page_num", 1)
                },
                profile = profile,
                credential = credential,
            )
            val list = parseSearchList(data)
            if (list == null) {
                // **按观测调参，不靠猜**：Lite 端点的返回形状第一次实测，
                // 把顶层与 body 的键名如实记下来（只记键名，不记值——值里可能有账号信息）。
                val bodyKeys = runCatching {
                    buildString { data.getJSONObject("body").keys().forEach { append(it).append(',') } }
                }.getOrDefault("(无 body)")
                val keys = buildString {
                    data.keys().forEach { append(it).append(',') }
                }
                QqLog.w(TAG, "Lite 搜索返回了但找不到歌曲数组，data 键=[$keys] body 键=[$bodyKeys]")
            }
            list
        } catch (e: Exception) {
            // 上游拒绝（QqProtocolException）/ 网络失败都到这里。**不重试、不猜原因**：
            // 如实记一条 W 日志，交由上层决定"未找到"还是"网络不可用"。
            QqLog.w(TAG, "带凭证搜索失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * 旧的手拼 GET。**仅作为凭证源缺失时的降级**（单测 / 预览 / 极端情况下凭证读不出来）。
     *
     * 2026-10-16 起方法名不再姑且用错的那个：`DoSearchForQQMusicDesktop` 在应用侧不存在，
     * 用它只会稳定拿到空。这里保留这条路径不是因为它在产品上可用，而是因为
     * "少一个能让搜索跑起来的入口"比"多一条走得通但未必灵的路"更糟。
     */
    private fun searchAnonymous(title: String): JSONArray? {
        val data = JSONObject().apply {
            put(
                "comm",
                JSONObject().apply {
                    put("ct", 24)
                    put("cv", 0)
                    put("format", "json")
                },
            )
            put(
                "req",
                JSONObject().apply {
                    put("module", MODULE_SEARCH)
                    put("method", "DoSearchForQQMusicLite")
                    put(
                        "param",
                        JSONObject().apply {
                            put("query", title)
                            put("search_type", SEARCH_TYPE_SONG)
                            put("page_num", 1)
                            put("num_per_page", SEARCH_PAGE_SIZE)
                        },
                    )
                },
            )
        }
        val url = buildString {
            append(SEARCH_BASE)
            append("?format=json&inCharset=utf8&outCharset=utf-8")
            append("&notice=0&platform=yqq.json&needNewCode=0&new_json=1")
            append("&data=").append(enc(data.toString()))
        }
        // ⚠️ 不吞 NetworkUnavailable：网络层没走通是"没问成"，由 [fetch] 转成 Unreachable。
        val body = get(url, referer = null, swallowNetworkFailure = false) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return runCatching {
            parseSearchList(
                json.getJSONObject("data")
                    .getJSONObject("req")
                    .getJSONObject("data"),
            )
        }.getOrNull()
    }

    /**
     * 从搜索结果里取歌曲数组。
     *
     * 形状是**实测出来的**（2026-10-16 小米真机，见日志"Lite 搜索返回了但找不到歌曲数组"）：
     * `DoSearchForQQMusicLite` 的返回是 `data.body.item_song` —— 裸 JSONArray，
     * **不是** Desktop 端点的 `body.song.list`。三条都兜住，顺序即优先级。
     */
    private fun parseSearchList(data: JSONObject): JSONArray? {
        val bodies = listOf(
            runCatching { data.getJSONObject("body") }.getOrNull(),
            data,
        )
        for (bodyObj in bodies) {
            if (bodyObj == null) continue
            // Lite 端点：`body.item_song`（裸数组）。
            runCatching { bodyObj.getJSONArray("item_song") }.getOrNull()?.let { return it }
            // Desktop 端点的旧形状：`song` 裸数组，或 `song.list`。
            runCatching { bodyObj.getJSONArray("song") }.getOrNull()?.let { return it }
            runCatching { bodyObj.getJSONObject("song").getJSONArray("list") }.getOrNull()
                ?.let { return it }
        }
        return null
    }

    /** 曲目 id。上游 `mid` / `songmid` 两种字段名都出现过，取到就算。 */
    private fun midOf(item: JSONObject): String =
        item.optString("mid").trim().ifEmpty { item.optString("songmid").trim() }

    /** 歌手名（多个用 `/` 连接）。`singer` 是数组，元素里的名字字段是 `name`。 */
    private fun singerNamesOf(item: JSONObject): String {
        val singers = item.optJSONArray("singer") ?: return ""
        return (0 until singers.length())
            .mapNotNull { singers.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
            .joinToString("/")
    }

    /** 设备档案（惰性读盘）。失败不抛：搜索降级为匿名查询。 */
    private fun ensureDevice(): QqDeviceProfile? {
        device?.let { return it }
        val ctx = appContext ?: return null
        val loaded = runCatching { QqDeviceFactory.loadOrCreate(ctx) }
            .onFailure { QqLog.w(TAG, "设备档案读取失败：${it.javaClass.simpleName}") }
            .getOrNull() ?: return null
        device = loaded
        return loaded
    }

    /** 第二步：取歌词。返回解码后的 LRC 文本，或 null（没拿到）。 */
    private fun queryLyric(mid: String): String? {
        val url = buildString {
            append(LYRIC_BASE)
            append("?songmid=").append(mid)
            append("&format=json&nobase64=1")
        }
        val body = get(url, referer = "https://y.qq.com/") ?: run {
            QqLog.w(TAG, "取词失败（mid=$mid）：无响应体")
            return null
        }
        val json = runCatching { JSONObject(body) }.getOrNull() ?: run {
            QqLog.w(TAG, "取词失败（mid=$mid）：响应不是 JSON，长度=${body.length}")
            return null
        }
        // 形状兜底：`{data:{lyric}}` 是记录里的主形状，但这个端点年代久远，
        // 顶层直挂 `lyric` 的变体也出现过 —— 两处都试，代价只是一次 optString。
        val dataObj = json.optJSONObject("data")
        var lyric = dataObj?.optString("lyric").orEmpty()
        if (lyric.isBlank()) lyric = json.optString("lyric").orEmpty()
        if (lyric.isBlank()) {
            val topKeys = buildString { json.keys().forEach { append(it).append(',') } }
            val dataKeys = dataObj?.let { o -> buildString { o.keys().forEach { append(it).append(',') } } } ?: "(无data)"
            QqLog.w(TAG, "取词失败（mid=$mid）：lyric 为空（顶层键=[$topKeys] data键=[$dataKeys]）")
            return null
        }
        // 解码判断**不能依赖"Base64.decode 抛不抛异常"**（2026-10-16 真机实测的坑）：
        // 带 `nobase64=1` 时返回的已是明文 LRC，但 `Base64.DEFAULT` 是宽容模式，
        // 把明文当 Base64 解**不抛异常**，只会解出一串乱码 —— 旧写法 `runCatching{…}?:原文`
        // 对此完全失效，症状是"歌词有内容但 looksSynced 判否"。
        // 正确判据：Base64 密文字符集只有 `A-Za-z0-9+/=`（加空白），
        // 中文 LRC 必含超出该字符集的字符 → 不在字符集内就是明文，直接用。
        val decoded = if (isBase64Alphabet(lyric)) {
            runCatching {
                String(Base64.decode(lyric, Base64.DEFAULT), StandardCharsets.UTF_8)
            }.getOrDefault(lyric)
        } else {
            lyric
        }
        QqLog.i(TAG, "取词成功（mid=$mid）：解码后 ${decoded.length} 字符，开头=${decoded.take(80)}")
        return decoded
    }

    /** 是否只含 Base64 字符集（含换行/回车）。含任何其它字符（如汉字）就不是 Base64。 */
    private fun isBase64Alphabet(text: String): Boolean {
        if (text.isEmpty()) return false
        for (c in text) {
            val ok = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '+' || c == '/' || c == '=' || c == '\n' || c == '\r'
            if (!ok) return false
        }
        return true
    }

    // -------------------------------------------------------------------- 公共部分

    /** 一次 GET。带 `Referer`（fcg_query_lyric_new 必须带，否则返回空）。 */
    private fun get(url: String, referer: String? = null): String? =
        get(url, referer, swallowNetworkFailure = true)

    /**
     * @param swallowNetworkFailure 网络层失败时返回 null（true）还是抛 [NetworkUnavailable]（false）。
     *
     * 两种都要，因为两条调用点的语义不同：取歌词那一步"没拿到"与"没问成"都是回 null
     * （上层统一按 [FetchResult] 结局处理，见 [LyricsRepository]）；而搜索那一步"没问成"必须一路
     * 冒到 [fetch] 变成 [FetchResult.Unreachable] —— 否则未登录时会被记成"这首歌没有歌词"。
     */
    private fun get(url: String, referer: String?, swallowNetworkFailure: Boolean): String? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
            if (referer != null) builder.header("Referer", referer)
            httpClient.newCall(builder.build()).execute().use { response ->
                val code = response.code
                if (code == 404) {
                    Log.d(TAG, "404 $url")
                    return null
                }
                if (code !in 200..299) {
                    if (code >= 500 || code == 429) {
                        QqLog.w(TAG, "transient http $code $url")
                        throw NetworkUnavailable("http $code")
                    }
                    QqLog.w(TAG, "client http $code $url")
                    return null
                }
                response.body.string().orEmpty()
            }
        } catch (e: IOException) {
            QqLog.w(TAG, "io error: ${e.javaClass.simpleName} ${e.message}")
            if (swallowNetworkFailure) null else throw NetworkUnavailable(e.message, e)
        } catch (e: NetworkUnavailable) {
            if (swallowNetworkFailure) null else throw e
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
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private companion object {
        const val SEARCH_BASE = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val LYRIC_BASE = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"

        /** `DoSearchForQQMusicLite`（需凭证）所在模块。见类注释的根因说明。 */
        const val MODULE_SEARCH = "music.search.SearchCgiService"

        /** `search_type=0` = 单曲。Lite 端点靠它区分搜歌 / 搜专辑 / 搜歌单。 */
        const val SEARCH_TYPE_SONG = 0

        /** 每页条数。取 10 是为了"同名别家歌"能靠歌手名筛掉，又不至于让响应变大。 */
        const val SEARCH_PAGE_SIZE = 10

        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
    }
}
