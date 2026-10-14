package com.qingke.desk.feature.media.music.online

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

        /** PNG 文件头。用来挡"返回了一个坏图但流程继续等"的假成功。 */
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
