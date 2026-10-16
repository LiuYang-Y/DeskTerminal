package com.qingke.desk.feature.media.music.online

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

/**
 * QQ 音乐扫码登录的 MQTT 通道（`qq` = QQ App 扫码，push 模式）。
 *
 * ---------------------------------------------------------------------------
 * 为什么手机端能持这条长连接
 *
 * 有没有 qq 通道**完全由"谁能持长连接"决定**。
 * 短生命周期的服务端运行时挂不住一条要在二维码有效期内一直开着的 WSS，只能宣告 wechat；
 * 而手机 App 天然能一直持有它。
 *
 * 但那条长连接本身**没有任何服务端要求**：`wss://mu.y.qq.com/ws/handshake` 是标准 WS 握手
 * 且**不校验 Origin**（2026-10-14 手写握手实测：101 + `Sec-WebSocket-Protocol: mqtt`）。
 * 原生 App 持一条长连接是免费的 —— 这就是"客户端自己做完登录"的全部依据。
 *
 * ---------------------------------------------------------------------------
 * 三条照抄上游、**不能"看起来差不多"** 的地方
 *
 * 1. `encodeUtf8` 的 2 字节长度前缀是 **UTF-8 字节数**，不是 `String.length`（那是 UTF-16 单元数）。
 *    中文/emoji 上会差。
 * 2. 变长整数是 MQTT 自己的编码（每字节 7 位数据 + 最高位续接），不是普通 varint。
 * 3. PUBLISH 的解析里，跳过 topic 之后**若 QoS > 0 还要再跳 2 字节 packetId**，然后才是 properties，
 *    properties 之后剩下的整段才是 payload。少跳这 2 字节，payload 会从中间裂开，
 *    症状是 JSON 解析失败 → 表现成"扫了码但一直没反应"。
 *
 * ---------------------------------------------------------------------------
 * `ready` 的语义是 **SUBACK，不是 connect**（🔴 上游专门标注过）
 *
 * CONNECT 请求的是 clean session，订阅又是 unicast —— 上游**既不保留也不重放**订阅之前发出的事件。
 * 所以"码显示早了"就是"这个码的 scanned/cookies 事件会直接丢掉"。
 * [listen] 因此把 [onReady] 挂在 SUBACK 之后：**调用方必须先等到 onReady 再把二维码画给用户**。
 */
class QqMqttLogin(private val client: OkHttpClient) {

    /**
 * `WebSocket.send(ByteArray)` 的重载。
 *
 * OkHttp **5.x 去掉了 `send(ByteArray)`**，只剩 `send(String)` 与 `send(ByteString)`
 * （4.x 还有那个重载，照着 4.x 的文档写会直接编译失败：`None of the following candidates is applicable`）。
 * MQTT 的包天然是字节，所以这里补一个 —— 比在每个调用点写 `toByteString()` 少一处漏改的机会。
 */
private fun WebSocket.sendBytes(bytes: ByteArray): Boolean = send(bytes.toByteString())

/** 二维码登录事件。与上游 `onQrEvent` 的 `type` 一一对应。 */
    sealed interface QrEvent {
        data object Waiting : QrEvent

        /** 已扫码，等用户在手机上确认。 */
        data object Scanned : QrEvent

        /** MQTT 推来的一次性 token。**它不是最终凭证** —— 还要拿它去换。 */
        data class Cookies(val musicId: String, val token: String) : QrEvent

        /** 用户取消 / 拒绝 / 码过期。上游把三者都算作"会话结束"。 */
        data object Canceled : QrEvent

        data object TimedOut : QrEvent
    }

    /**
     * 连上、订阅、然后一直收到终态或超时。
     *
     * @param timeoutMs 二维码的有效期（上游 `QR_TTL_MS` = 3 分钟）。
     * @param onReady 订阅成功的回调。**必须等它再显示二维码**，理由见类注释。
     */
    suspend fun listen(
        qrcodeId: String,
        timeoutMs: Long,
        onReady: () -> Unit,
        onEvent: (QrEvent) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var path = INITIAL_PATH
        repeat(MAX_REDIRECTS + 1) { attempt ->
            when (val outcome = runOnce(path, qrcodeId, timeoutMs, onReady, onEvent)) {
                is Outcome.Done -> return@withContext
                is Outcome.Redirect -> {
                    QqLog.i(TAG, "MQTT 重定向 → ${outcome.path}（第 ${attempt + 1} 次）")
                    path = outcome.path
                }
            }
        }
        error("MQTT 重定向次数超限")
    }

