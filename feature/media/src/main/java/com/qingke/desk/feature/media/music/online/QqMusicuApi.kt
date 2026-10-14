package com.qingke.desk.feature.media.music.online

import android.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 上游拒绝。带上 [phase]，因为**上游的返回码区分度极低** ——
 * `qrLogin.js` 把"会员不足 / 地区限制 / 下架 / 无该音质"全都写死成同一个 `'暂无播放链接'`，
 * 所以"卡在哪一步"是我们唯一能拿到的定位信息。
 *
 * 消息里**不含任何请求体或凭证**。
 */
class QqProtocolException(
    val phase: String,
    val upstreamCode: Int,
    val globalCode: Int,
    val httpStatus: Int,
) : Exception("$phase：上游拒绝（http=$httpStatus global=$globalCode upstream=$upstreamCode）")

/**
 * 一首歌的播放直链。
 *
 * @param url 首选地址（https 兜底域名 + `purl`）。
 * @param candidates 全部候选地址（`sip` 在前、https 兜底域名在后），取链失败换域名时用。
 *                    **只含 https 条目** —— `targetSdk 36` 下明文被系统直接拒
 *                    （Phase 0 §4.2 实测 `Cleartext HTTP traffic ... not permitted`），
 *                    而同一 `purl` 在四个 https 域名上全部 206。
 * @param declaredSize 上游声明的字节数。Phase 0 的"完整曲目"判据拿它对照 CDN 的 Content-Range。
 */
data class QqPlayLink(
    val url: String,
    val candidates: List<String>,
    val declaredSize: Long,
    val filename: String,
)

/** 一页歌单歌曲。 */
data class QqSongPage(
    val tracks: List<QqTrack>,
    val title: String,
    val total: Int,
    val hasMore: Boolean,
)

/**
 * `musicu.fcg` 客户端。
 *
 * ---------------------------------------------------------------------------
 * 这条路**没有签名参数**（Phase 0 §2 实测）
 *
 * 全部内容就是 `POST {comm, req_0:{module,method,param}}` + `Cookie` 头 + `User-Agent`。
 * 协议包里那一堆 `md5 ×6 / rsa ×5 / aes ×6` **不在这条路上** —— 它们属于 QIMEI 引导与登录，
 * 而 QIMEI 在 [QqQimei] 里、登录的加密在协议那边，都不经过这里。
 *
 * ---------------------------------------------------------------------------
 * 为什么用 OkHttp 而不是 `HttpURLConnection`
 *
 * 仅仅为了统一（[QqMqttLogin] 已经必须带 OkHttp）。这一段的 POST 本身 `HttpURLConnection` 完全够，
 * 换成 OkHttp 不是因为它更好，而是**同一子树里两套客户端不值得**。
 */
class QqMusicuApi(private val client: OkHttpClient) {

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /** 二维码。`pngBase64` 已剥掉 `data:image/png;base64,` 前缀，能直接喂 `BitmapFactory`。 */
    data class QrCode(val id: String, val pngBase64: String, val expiresInSec: Int)

    /**
     * 统一出口。
     *
     * 错误判据与上游一致：`req_0` 为空对象、或任一层 code 非 0 → 抛 [QqProtocolException]。
     * **不要在这里"猜"错误含义** —— 上游把多种原因压成同一个码，
     * 在客户端编一套解释只会造出"看起来懂了其实猜错了"的日志。
     */
    suspend fun call(
        phase: String,
        module: String,
        method: String,
        param: JSONObject,
        profile: QqDeviceProfile,
        credential: QqCredential? = null,
        commOverrides: Map<String, Any> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("comm", buildComm(profile, credential, commOverrides))
            put(
                "req_0",
                JSONObject().apply {
                    put("module", module)
                    put("method", method)
                    put("param", param)
                },
            )
        }

