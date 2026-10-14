package com.qingke.desk.feature.media.music.online

import android.content.Context
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 合成设备指纹的造与存。
 *
 * 落盘位置 `filesDir/qq-device.json`（明文）。理由见 [QqDeviceProfile] 的类注释：
 * 这里全是随机噪音，加密只有成本没有收益。
 *
 * **必须持久化**，不能每次启动重造：设备指纹一变，同一个账号会被上游当成一台新设备，
 * 攒够几次就是「登录设备数超限」（`20279`）。这是"看起来能用但会攒出故障"的那一类问题。
 */
object QqDeviceFactory {

    private const val FILE_NAME = "qq-device.json"
    private const val TAG = "QqDevice"

    private val rng = SecureRandom()

    fun loadOrCreate(context: Context): QqDeviceProfile {
        val file = File(context.filesDir, FILE_NAME)
        if (file.isFile) {
            val parsed = runCatching { QqDeviceProfile.fromJson(JSONObject(file.readText())) }
                .onFailure { QqLog.w(TAG, "设备档案解析失败，将重造：${it.javaClass.simpleName}") }
                .getOrNull()
            if (parsed != null) return parsed
        }
        val created = create()
        save(context, created)
        return created
    }

    fun save(context: Context, profile: QqDeviceProfile) {
        runCatching { File(context.filesDir, FILE_NAME).writeText(profile.toJson().toString()) }
            .onFailure { QqLog.w(TAG, "设备档案落盘失败：${it.javaClass.simpleName}") }
    }

    /** 照上游 `createAndroidDevice` 造一台：字段形状一致，值全随机。 */
    fun create(): QqDeviceProfile {
        val six = digits(6)
        val seven = digits(7)
        return QqDeviceProfile(
            display = "QMAPI.$six.001",
            product = "iarim",
            device = "sagit",
            board = "eomam",
            model = "MI 6",
            fingerprint = "xiaomi/iarim/sagit:10/eomam.200122.001/$seven:user/release-keys",
            procVersion = "Linux 5.4.0-54-generic-${hex(8)} (android-build@google.com)",
            imei = imei(),
            brand = "Xiaomi",
            androidId = hex(16),
            openUdid = hex(32),
            osRelease = "10",
            sdk = 29,
        )
    }

    private fun digits(n: Int) = buildString { repeat(n) { append(rng.nextInt(10)) } }

    private fun hex(n: Int) = buildString {
        repeat(n) { append("0123456789abcdef"[rng.nextInt(16)]) }
    }

    /** Luhn 校验位。上游的随机 IMEI 是这么造的，照抄 —— 一个校验位不对的 IMEI 是个不必要的异常信号。 */
    private fun imei(): String {
        val body = digits(14).map { it - '0' }
        var sum = 0
        body.forEachIndexed { index, digit ->
            val d = if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
            sum += d
        }
        return body.joinToString("") + ((10 - sum % 10) % 10)
    }
}

/**
 * QIMEI 引导。一次普通 POST，但包体要做 RSA 与 AES —— 所以它是本子树里唯一带密码学的一段。
 *
 * ---------------------------------------------------------------------------
 * 三个原语**全部有原生对应**，这是"能不能移植"的第一优先判据
 *
 * | 协议 | Kotlin |
 * | --- | --- |
 * | MD5 ×2（两个 sign） | `MessageDigest("MD5")` |
 * | RSAES-PKCS1-v1_5（包 AES 密钥） | `Cipher("RSA/ECB/PKCS1Padding")` —— ⚠️ 见下 |
 * | AES-128-CBC（payload，key 与 iv 是同一串） | `Cipher("AES/CBC/PKCS5Padding")` |
 *
 * ⚠️ **RSA 这一步不能用 RSA-OAEP**：协议要的是 PKCS#1 v1.5 加密填充，两者不可互换。
 * 上游用 Web Crypto 时是**手搓 BigInt 模幂**实现的，正是因为 `subtle.encrypt` 只提供 OAEP；
 * Android 的 JCE 直接支持 `RSA/ECB/PKCS1Padding`，所以这里不需要手搓 —— 但也**必须显式写对**，
 * 写成默认的 `RSA` 在部分实现上会落到 OAEP。
 *
 * 上游响应是 `{code, data:{code, data:{q16, q36}}}`，而中间层在实测里出现过**字符串化的 JSON**，
 * 所以取值前统一过一遍 [asJson]。
 */