    // ---- 一次连接的生命周期 --------------------------------------------------

    private sealed interface Outcome {
        data object Done : Outcome
        data class Redirect(val path: String) : Outcome
    }

    private suspend fun runOnce(
        path: String,
        qrcodeId: String,
        timeoutMs: Long,
        onReady: () -> Unit,
        onEvent: (QrEvent) -> Unit,
    ): Outcome {
        val assembler = PacketAssembler()
        val socket = openSocket(path, assembler)
        val scope = CoroutineScope(Dispatchers.IO + Job())
        var pingJob: Job? = null

        try {
            socket.sendBytes(buildConnectPacket("${System.currentTimeMillis()}${randomDigits(4)}", qrcodeId))
            // 诊断探针：CONNACK 等待期间每 5s 发一个 PINGREQ。MQTT 规范要求服务端对
            // PINGREQ 必须回 PINGRESP —— 它回了，说明服务端在收我们的字节，问题在
            // "它不认这个 CONNECT"；它不回，问题在更早的层（path/升级/后端路由）。
            val probeJob = scope.launch {
                while (isActive) {
                    delay(5_000)
                    runCatching { socket.sendBytes(PINGREQ) }
                }
            }
            val connack = awaitConnack(assembler)
            probeJob.cancel()
            val reason = parseConnackReason(connack)
            if (reason != 0) {
                socket.close(1000, null)
                // 0x9c / 0x9d 是 MQTT 的"用另一个服务"：带着 serverReference 换个 path 重连。
                if (reason == REASON_USE_ANOTHER || reason == REASON_SERVER_MOVED) {
                    val reference = parseConnackServerReference(connack)
                    if (!reference.isNullOrEmpty()) {
                        return Outcome.Redirect(redirectPath(path, reference))
                    }
                }
                error("MQTT CONNACK 被拒：0x${reason.toString(16)}")
            }

            pingJob = scope.launch {
                while (isActive) {
                    delay(PING_INTERVAL_MS)
                    runCatching { socket.sendBytes(PINGREQ) }
                }
            }

            socket.sendBytes(buildSubscribePacket(qrcodeId))
            QqLog.i(TAG, "SUBSCRIBE 已发，等 SUBACK")
            awaitSubAck(assembler)

            // ★ 到这里才让调用方显示二维码 —— 早一帧就可能漏掉事件。
            onReady()

            consumeEvents(assembler, timeoutMs, onEvent)
            return Outcome.Done
        } finally {
            pingJob?.cancel()
            runCatching { socket.close(1000, null) }
        }
    }

    /** 等 CONNACK（type 2）。诊断期间可能先到 PINGRESP（type 13），记日志后继续等。 */
    private suspend fun awaitConnack(assembler: PacketAssembler): ByteArray {
        while (true) {
            val packet = assembler.next(HANDSHAKE_TIMEOUT_MS)
            when (packetType(packet)) {
                2 -> {
                    val reason = runCatching { parseConnackReason(packet) }.getOrDefault(-1)
                    QqLog.i(TAG, "CONNACK 收到（${packet.size} 字节 reason=0x${reason.toString(16)}）")
                    return packet
                }
                13 -> QqLog.i(TAG, "PINGRESP 已回 —— 服务端在收我们的字节")
                else -> QqLog.i(TAG, "CONNACK 前收到包 type=${packetType(packet)}（${packet.size} 字节）")
            }
        }
    }

