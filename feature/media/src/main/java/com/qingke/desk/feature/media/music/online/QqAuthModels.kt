package com.qingke.desk.feature.media.music.online

import org.json.JSONObject

/** QIMEI 的新鲜期。上游 24 小时（folia `QIMEI_TTL_MS`）。 */
internal const val QIMEI_TTL_MS = 24L * 60 * 60 * 1000

/** 登录类型：QQ 音乐 App 扫码（MQTT 推送通道）。上游 `MOBILE_LOGIN_TYPE`。 */
internal const val LOGIN_TYPE_QQ_APP = 6

/** 渠道号。公共客户端常量，不是用户凭证。 */
internal const val CHANNEL_ID = "10003505"

/** 客户端版本号，同时出现在 `comm.cv/v` 与 User-Agent 里。 */
internal const val CLIENT_VERSION = "14090008"

/**
 * 一份 QQ 音乐凭证。
 *
 * ---------------------------------------------------------------------------
 * ⚠️ 这个类**含密钥**，纪律有三条
 *
 * 1. [toString] 被覆写成脱敏形式。data class 的默认实现会把 [musickey] 原样打出来，
 *    那是一条谁都不会注意到的泄漏路径 —— 一句 `log("cred=$cred")`、一次异常栈就够了。
 *    覆写它是**必要的**，不是洁癖。
 * 2. 只允许存在于两处：进程内存、[QqCredentialStore] 的加密文件。**不进 git / 文档 / 普通日志。**
 * 3. 对外只允许 [mask] 这一种形式。界面、日志、崩溃上报一律用它。
 *
 * @param musicId 账号 id。取自 `str_musicid`（微信凭证会把占位值放在 `musicid`），
 *                所以取值的优先级在 [fromLoginResponse] 里统一处理，别在这里另开一套。
 * @param musickey ★ 密钥。后续所有请求的 `Cookie: qm_keyst=…` 与 `comm.authst` 都用它。
 * @param createdAtMs 落盘时刻（本机时钟），只用于展示"登录于何时"。
 * @param expiresAtMs 由上游的 `musickeyCreateTime + keyExpiresIn` 推导并夹取过的到期时刻。
 *                    缺失或畸形时退回 `now + 24h`。
 */