object QqQimei {

    private const val URL = "https://api.tencentmusic.com/tme/trpc/proxy"
    private const val SECRET = "ZdJqM15EeO2zWc08"
    private const val APP_KEY = "0AND0HD6FE4HY80F"
    private const val PACKAGE_ID = "com.tencent.qqmusic"
    private const val TAG = "QqQimei"

    /**
     * 腾讯的 QIMEI 引导公钥（固定常量，不是每设备/每请求的值）。
     * 只取模数与指数，不从 PEM/DER 解析 —— 少一个解析器。
     */
    private const val RSA_MODULUS_HEX =
        "c4231830a2eb5fc2827170641e79d80fec51bda9a22e4b4ab37d1f205a4ae44d928cda25879f66a3429051663312a12" +
            "7faf8a246bdaaf63918417e90d7c95b5908aa6a2d0f852e4a6770294a548ac1c2fe8f1f252fb826f4ac86ab9a00e7ce4" +
            "7d002a56e7c4b51eb889acc60ca6adbc9f72e81f4d31b1dd7464805264530ab1d"
    private const val RSA_EXPONENT = 65537L

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    /**
     * 取一对 QIMEI。失败返回 `null` —— **由调用方决定是否致命**。
     *
     * 这里刻意不抛：QIMEI 的作用是让请求看起来像来自一台真机，
     * 而"这次拿不到"与"这份凭证不合法"是两件完全不同的事，
     * 在引导阶段把后者当前者处理，会让一个瞬时网络抖动表现成登录失败。
     */
    suspend fun bootstrap(client: OkHttpClient, profile: QqDeviceProfile): Pair<String, String>? =
        withContext(Dispatchers.IO) {
            try {
                val timestamp = System.currentTimeMillis() / 1000L
                val cryptKey = randomHex(16)
                // 16 个**字符**的 hex 串原样当 16 字节密钥（UTF-8），不是 hex 解码。
                // 上游注释专门交代过这一条：这是协议要的形状。
                val keyBytes = cryptKey.toByteArray(Charsets.UTF_8)
                val nonce = randomHex(16)

                val encryptedKey = Base64.encodeToString(rsaEncrypt(keyBytes), Base64.NO_WRAP)
                val payloadJson = buildPayload(profile).toString()
                val encryptedPayload = Base64.encodeToString(
                    aesCbcEncrypt(keyBytes, payloadJson.toByteArray(Charsets.UTF_8)),
                    Base64.NO_WRAP,
                )
                val extra = """{"appKey":"$APP_KEY"}"""
                val params = JSONObject().apply {
                    put("key", encryptedKey)
                    put("params", encryptedPayload)
                    put("time", timestamp.toString())
                    put("nonce", nonce)
                    put(
                        "sign",
                        md5(encryptedKey, encryptedPayload, (timestamp * 1000L).toString(), nonce, SECRET, extra),
                    )
                    put("extra", extra)
                }
                val body = JSONObject().apply {
                    put("app", 0)
                    put("os", 1)
                    put("qimeiParams", params)
                }

                val request = Request.Builder()
                    .url(URL)
                    .header("Host", "api.tencentmusic.com")
                    .header("method", "GetQimei")
                    .header("service", "trpc.tme_datasvr.qimeiproxy.QimeiProxy")
                    .header("appid", "qimei_qq_android")
                    .header(
                        "sign",
                        md5("qimei_qq_android", "pzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH", timestamp.toString()),
                    )
                    .header("user-agent", "QQMusic")
                    .header("timestamp", timestamp.toString())
                    .post(body.toString().toRequestBody(jsonType))
                    .build()

                client.newCall(request).execute().use { response ->
                    // OkHttp 5 起 `Response.body` 是非空的（4.x 才是可空），所以不需要 `?.`。
                    val text = response.body.string()
                    val outer = asJson(text)
                    val inner = asJson(outer?.opt("data"))
                    val data = asJson(inner?.opt("data"))
                    val q16 = data?.optString("q16").orEmpty()
                    val q36 = data?.optString("q36").orEmpty()
                    QqLog.i(TAG, "QIMEI http=${response.code} q16=${q16.length} q36=${q36.length}")
                    if (q16.isEmpty() || q36.isEmpty()) null else q16 to q36
                }
            } catch (t: Throwable) {
                QqLog.w(TAG, "QIMEI 引导失败（非致命）：${t.javaClass.simpleName}: ${t.message}")
                null
            }
        }