    private fun openSocket(path: String, assembler: PacketAssembler): WebSocket {
        val request = Request.Builder()
            .url("$MQTT_HOST$path")
            // ⚠️ 这个头就是子协议。OkHttp 的 WebSocket 从它构造
            // `Sec-WebSocket-Protocol`，服务端回的是 `mqtt`（手写握手实测过）。
            .header("Sec-WebSocket-Protocol", MQTT_PROTOCOL)
            .build()
        // 打实际 URL：验证 OkHttp 没把 path 里的冒号编码成 %3A（那会让服务端
        // 路由到不存在的后端，症状同样是"连接成功但永远没有 CONNACK"）。
        QqLog.i(TAG, "WS 请求 url=${request.url}")
        return client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                    // 诊断：把收到的原始字节整个打出来（截前 120 字节）。MQTT 包边界与
                    // WebSocket 消息边界无关，只有看到原始字节才能区分"卡在切包"还是"卡在解析"。
                    val raw = bytes.toByteArray()
                    val hex = raw.take(120).joinToString("") { "%02x".format(it) }
                    QqLog.i(TAG, "WS 收到 ${raw.size} 字节：$hex${if (raw.size > 120) " …(截断)" else ""}")
                    assembler.feed(raw)
                }

                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    QqLog.i(TAG, "WS 已打开（path=$path http=${response.code}）")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    // response 非空 = HTTP 升级层被拒（如 403/404）；为空 = 升级完成后断（EOF 等）。
                    // 两者是不同的病：前者查 path/头，后者查字节层与服务端。
                    QqLog.w(
                        TAG,
                        "MQTT 连接断开：${t.javaClass.simpleName}: ${t.message}" +
                            (response?.let { "（http=${it.code}）" } ?: ""),
                    )
                    assembler.fail(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    assembler.fail(IllegalStateException("MQTT 连接已关闭（$code）"))
                }
            },
        )
    }

    /** 等 SUBACK。它自己的包类型是 9，最后一个字节是 reason code。 */
    private suspend fun awaitSubAck(assembler: PacketAssembler) {
        while (true) {
            val packet = assembler.next(HANDSHAKE_TIMEOUT_MS)
            val type = packetType(packet)
            if (type != TYPE_SUBACK) {
                QqLog.i(TAG, "等 SUBACK 期间收到包 type=$type（${packet.size} 字节）")
                continue
            }
            val reason = packet.last().toInt() and 0xFF
            QqLog.i(TAG, "SUBACK 收到，reason=0x${reason.toString(16)}")
            if (reason >= 0x80) error("MQTT SUBACK 被拒：0x${reason.toString(16)}")
            return
        }
    }

    /**
     * 消费事件，直到出现终态或总超时。
     *
     * 与上游 `consumeQrEvents` 一致：`cookies` / `canceled` / `timeout` / `loginFailed` 出现即结束。
     * 本地超时也合成一个 [QrEvent.TimedOut] —— 服务端不发超时事件的时候，
     * 没有这一条会让界面永远停在"等待扫码"。
     */
    private suspend fun consumeEvents(
        assembler: PacketAssembler,
        timeoutMs: Long,
        onEvent: (QrEvent) -> Unit,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                onEvent(QrEvent.TimedOut)
                return
            }
            val packet = try {
                assembler.next(remaining)
            } catch (t: Throwable) {
                // 超时/断连都当作"码没用了"。这里**不往上抛**：
                // 一次握手期的网络抖动与"用户没扫"对用户是同一件事。
                QqLog.i(TAG, "MQTT 事件循环结束：${t.javaClass.simpleName}")
                onEvent(QrEvent.TimedOut)
                return
            }
            if (packetType(packet) != TYPE_PUBLISH) continue
            val event = parsePublish(packet) ?: continue
            onEvent(event)
            if (event is QrEvent.Cookies || event is QrEvent.Canceled || event is QrEvent.TimedOut) return
        }
    }

    // ---- 解析 ---------------------------------------------------------------

    /**
     * PUBLISH → [QrEvent]。
     *
     * 事件类型藏在 **properties 的 userProperty `type`** 里（不是 topic、也不是 payload 字段）。
     * payload 才是 JSON，形状由 type 决定：`cookies` 事件带
     * `{cookies:{qqmusic_uin:{value}, qqmusic_key:{value}}}`。
     *
     * ⚠️ `qqmusic_uin` 实测是**对象带 `value` 字段**（上游取的就是 `.value`）。
     * 这里两种形状都认 —— 上游换过一次形状的话，只认一种会表现成"扫了码但登录没反应"。
     */
    private fun parsePublish(packet: ByteArray): QrEvent? {
        val remaining = decodeVariableInteger(packet, 1) ?: return null
        var cursor = 1 + remaining.bytes
        cursor = readUtf8(packet, cursor).next
        val qos = ((packet[0].toInt() and 0xFF) shr 1) and 0x03
        if (qos > 0) cursor += 2
        val properties = parseProperties(packet, cursor)
        val type = properties.userProperties["type"]
        val payloadText = String(packet, properties.next, packet.size - properties.next, Charsets.UTF_8)
        val payload = if (payloadText.isBlank()) null
        else runCatching { JSONObject(payloadText) }.getOrNull()

        return when (type) {
            "waiting" -> QrEvent.Waiting
            "scanned" -> QrEvent.Scanned
            "cookies", "authorized" -> {
                val cookies = payload?.optJSONObject("cookies") ?: return null
                val musicId = cookieValue(cookies, "qqmusic_uin")
                val token = cookieValue(cookies, "qqmusic_key")
                if (musicId.isEmpty() || token.isEmpty()) null else QrEvent.Cookies(musicId, token)
            }
            // 上游把"用户取消 / 被拒 / 上游判失败"都收进 expired 一个状态。
            "canceled", "timeout", "loginFailed" -> QrEvent.Canceled
            else -> null
        }
    }

    private fun cookieValue(cookies: JSONObject, name: String): String {
        val raw = cookies.opt(name) ?: return ""
        return when (raw) {
            is JSONObject -> raw.optString("value").trim()
            is String -> raw.trim()
            is Number -> raw.toLong().toString()
            else -> ""
        }
    }

    private class Str(val value: String, val next: Int)

    private fun readUtf8(buffer: ByteArray, offset: Int): Str {
        val length = ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        val start = offset + 2
        return Str(String(buffer, start, length, Charsets.UTF_8), start + length)
    }

    private class Properties(
        val userProperties: Map<String, String>,
        val serverReference: String?,
        val next: Int,
    )

    private fun parseProperties(buffer: ByteArray, offset: Int): Properties {
        val length = decodeVariableInteger(buffer, offset) ?: error("MQTT properties 截断")
        var cursor = offset + length.bytes
        val end = cursor + length.value
        val users = HashMap<String, String>()
        var reference: String? = null
        while (cursor < end) {
            val id = buffer[cursor].toInt() and 0xFF
            cursor += 1
            when (id) {
                0x26 -> {
                    val key = readUtf8(buffer, cursor)
                    val value = readUtf8(buffer, key.next)
                    users[key.value] = value.value
                    cursor = value.next
                }
                // 0x1C = serverReference（重定向要用）；0x1F = reasonString（只跳过）。
                0x1C -> {
                    val value = readUtf8(buffer, cursor)
                    reference = value.value
                    cursor = value.next
                }
                0x1F -> cursor = readUtf8(buffer, cursor).next
                else -> cursor = skipProperty(buffer, cursor, id)
            }
        }
        return Properties(users, reference, end)
    }

    /**
     * 按 MQTT 5.0 的属性长度表跳过不关心的字段。
     *
     * 这一段**不能省**：上游注入的属性（会话过期间隔、最大包长、reason string…）会出现在
     * CONNACK / PUBLISH 里，遇到不认识的就抛错会让登录在"上游改了个小属性"时直接挂掉。
     * 长度表照抄上游 `skipProperty`。
     */
    private fun skipProperty(buffer: ByteArray, offset: Int, id: Int): Int = when (id) {
        in UTF8_PROPERTIES -> readUtf8(buffer, offset).next
        in U16_PROPERTIES -> offset + 2
        in U32_PROPERTIES -> offset + 4
        in BYTE_PROPERTIES -> offset + 1
        in BINARY_PROPERTIES -> offset + 2 + readUInt16(buffer, offset)
        0x0B -> {
            val value = decodeVariableInteger(buffer, offset) ?: error("MQTT 订阅标识截断")
            offset + value.bytes
        }
        else -> error("不支持的 MQTT 属性 0x${id.toString(16)}")
    }

    private fun readUInt16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)

    private class VarInt(val value: Int, val bytes: Int)

    private fun decodeVariableInteger(buffer: ByteArray, offset: Int): VarInt? {
        var multiplier = 1
        var value = 0
        for (i in 0 until 4) {
            if (offset + i >= buffer.size) return null
            val digit = buffer[offset + i].toInt() and 0xFF
            value += (digit and 0x7F) * multiplier
            if (digit and 0x80 == 0) return VarInt(value, i + 1)
            multiplier *= 128
        }
        error("MQTT 变长整数畸形")
    }

    /**
     * MQTT 包类型 = 首字节高 4 位。
     *
     * ⚠️ 必须 `and 0xFF`。`Byte.toInt()` 会**符号扩展**：首字节 ≥ 0x80 的包
     * （SUBACK 0x90 / PINGREQ 0xC0 / PINGRESP 0xD0）toInt() 是负数，`shr 4` 会算出
     * -7 / -4 / -3 这种值，与 TYPE_* 常量永不相等。症状极具迷惑性：**CONNACK 用 0x20
     * （正数）所以"能连上"，SUBACK 一到就永远判不出类型、死等下去**
     * （2026-10-14 一加真机：收到 `901f0001...` 却打印 type=-7）。
     */
    private fun packetType(packet: ByteArray): Int = (packet[0].toInt() and 0xFF) shr 4

    private fun parseConnackReason(packet: ByteArray): Int {
        val remaining = decodeVariableInteger(packet, 1) ?: error("MQTT CONNACK 截断")
        val offset = 1 + remaining.bytes
        return packet[offset + 1].toInt() and 0xFF
    }

    private fun parseConnackServerReference(packet: ByteArray): String? {
        val remaining = decodeVariableInteger(packet, 1) ?: return null
        val offset = 1 + remaining.bytes
        return runCatching { parseProperties(packet, offset + 2).serverReference }.getOrNull()
    }

    // ---- 构造 ---------------------------------------------------------------

    private fun encodeVariableInteger(input: Int): ByteArray {
        var value = input
        val bytes = ArrayList<Byte>(4)
        do {
            var digit = value % 128
            value /= 128
            if (value > 0) digit = digit or 0x80
            bytes.add(digit.toByte())
        } while (value > 0)
        return bytes.toByteArray()
    }

    /** 2 字节大端长度 + UTF-8 内容。**长度是字节数**。 */
    private fun encodeUtf8(value: String): ByteArray {
        val data = value.toByteArray(Charsets.UTF_8)
        return byteArrayOf((data.size shr 8).toByte(), data.size.toByte()) + data
    }

    private fun encodeProperties(authMethod: String?, properties: List<Pair<String, String>>): ByteArray {
        val chunks = ArrayList<Byte>(64)
        if (authMethod != null) {
            chunks.add(0x15)
            encodeUtf8(authMethod).forEach { chunks.add(it) }
        }
        properties.forEach { (key, value) ->
            chunks.add(0x26)
            encodeUtf8(key).forEach { chunks.add(it) }
            encodeUtf8(value).forEach { chunks.add(it) }
        }
        val data = chunks.toByteArray()
        return encodeVariableInteger(data.size) + data
    }

    private fun wrapPacket(header: Int, body: ByteArray): ByteArray =
        byteArrayOf(header.toByte()) + encodeVariableInteger(body.size) + body

    private fun buildConnectPacket(clientId: String, qrcodeId: String): ByteArray {
        // ⚠️ 协议名是 "MQTT"（大写，MQTT 规范写死的魔法名），**不是** WebSocket 子协议的
        // "mqtt"（小写）。两者共用一个常量时服务端对 CONNECT 的反应是：多数后端直接不理
        // （20 秒收不到 CONNACK），个别后端回一个 redirect —— 症状与"网络问题"一模一样，
        // 全靠 PC 上同字节复现实验才劈开（2026-10-14 真机 vs PC 对照）。
        val properties = encodeProperties(
            authMethod = "pass",
            properties = listOf(
                "tmeAppID" to "qqmusic",
                "business" to "management",
                "hashTag" to qrcodeId,
                "clientTag" to "management.user",
                "userID" to qrcodeId,
            ),
        )
        val body = encodeUtf8(MQTT_CONNECT_PROTOCOL_NAME) +
            byteArrayOf(0x05, 0x02) +      // 协议版本 5 + 干净的会话
            byteArrayOf(0x00, 45) +        // keepAlive = 45 秒
            properties +
            encodeUtf8(clientId)
        return wrapPacket(0x10, body)
    }

    private fun buildSubscribePacket(qrcodeId: String): ByteArray {
        val properties = encodeProperties(
            authMethod = null,
            properties = listOf("authorization" to "tmelogin", "pubsub" to "unicast"),
        )
        val body = byteArrayOf(0x00, 0x01) +   // packetId
            properties +
            encodeUtf8("management.qrcode_login/$qrcodeId") +
            byteArrayOf(0x00)                  // QoS 0
        return wrapPacket(0x82, body)
    }

    private fun randomDigits(n: Int): String {
        val rng = java.security.SecureRandom()
        return buildString { repeat(n) { append(rng.nextInt(10)) } }
    }

    private fun redirectPath(path: String, reference: String): String {
        val parts = path.trimEnd('/').split('/').toMutableList()
        if (parts.lastOrNull()?.contains(':') == true) parts[parts.size - 1] = reference
        else parts.add(reference)
        return parts.joinToString("/")
    }

    // ---- 包重组 --------------------------------------------------------------

    /**
     * 把 WebSocket 的字节流按 MQTT 包边界切开。
     *
     * **必须做这一步**：WebSocket 的 message 边界与 MQTT 的包边界没有关系 ——
     * 一个 message 里可能是两个包（粘包），也可能只有半个（拆包）。
     * 直接拿 message 当包解析，症状是"大部分时候能用，偶尔一个事件被吃掉"。
     */
    private class PacketAssembler {

        private val lock = Any()
        private var buffered = ByteArray(0)
        private val queue = ArrayDeque<ByteArray>()
        private var terminal: Throwable? = null
        private var waiter: CompletableDeferred<ByteArray>? = null

        fun feed(bytes: ByteArray) = synchronized(lock) {
            val all = buffered + bytes
            var cursor = 0
            while (cursor < all.size) {
                val remaining = decodeVarInt(all, cursor + 1) ?: break
                val end = cursor + 1 + remaining.second + remaining.first
                if (end > all.size) break
                queue.addLast(all.copyOfRange(cursor, end))
                cursor = end
            }
            buffered = all.copyOfRange(cursor, all.size)
            drain()
        }

        fun fail(t: Throwable) = synchronized(lock) {
            if (terminal == null) terminal = t
            drain()
        }

        private fun drain() {
            val pending = waiter ?: return
            val packet = queue.pollFirst()
            if (packet != null) {
                waiter = null
                pending.complete(packet)
            } else {
                terminal?.let {
                    waiter = null
                    pending.completeExceptionally(it)
                }
            }
        }

        suspend fun next(timeoutMs: Long): ByteArray {
            val deferred = synchronized(lock) {
                queue.pollFirst()?.let { return it }
                terminal?.let { throw it }
                CompletableDeferred<ByteArray>().also { waiter = it }
            }
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (timeout: TimeoutCancellationException) {
                // ⚠️ 必须转成普通异常再抛。TimeoutCancellationException 是 CancellationException
                // 的子类，而 runLogin 用 `catch (CancellationException)` 区分"用户取消"——
                // 让它原样出去，一次服务端无响应就会被当成"用户取消"**静默吞掉**：
                // 协程无声死亡、状态永远停在 Preparing、既无日志也无 Failed（2026-10-14 真机实测）。
                throw java.util.concurrent.TimeoutException("MQTT 包等待超时（${timeoutMs}ms）")
            } finally {
                synchronized(lock) { if (waiter === deferred) waiter = null }
            }
        }

        /** 只解开"长度"这一层；不关心内容。返回 (剩余长度, 长度字段占几字节)。 */
        private fun decodeVarInt(buffer: ByteArray, offset: Int): Pair<Int, Int>? {
            var multiplier = 1
            var value = 0
            for (i in 0 until 4) {
                if (offset + i >= buffer.size) return null
                val digit = buffer[offset + i].toInt() and 0xFF
                value += (digit and 0x7F) * multiplier
                if (digit and 0x80 == 0) return value to (i + 1)
                multiplier *= 128
            }
            return null
        }
    }

    private companion object {
        const val TAG = "QqMqtt"
        const val MQTT_HOST = "wss://mu.y.qq.com"
        const val INITIAL_PATH = "/ws/handshake"
        const val MQTT_PROTOCOL = "mqtt"
        /** CONNECT 包里的协议名。MQTT 规范写死为大写 "MQTT"，与上面的子协议不是一回事。 */
        const val MQTT_CONNECT_PROTOCOL_NAME = "MQTT"
        const val HANDSHAKE_TIMEOUT_MS = 60_000L
        const val PING_INTERVAL_MS = 30_000L
        const val MAX_REDIRECTS = 3

        const val TYPE_PUBLISH = 3
        const val TYPE_SUBACK = 9

        /** 0x9c / 0x9d：`Use another server` / `Server moved`。 */
        const val REASON_USE_ANOTHER = 0x9C
        const val REASON_SERVER_MOVED = 0x9D

        /** PINGREQ：固定两字节。keepAlive 45 秒，30 秒发一次。 */
        val PINGREQ = byteArrayOf(0xC0.toByte(), 0x00)

        // MQTT 5.0 属性长度表（照抄上游 skipProperty）。
        val UTF8_PROPERTIES = intArrayOf(0x03, 0x08, 0x12, 0x15, 0x1A, 0x1C, 0x1F)
        val U16_PROPERTIES = intArrayOf(0x13, 0x21, 0x22, 0x23)
        val U32_PROPERTIES = intArrayOf(0x02, 0x11, 0x18, 0x27)
        val BYTE_PROPERTIES = intArrayOf(0x01, 0x17, 0x19, 0x24, 0x25, 0x28, 0x29, 0x2A)
        val BINARY_PROPERTIES = intArrayOf(0x09, 0x16)
    }
}