data class QqCredential(
    val musicId: String,
    val musickey: String,
    val loginType: Int,
    val nick: String,
    val avatarUrl: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {

    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    /** 剩余有效期（毫秒，可为负）。 */
    fun remainingMs(nowMs: Long = System.currentTimeMillis()): Long = expiresAtMs - nowMs

    /**
     * 界面与日志**唯一**允许使用的形式。
     *
     * 刻意给出长度而不是掩码后的片段：长度是排查"凭证是不是被截断了"的关键信息，
     * 而密钥的前后几位反而不该露 —— 短前缀在彩虹表面前等于没遮。
     */
    fun mask(): String = buildString {
        append("uin=")
        append(if (musicId.length > 4) musicId.take(4) + "****" else "****")
        append(" key=")
        append(musickey.length)
        append("字符 type=")
        append(loginType)
    }

    override fun toString(): String = "QqCredential(${mask()})"

    companion object {

        /**
         * 从 `music.login.LoginServer / Login` 的响应构造。
         *
         * 两个必须照上游来的判断：
         *  · `musicid` 取 `str_musicid` 优先 —— 微信通道会把占位值放在 `musicid`，
         *    而上游**自己**发请求时用的也是 `credentialMusicId`，我们跟着它才不会拿到一个"服务端不认"的 id；
         *  · `loginType` 缺省时才用 [LOGIN_TYPE_QQ_APP]，不是写死 —— 一份微信凭证带着 6 发回去会被后续每个请求拒。
         */
        fun fromLoginResponse(data: JSONObject): QqCredential {
            val musicId = credentialMusicId(data)
            val musickey = data.optString("musickey")
            require(musicId.isNotEmpty() && musickey.isNotEmpty()) { "Login 响应缺少凭证字段" }
            val now = System.currentTimeMillis()
            return QqCredential(
                musicId = musicId,
                musickey = musickey,
                loginType = data.optInt("loginType", LOGIN_TYPE_QQ_APP),
                nick = data.optString("nick").ifEmpty { data.optString("nickname") },
                avatarUrl = data.optString("logo").ifEmpty { data.optString("avatarUrl") },
                createdAtMs = now,
                expiresAtMs = expiryAt(data, now),
            )
        }

        /** 与上游 `credentialMusicId` 同序：`str_musicid` → `musicid`。 */
        private fun credentialMusicId(data: JSONObject): String {
            val str = data.optString("str_musicid").trim()
            val raw = data.optString("musicid").trim()
            return when {
                str.isNotEmpty() && str != "0" -> str
                raw.isNotEmpty() && raw != "0" -> raw
                else -> str.ifEmpty { raw }
            }
        }

        /**
         * 到期时刻**跟着 musickey 自己的寿命走**，不是硬编 24 小时。
         *
         * 上游给 `musickeyCreateTime`（epoch 秒）+ `keyExpiresIn`（秒，实测 259200 = 三天）。
         * 硬编 24h 会让用户每天重扫一次 —— 而那并不是 QQ 要求的。
         * 缺字段或值畸形时退回 24h，结果再夹进 `[1h, 7d]`：一份畸形的上游回应既不会立刻作废会话，
         * 也不会签发一个远超凭证寿命的会话。
         *
         * **已过期时算出来是负的** → 被夹到 1 小时。那一小时里任何一次上游调用都会被拒，
         * 客户端据此干净地登出（而不是卡在一个"看起来已登录"的状态里）。
         */
        private fun expiryAt(data: JSONObject, nowMs: Long): Long {
            val createdSec = data.optLong("musickeyCreateTime", 0L)
            val lifeSec = data.optLong("keyExpiresIn", 0L)
            if (createdSec <= 0L || lifeSec <= 0L) return nowMs + DEFAULT_TTL_MS
            val ttl = (createdSec + lifeSec) * 1000L - nowMs
            return when {
                ttl < MIN_TTL_MS -> nowMs + MIN_TTL_MS
                ttl > MAX_TTL_MS -> nowMs + MAX_TTL_MS
                else -> nowMs + ttl
            }
        }

        private const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
        private const val MIN_TTL_MS = 60L * 60 * 1000
        private const val MAX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * 合成设备档案。
 *
 * ---------------------------------------------------------------------------
 * 为什么是**合成**的
 *
 * 上游不校验设备真实性 —— 它要的是一组**稳定**的标识（同一账号反复登录时设备指纹不能变，
 * 否则会触发"登录设备数超限"）。folia 造的就是一台 MI 6（`createAndroidDevice`），
 * Phase 0 那份凭证用的也是它，实测全程可用。
 *
 * 合成还带来一个真实的隐私好处：**这台终端的真实 IMEI / Android ID 一步都不出设备**。
 *
 * 不加密存储（与 [QqCredential] 不同）：这里全是随机噪音，没有任何与用户绑定的信息，
 * 而 qimei 即使泄漏也只是让别人的请求看起来像来自本设备。**加密它只有成本没有收益。**
 */
data class QqDeviceProfile(
    val display: String,
    val product: String,
    val device: String,
    val board: String,
    val model: String,
    val fingerprint: String,
    val procVersion: String,
    val imei: String,
    val brand: String,
    val androidId: String,
    val openUdid: String,
    val osRelease: String,
    val sdk: Int,
    val qimei: String = "",
    val qimei36: String = "",
    val qimeiSavedAtMs: Long = 0L,
    val sessionUid: String = "",
    val sessionSid: String = "",
) {

    val hasFreshQimei: Boolean
        get() = qimei.isNotEmpty() && qimei36.isNotEmpty() &&
            System.currentTimeMillis() - qimeiSavedAtMs < QIMEI_TTL_MS

    fun toJson(): JSONObject = JSONObject().apply {
        put("display", display); put("product", product); put("device", device)
        put("board", board); put("model", model); put("fingerprint", fingerprint)
        put("procVersion", procVersion); put("imei", imei); put("brand", brand)
        put("androidId", androidId); put("openUdid", openUdid)
        put("osRelease", osRelease); put("sdk", sdk)
        put("qimei", qimei); put("qimei36", qimei36)
        put("qimeiSavedAtMs", qimeiSavedAtMs)
        put("sessionUid", sessionUid); put("sessionSid", sessionSid)
    }

    companion object {
        /**
         * 解析。**缺必填字段就返回 null** —— 一份被截断的档案会让 `comm` 里出现空字段，
         * 症状是上游返回一个语焉不详的错误码，排查成本远高于在这里直接拒绝。
         */
        fun fromJson(o: JSONObject): QqDeviceProfile? {
            val display = o.optString("display")
            val androidId = o.optString("androidId")
            val openUdid = o.optString("openUdid")
            if (display.isEmpty() || androidId.isEmpty() || openUdid.isEmpty()) return null
            return QqDeviceProfile(
                display = display,
                product = o.optString("product"),
                device = o.optString("device"),
                board = o.optString("board"),
                model = o.optString("model"),
                fingerprint = o.optString("fingerprint"),
                procVersion = o.optString("procVersion"),
                imei = o.optString("imei"),
                brand = o.optString("brand"),
                androidId = androidId,
                openUdid = openUdid,
                osRelease = o.optString("osRelease", "10"),
                sdk = o.optInt("sdk", 29),
                qimei = o.optString("qimei"),
                qimei36 = o.optString("qimei36"),
                qimeiSavedAtMs = o.optLong("qimeiSavedAtMs", 0L),
                sessionUid = o.optString("sessionUid"),
                sessionSid = o.optString("sessionSid"),
            )
        }
    }
}

/**
 * 登录状态。**界面唯一的数据来源** —— 它不认识 MQTT、不认识 musicu，
 * 就像 [com.qingke.desk.feature.media.music.PlaybackSource] 不认识 MediaSession 一样。
 *
 * [Failed] 带 [stage] 而不是只带一句话：这个流程有七个阶段，出错时"卡在哪一步"
 * 比"失败了"有用得多（上游对空链接、会员不足、下架都回同一个固定串，
 * 所以阶段信息是我们唯一能拿到的区分度）。
 */
sealed interface QqLoginState {

    /** 未登录（含"从未登录过"与"点了退出登录"）。 */
    data object LoggedOut : QqLoginState

    /**
     * 已开始登录，但还没有二维码可看（QIMEI → GetSession → CreateQRCode 这一段，约 1~3 秒）。
     *
     * 单独成态而不是让界面停在 [LoggedOut]：用户点了按钮却"什么都没发生"，会再点一次；
     * 而底下的流程**不是幂等**的（第二次 CreateQRCode 会在上游铸出第二张码，
     * 把第一张和它的订阅一起作废 —— `createRelayQqQrDriver` 专门注释过这个坑）。
     */
    data object Preparing : QqLoginState

    /** 未登录，且已有一张展示中的二维码。 */
    data class QrReady(val pngBase64: String, val expiresInSec: Int) : QqLoginState

    /** 码被扫了，等用户在手机上确认。 */
    data object Scanned : QqLoginState

    /** 已收到 cookies，正在换正式凭证。 */
    data object Exchanging : QqLoginState

    data class LoggedIn(val cred: QqCredential) : QqLoginState

    /** 盘上有凭证但已过期。**不自动删除** —— 留着让"退出登录"成为用户的显式动作。 */
    data class Expired(val cred: QqCredential) : QqLoginState

    /** [stage] 见 [QqLoginController] 的阶段常量。 */
    data class Failed(val stage: String, val reason: String) : QqLoginState
}

/** 给界面用的一句话状态标签。**不回显任何 token**。 */
internal fun QqLoginState.statusLabel(): String = when (this) {
    QqLoginState.LoggedOut -> "未登录"
    QqLoginState.Preparing -> "正在准备二维码"
    is QqLoginState.QrReady -> "等待扫码"
    QqLoginState.Scanned -> "已扫码，请在手机上确认"
    QqLoginState.Exchanging -> "正在登录"
    is QqLoginState.LoggedIn -> if (nick.isNotEmpty()) nick else "已登录"
    is QqLoginState.Expired -> "登录已过期"
    is QqLoginState.Failed -> "登录失败"
}

private val QqLoginState.nick: String get() = (this as? QqLoginState.LoggedIn)?.cred?.nick.orEmpty()

/** 是否处于"已经有一个能用的凭证"的状态。第 2 步的播放源据此决定要不要接管。 */
internal val QqLoginState.isUsableCredential: Boolean
    get() = this is QqLoginState.LoggedIn && !cred.isExpired()