    /** 一层可能是对象、也可能是字符串化 JSON 的中间层。 */
    private fun asJson(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun rsaEncrypt(plain: ByteArray): ByteArray {
        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(RSA_MODULUS_HEX, 16), BigInteger.valueOf(RSA_EXPONENT)),
        )
        return Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, key) }
            .doFinal(plain)
    }

    private fun aesCbcEncrypt(keyBytes: ByteArray, plain: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
            // 协议的 AES 步骤：同样 16 字节既作密钥又作 IV。上游显式这么写的。
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(keyBytes))
        }.doFinal(plain)

    /** 上游的两个 `sign` 都是**多段拼接后取一次 MD5**，不是逐段链式。 */
    private fun md5(vararg parts: String): String {
        val digest = MessageDigest.getInstance("MD5")
        parts.forEach { digest.update(it.toByteArray(Charsets.UTF_8)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(n: Int): String {
        val rng = SecureRandom()
        return buildString { repeat(n) { append("0123456789abcdef"[rng.nextInt(16)]) } }
    }

    /**
     * 上报体。字段名与形状照上游 `buildQimeiPayload` —— 这里面**没有一项是我们能解释的**，
     * 全部原样照抄。唯一改动是把 `beaconIdSrc` 的日期部分按当天算（上游如此）。
     */
    private fun buildPayload(profile: QqDeviceProfile): JSONObject {
        val uptime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(System.currentTimeMillis() - SecureRandom().nextInt(14401) * 1000L))

        val reserved = JSONObject().apply {
            put("harmony", "0"); put("clone", "0"); put("containe", "")
            put("oz", "UhYmelwouA+V2nPWbOvLTgN2/m8jwGB+yUB5v9tysQg=")
            put("oo", "Xecjt+9S1+f8Pz2VLSxgpw==")
            put("kelong", "0"); put("uptimes", uptime); put("multiUser", "0")
            put("bod", profile.brand); put("dv", profile.device)
            put("firstLevel", ""); put("manufact", profile.brand)
            put("name", profile.model); put("host", "se.infra")
            put("kernel", profile.procVersion)
        }

        return JSONObject().apply {
            put("androidId", profile.androidId)
            put("platformId", 1)
            put("appKey", APP_KEY)
            put("appVersion", "14.9.0.8")
            put("beaconIdSrc", beaconId())
            put("brand", profile.brand)
            put("channelId", CHANNEL_ID)
            put("cid", ""); put("imei", profile.imei); put("imsi", ""); put("mac", "")
            put("model", profile.model)
            put("networkType", "unknown"); put("oaid", "")
            put("osVersion", "Android ${profile.osRelease},level ${profile.sdk}")
            put("qimei", ""); put("qimei36", "")
            put("sdkVersion", "1.2.13.6")
            put("targetSdkVersion", "33")
            put("audit", ""); put("userId", "{}")
            put("packageId", PACKAGE_ID)
            put("deviceType", "Phone"); put("sdkName", "")
            put("reserved", reserved.toString())
        }
    }

    /** 40 个 `kN:值`。下标落在 [DATED_FIELDS] 的用"当月日期 + 随机数"，其余是纯随机数。 */
    private fun beaconId(): String {
        val month = SimpleDateFormat("yyyy-MM", Locale.US).format(Date()) + "-01"
        val first = digits(6)
        val second = digits(9)
        val fields = (1..40).map { key ->
            when {
                key in DATED_FIELDS -> "k$key:$month$first.$second"
                key == 3 -> "k3:0000000000000000"
                key == 4 -> "k4:${randomHex(16).replace('0', '1')}"
                else -> "k$key:${SecureRandom().nextInt(10000)}"
            }
        }
        return fields.joinToString(";", postfix = ";")
    }

    private fun digits(n: Int): String {
        val rng = SecureRandom()
        return buildString { repeat(n) { append(rng.nextInt(10)) } }
    }

    private val DATED_FIELDS =
        setOf(1, 2, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30, 33, 34, 37, 38)
}