        val builder = Request.Builder()
            .url(MUSICU_URL)
            .header("User-Agent", "QQMusic $CLIENT_VERSION(android ${profile.osRelease})")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonType))
        // 有凭证才加 Cookie 头 —— 引导阶段（GetSession / CreateQRCode）是匿名调用。
        credential?.let { builder.header("Cookie", cookieHeader(it)) }

        client.newCall(builder.build()).execute().use { response ->
            // OkHttp 5 起 `Response.body` 是非空的（4.x 才是可空）。
            val text = response.body.string()
            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: throw QqProtocolException(phase, -1, -1, response.code)
            val item = root.optJSONObject("req_0") ?: JSONObject()
            val globalCode = root.optInt("code", 0)
            val upstreamCode = item.optInt("code", 0)
            if (item.length() == 0 || globalCode != 0 || upstreamCode != 0) {
                throw QqProtocolException(phase, upstreamCode, globalCode, response.code)
            }
            item.optJSONObject("data") ?: JSONObject()
        }
    }

    /**
     * 匿名会话引导，拿一对 uid / sid。
     *
     * 它填进 `comm.uid/sid`。**不做这一步也能取到链接**（Phase 0 那份凭证里就有 uid/sid），
     * 但上游的 `createSession` 把它放在二维码之前的关键路径上，跟着做是成本最低的选择。
     */
    suspend fun getSession(profile: QqDeviceProfile): Pair<String, String> {
        val data = call(
            phase = "get-session",
            module = "music.getSession.session",
            method = "GetSession",
            param = JSONObject().apply {
                put("uid", profile.sessionUid)
                put("vkey", 0)
                put("caller", 0)
            },
            profile = profile,
        )
        val session = data.optJSONObject("session") ?: JSONObject()
        val uid = identifierOf(session.opt("uid"))
        val sid = identifierOf(session.opt("sid"))
        if (uid.isEmpty() || sid.isEmpty()) error("GetSession 响应缺少 uid/sid")
        return uid to sid
    }

    /**
     * 取一张二维码。
     *
     * 两处与其它调用不同，都是上游写死的：
     *  · 参数带 `tmeAppID/ct/cv`；
     *  · `comm` 里覆盖 `ct:23, cv:0`。
     * 返回的 `qrcode` 是 `data:image/png;base64,…`，**校验 PNG magic** ——
     * 上游可能回一个坏图而不报错，那时候症状是"二维码区域一片空白但流程还在等"。
     */
    suspend fun createQrCode(profile: QqDeviceProfile): QrCode {
        val data = call(
            phase = "qr-create",
            module = "music.login.LoginServer",
            method = "CreateQRCode",
            param = JSONObject().apply {
                put("tmeAppID", "qqmusic")
                put("ct", 11)
                put("cv", CLIENT_VERSION.toInt())
            },
            profile = profile,
            commOverrides = mapOf("ct" to 23, "cv" to 0),
        )
        val id = data.optString("qrcodeID")
        val encoded = data.optString("qrcode").substringAfterLast(',')
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull()
        if (id.isEmpty() || bytes == null || bytes.size < PNG_MAGIC.size ||
            !bytes.copyOfRange(0, PNG_MAGIC.size).contentEquals(PNG_MAGIC)
        ) {
            error("CreateQRCode 响应缺少合法的 PNG / qrcodeID")
        }
        return QrCode(id, encoded, data.optInt("expiresIn", QR_DEFAULT_TTL_SEC))
    }

    /**
     * 用 MQTT 推来的 token 换正式凭证。
     *
     * 这一步**不带凭证**（我们还没有），但 `comm` 要带 `tmeLoginType: 6` ——
     * 它告诉上游"这是 QQ App 扫码通道"，缺了会被拒。
     */
    suspend fun login(
        profile: QqDeviceProfile,
        qrcodeId: String,
        musicId: String,
        token: String,
    ): QqCredential {
        val data = call(
            phase = "credential-exchange",
            module = "music.login.LoginServer",
            method = "Login",
            param = JSONObject().apply {
                put("musicid", musicId.toLongOrNull() ?: 0L)
                put("qrCodeID", qrcodeId)
                put("token", token)
            },
            profile = profile,
            credential = null,
            commOverrides = mapOf("tmeLoginType" to LOGIN_TYPE_QQ_APP),
        )
        return QqCredential.fromLoginResponse(data)
    }

    /**
     * 查当前账号资料。**用来判"这份凭证上游还认不认"** ——
     * 过期检测不能只看本机时钟（用户改表、时区、上游提前失效都会让本地判断失真）。
     */
    suspend fun loginUserInfo(profile: QqDeviceProfile, credential: QqCredential): JSONObject = call(
        phase = "get-login-user",
        module = "music.UserInfo.userInfoServer",
        method = "GetLoginUserInfo",
        param = JSONObject(),
        profile = profile,
        credential = credential,
    )

    // ---- 曲库（第 2 步）-----------------------------------------------------

    /**
     * 取一首歌的播放直链。`music.vkey.GetVkey / UrlGetVkey`。
     *
     * 这是 Phase 0 探针（`app/src/debug/.../Phase0ProbeActivity.kt#requestVkey`）的**逐字段移植**：
     * 同一个 module/method、同一组 param、同一个 Cookie 头。两处必须同时改 —— 改一头就会漂开。
     *
     * **与探针的一处刻意差异：不做 Range 预探。** 探针对每个候选域名都发一次
     * `Range: bytes=0-0` 是为了**取证**（"拿到的是不是完整曲目"要逐字节对照）；产品路径上
     * 这件事由 ExoPlayer 自己做（打不开会以 PlaybackException 冒出来），再预探一次只是把
     * 延迟翻倍。Phase 0 实测四个 https 域名 6/6 全部 206，所以直接取第一个 https 兜底域名。
     *
     * @param quality 音质档位。见 [QqQuality] —— 本期只有 128k 对外可选。
     */
    suspend fun getPlayLink(
        profile: QqDeviceProfile,
        credential: QqCredential,
        track: QqTrack,
        quality: QqQuality = QqQuality.DEFAULT,
    ): QqPlayLink {
        val guid = UUID.randomUUID().toString().replace("-", "")
        val filename = "${quality.prefix}${track.mid}${track.mediaIdOrMid}${quality.ext}"
        val data = call(
            phase = "get-play-link",
            module = "music.vkey.GetVkey",
            method = "UrlGetVkey",
            param = JSONObject().apply {
                // 单元素数组是上游写死的形状（ Phase 0 实测，不是风格选择）。
                put("filename", JSONArray(listOf(filename)))
                put("guid", guid)
                put("songmid", JSONArray(listOf(track.mid)))
                put("songtype", JSONArray(listOf(0)))
                put("uin", credential.musicId)
                put("ctx", 0)
            },
            profile = profile,
            credential = credential,
        )
        val info = data.optJSONArray("midurlinfo")?.optJSONObject(0) ?: JSONObject()
        val purl = info.optString("purl")
        if (purl.isEmpty()) {
            // 空链接**不猜原因**：上游把"会员不足 / 地区限制 / 下架 / 无该音质"压成同一个形状，
            // 客户端编一套解释只会造出"看起来懂了其实猜错了"的日志。如实带出 result/errtype。
            throw QqProtocolException(
                phase = "get-play-link",
                upstreamCode = info.optInt("result", -1),
                globalCode = 0,
                httpStatus = 200,
            )
        }
        return QqPlayLink(
            url = HTTPS_FALLBACK_DOMAINS.first() + purl,
            candidates = buildList {
                addAll(readSip(data))
                addAll(HTTPS_FALLBACK_DOMAINS)
            }.distinct().map { it + purl },
            declaredSize = info.optLong("size", track.size128),
            filename = info.optString("filename"),
        )
    }

    /**
     * "我喜欢"歌曲。`music.srfDissInfo.DissInfo / CgiGetDiss`，`disstid=0 / dirid=201`。
     *
     * 这一对常量是上游的硬编码：**"我喜欢"不是普通歌单，是目录 201**（`dirid` 固定 201，
     * 同一接口还能加歌/删歌）。参数形状与分页字段经两个独立来源交叉核对
     * （L-1124/QQMusicApi 的 `user.get_fav_song` 与 simple-music 的 API 梳理文档）。
     *
     * 布尔参数必须是 JSON boolean，不能序列化成 0/1 —— `tag`/`userinfo`/`orderlist` 都是。
     */
    suspend fun favoriteSongs(
        profile: QqDeviceProfile,
        credential: QqCredential,
        begin: Int = 0,
        num: Int = FAV_PAGE_SIZE,
    ): QqSongPage = dissSongs(
        profile = profile,
        credential = credential,
        disstid = 0,
        dirid = FAV_DIRID,
        begin = begin,
        num = num,
        phase = "favorite-songs",
    )

    /**
     * 一个歌单的歌曲。与 [favoriteSongs] 同一个接口，换成 `disstid` 为目标歌单。
     *
     * @param dissId 歌单 id（[QqPlaylistSummary.id]）。
     */
    suspend fun playlistSongs(
        profile: QqDeviceProfile,
        credential: QqCredential,
        dissId: String,
        begin: Int = 0,
        num: Int = FAV_PAGE_SIZE,
    ): QqSongPage = dissSongs(
        profile = profile,
        credential = credential,
        disstid = dissId.toLongOrNull() ?: 0L,
        dirid = 0,
        begin = begin,
        num = num,
        phase = "playlist-songs",
    )

    private suspend fun dissSongs(
        profile: QqDeviceProfile,
        credential: QqCredential,
        disstid: Long,
        dirid: Long,
        begin: Int,
        num: Int,
        phase: String,
    ): QqSongPage {
        val data = call(
            phase = phase,
            module = "music.srfDissInfo.DissInfo",
            method = "CgiGetDiss",
            param = JSONObject().apply {
                put("disstid", disstid)
                put("dirid", dirid)
                put("tag", true)
                put("song_begin", begin)
                put("song_num", num)
                put("userinfo", true)
                put("orderlist", true)
            },
            profile = profile,
            credential = credential,
        )
        return QqSongPage(
            tracks = parseQqSongList(data),
            title = data.optJSONObject("dirinfo")?.optString("title").orEmpty(),
            total = data.optInt("total_song_num", 0),
            hasMore = data.optBoolean("hasmore", false),
        )
    }

    /**
     * 用户**自建**歌单列表。`music.musicasset.PlaylistBaseRead / GetPlaylistByUin`。
     *
     * 数组在 `data.v_playlist`（上游的 `v_` 前缀是历史包袱，不是笔误）。
     * 失败（凭证失效 / 上游改形状）时返回空表并在日志里留下阶段名 —— **不抛**：
     * 抽屉的"我喜欢" tab 与这份列表是并列的两个来源，少一个不该让另一个也不可用。
     */
    suspend fun createdPlaylists(
        profile: QqDeviceProfile,
        credential: QqCredential,
    ): List<QqPlaylistSummary> {
        val data = call(
            phase = "created-playlists",
            module = "music.musicasset.PlaylistBaseRead",
            method = "GetPlaylistByUin",
            param = JSONObject().apply { put("uin", credential.musicId) },
            profile = profile,
            credential = credential,
        )
        return parsePlaylistArray(data.optJSONArray("v_playlist"))
    }

    /**
     * 用户**收藏**的歌单列表。`music.musicasset.PlaylistFavRead / CgiGetPlaylistFavInfo`。
     * 数组在 `data.v_list`。与 [createdPlaylists] 同样的降级策略。
     *
     * ⚠️ 上游文档里这个接口要的是**加密 uin**（`euin`），我们只有明文 musicId。
     * 先按明文试；拿不到就如实降级（记 W 日志、返回空表），不猜、不重试。
     */
    suspend fun favPlaylists(
        profile: QqDeviceProfile,
        credential: QqCredential,
        size: Int = PLAYLIST_PAGE_SIZE,
    ): List<QqPlaylistSummary> {
        val data = call(
            phase = "fav-playlists",
            module = "music.musicasset.PlaylistFavRead",
            method = "CgiGetPlaylistFavInfo",
            param = JSONObject().apply {
                put("uin", credential.musicId)
                put("offset", 0)
                put("size", size)
            },
            profile = profile,
            credential = credential,
        )
        return parsePlaylistArray(data.optJSONArray("v_list"))
    }

    /** `v_playlist` / `v_list` 两个数组的形状相同：`id / title / picurl / songnum`。 */
    private fun parsePlaylistArray(arr: JSONArray?): List<QqPlaylistSummary> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val title = o.optString("title")
                if (id.isEmpty() || title.isEmpty()) continue
                add(
                    QqPlaylistSummary(
                        id = id,
                        name = title,
                        coverUrl = o.optString("picurl"),
                        songCount = o.optInt("songnum", 0),
                    ),
                )
            }
        }
    }

    /** `sip` 数组。**实测恒为空**（Phase 0 §4.1，6/6），所以它只是锦上添花，不是依赖。 */
    private fun readSip(data: JSONObject): List<String> {
        val arr = data.optJSONArray("sip") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
    }

    // ---- 内部 ---------------------------------------------------------------

    private fun cookieHeader(cred: QqCredential): String = listOf(
        "uin=${cred.musicId}",
        "qqmusic_uin=${cred.musicId}",
        "qm_keyst=${cred.musickey}",
        "qqmusic_key=${cred.musickey}",
    ).joinToString("; ")

    private fun buildComm(
        profile: QqDeviceProfile,
        credential: QqCredential?,
        overrides: Map<String, Any>,
    ): JSONObject = JSONObject().apply {
        put("ct", 11)
        put("cv", CLIENT_VERSION.toInt())
        put("v", CLIENT_VERSION.toInt())
        put("chid", CHANNEL_ID)
        put("tmeAppID", "qqmusic")
        put("QIMEI", profile.qimei)
        put("QIMEI36", profile.qimei36)
        put("OpenUDID", profile.openUdid)
        put("udid", profile.openUdid)
        put("OpenUDID2", profile.openUdid)
        put("aid", profile.androidId)
        put("os_ver", profile.osRelease)
        put("phonetype", profile.model)
        put("devicelevel", profile.sdk.toString())
        put("newdevicelevel", profile.sdk.toString())
        put("rom", profile.fingerprint)
        if (profile.sessionUid.isNotEmpty()) put("uid", profile.sessionUid)
        if (profile.sessionSid.isNotEmpty()) put("sid", profile.sessionSid)
        credential?.let {
            put("qq", it.musicId)
            put("authst", it.musickey)
            put("tmeLoginType", it.loginType)
        }
        overrides.forEach { (key, value) -> put(key, value) }
    }

    /** 上游的 id 字段有时是数字有时是字符串，统一成字符串。 */
    private fun identifierOf(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value.trim()
        is Number -> value.toLong().toString()
        else -> ""
    }

    private companion object {
        const val MUSICU_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        const val QR_DEFAULT_TTL_SEC = 180

        /**
         * https 兜底域名，**按 Phase 0 实测的可用顺序**排列。
         *
         * `UrlGetVkey` 返回的 `sip` 恒为空数组（实测 6/6），所以域名必须自备。
         * 这四个域名对同一个 `purl` 全部返回 206（同一次运行、同一设备），
         * 顺序只是偏好（ws 是 QQ 自己的 CDN 主域名），没有"哪个更快"的实测数据。
         *
         * ⚠️ **不要加 http 条目**：targetSdk 36 下明文被系统直接拒，加了只会让
         * "候选域名逐个试"的第一跳白等一次超时。本项目不开 `usesCleartextTraffic`。
         */
        val HTTPS_FALLBACK_DOMAINS = listOf(
            "https://ws.stream.qqmusic.qq.com/",
            "https://dl.stream.qqmusic.qq.com/",
            "https://isure.stream.qqmusic.qq.com/",
            "https://sjy6.stream.qqmusic.qq.com/",
        )

        /** "我喜欢"的目录 id。上游硬编码：它不是普通歌单，是目录 201。 */
        const val FAV_DIRID = 201L

        /** 歌单歌曲每页大小。抽屉一期只拉一页，翻页属后续。 */
        const val FAV_PAGE_SIZE = 100

        /** 歌单列表每页大小。 */
        const val PLAYLIST_PAGE_SIZE = 30

        /** PNG 文件头。用来挡"返回了一个坏图但流程继续等"的假成功。 */
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
