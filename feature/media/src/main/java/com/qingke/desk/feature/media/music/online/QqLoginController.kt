package com.qingke.desk.feature.media.music.online

import android.content.Context
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * 登录流程的阶段名。失败时随 [QqLoginState.Failed] 一起给出去 ——
 * 上游把"会员不足 / 地区限制 / 下架 / 无该音质"压成同一个错误串，
 * 所以**阶段是我们唯一拿得到的区分度**，不能丢。
 */
internal object QqLoginStage {
    const val PROFILE = "device-profile"
    const val QIMEI = "qimei"
    const val SESSION = "session-bootstrap"
    const val QR_CREATE = "qr-create"
    const val MQTT = "mqtt-listener"
    const val EXCHANGE = "credential-exchange"
    const val STORE = "credential-store"
}

/**
 * 扫码登录的状态机。**整个登录会话的唯一起点与唯一状态源。**
 *
 * ---------------------------------------------------------------------------
 * 为什么它归壳层持有，而不是挂在音乐页里
 *
 * 扫码是**跨页面**的（用户在等二维码时可能切回首页看一眼导航），而它持有一条 MQTT 长连接。
 * 挂在音乐页上，一切页就断连 —— 用户回来时码还在屏幕上，但它已经是个死码了
 * （订阅没了，`scanned`/`cookies` 事件不会重放）。这与 `MediaSessionMonitor` 同性质，
 * 所以按同一条路走：**壳层持有，页面注入，页面只读状态。**
 *
 * ---------------------------------------------------------------------------
 * 两个 client，不是一个
 *
 * HTTP 调用要有超时（一个卡住的请求不该让界面一直转），WebSocket 不能有读超时
 * （空闲是正常的，MQTT 靠 45 秒的 keepAlive 维持）。`OkHttpClient` 这两项是客户端级的，
 * 所以在同一个实例上没法两全 —— 这不是"多建一个对象"的洁癖，是两项配置真的冲突。
 *
 * @param scope 由壳层给（`rememberCoroutineScope()`），登录协程随组合取消，本类不持有需要手动释放的资源。
 */
class QqLoginController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    init {
        // 文件日志的落点。越早 init，越能把"启动即失败"的那一段也记下来。
        QqLog.init(context)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val socketClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 0 = 不限读超时。WebSocket 空闲是常态，保活完全交给 MQTT 的 PINGREQ/PINGRESP
        // （30 秒一次，见 QqMqttLogin.pingJob）。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // ⚠️ 不设 pingInterval。OkHttp 的 WS ping 是**传输层**心跳，而 QQ 音乐的 MQTT 服
        //    务端只回 MQTT 的 PINGRESP、**不回 WebSocket 的 pong**。开着它 20 秒必然
        //    `SocketTimeoutException: sent ping but didn't receive pong` 把好连接判死
        //    （2026-10-14 一加真机实测，日志里 server 明明回了 0xd0 PINGRESP）。
        // ⚠️ 连接池置 0 空闲：登录要先后开两条 WS（直连 → 重定向到 IP:port）。默认池会把
        //    WS#1 关闭后那条**已被 WebSocket 升级过**的 socket 留给 WS#2 复用，服务端在
        //    已建立的 WS 上收到第二次握手就不理 —— 症状是"WS 升级 101 成功但永远等不到
        //    CONNACK"。置 0 强制每条 WS 走全新 TCP+TLS（登录是一次性流程，重连成本可忽略）。
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .build()

    private val store = QqCredentialStore(context)
    private val api = QqMusicuApi(httpClient)
    private val mqtt = QqMqttLogin(socketClient)

    private val _state = MutableStateFlow<QqLoginState>(QqLoginState.LoggedOut)
    val state: StateFlow<QqLoginState> = _state.asStateFlow()

    /** 设备档案只在内存里留一份，避免每次请求都读盘。 */
    private var device: QqDeviceProfile? = null

    private var loginJob: Job? = null

    /**
     * 读盘并判过期。**进应用时调一次**。
     *
     * 过期**不自动删**：留着让"退出登录"成为用户的显式动作，
     * 也让界面能说清"是过期了"而不是"你没登录过" —— 后者会让用户以为是应用把账号弄丢了。
     */
    fun restore() {
        val cred = store.load()
        _state.value = when {
            cred == null -> QqLoginState.LoggedOut
            cred.isExpired() -> QqLoginState.Expired(cred)
            else -> QqLoginState.LoggedIn(cred)
        }
    }

    /**
     * 走完全流程：QIMEI → GetSession → CreateQRCode → MQTT → Login → 落盘。
     *
     * 重入被挡掉（不是幂等操作，理由见 [QqLoginState.Preparing]）。
     */
    fun startLogin() {
        if (loginJob?.isActive == true) {
            QqLog.i(TAG, "已有登录流程在跑，忽略这次请求")
            return
        }
        _state.value = QqLoginState.Preparing
        loginJob = scope.launch { runLogin() }
    }

    /** 用户关掉浮层 / 点取消。**只结束本地流程**，不动盘上已有的凭证。 */
    fun cancelLogin() {
        if (loginJob?.isActive != true) return
        loginJob?.cancel()
        loginJob = null
        QqLog.i(TAG, "登录流程被用户取消")
        if (_state.value !is QqLoginState.LoggedIn && _state.value !is QqLoginState.Expired) {
            _state.value = QqLoginState.LoggedOut
        }
    }

    /** 退出登录：清凭证 + 清 KeyStore 密钥。 */
    fun logout() {
        loginJob?.cancel()
        loginJob = null
        store.clear()
        _state.value = QqLoginState.LoggedOut
    }

    /**
     * 当前可用的凭证。第 2 步的 `ExoPlayerPlaybackSource` 从这里取。
     *
     * 每次读盘而不是回内存：凭证是**加密存**的，留一份明文在内存里没有任何必要 ——
     * 而"内存里那份忘了清"正是最容易被忽略的泄漏路径。
     */
    suspend fun credentialOrNull(): QqCredential? = withContext(Dispatchers.IO) {
        store.load()?.takeIf { !it.isExpired() }
    }

    // ---- 流程 ---------------------------------------------------------------

    private suspend fun runLogin() {
        var stage = QqLoginStage.PROFILE
        try {
            ensureProfile()

            stage = QqLoginStage.QIMEI
            ensureQimei()

            stage = QqLoginStage.SESSION
            ensureSession()

            val profile = device ?: error("设备档案缺失")
            stage = QqLoginStage.QR_CREATE
            val qr = api.createQrCode(profile)
            QqLog.i(TAG, "二维码已创建（ID 长度=${qr.id.length}，有效期=${qr.expiresInSec}s）")

            stage = QqLoginStage.MQTT
            var terminal: QqMqttLogin.QrEvent? = null
            mqtt.listen(
                qrcodeId = qr.id,
                timeoutMs = qr.expiresInSec * 1000L,
                onReady = {
                    // ★ 订阅成功之后才把码画出来。上游是 clean session + unicast 订阅，
                    //   码早显示 = 这个码的 scanned/cookies 事件会被直接丢掉。
                    _state.value = QqLoginState.QrReady(qr.pngBase64, qr.expiresInSec)
                },
                onEvent = { event ->
                    when (event) {
                        QqMqttLogin.QrEvent.Waiting -> Unit   // 码已经显示了，状态不变
                        QqMqttLogin.QrEvent.Scanned -> _state.value = QqLoginState.Scanned
                        is QqMqttLogin.QrEvent.Cookies -> {
                            _state.value = QqLoginState.Exchanging
                            terminal = event
                        }
                        QqMqttLogin.QrEvent.Canceled, QqMqttLogin.QrEvent.TimedOut -> terminal = event
                    }
                },
            )

            val cookies = terminal as? QqMqttLogin.QrEvent.Cookies
            if (cookies == null) {
                // 超时 / 用户取消 / 上游判失败 —— 三者对界面是同一件事：这张码没用了。
                _state.value = QqLoginState.Failed(
                    stage = QqLoginStage.MQTT,
                    reason = if (terminal is QqMqttLogin.QrEvent.Canceled) {
                        "二维码已失效或被取消，请重新获取"
                    } else {
                        "二维码已过期，请重新获取"
                    },
                )
                return
            }

            stage = QqLoginStage.EXCHANGE
            val credential = api.login(device ?: error("设备档案缺失"), qr.id, cookies.musicId, cookies.token)

            stage = QqLoginStage.STORE
            store.save(credential)

            _state.value = QqLoginState.LoggedIn(credential)
            QqLog.i(TAG, "登录成功：${credential.mask()}，有效期至 ${credential.expiresAtMs}")
        } catch (cancellation: CancellationException) {
            throw cancellation     // 取消不是失败，别把它变成 Failed
        } catch (t: Throwable) {
            QqLog.w(TAG, "登录失败于 [$stage]：${t.javaClass.simpleName}: ${t.message}")
            _state.value = QqLoginState.Failed(stage, friendlyReason(stage, t))
        }
    }

    /**
     * 失败原因给界面看的文案。**原始消息照旧进日志**（上面那行 W 脱敏检查过），
     * 这里只替换用户读到的那一句。
     *
     * 唯一需要特殊映射的是 [UPSTREAM_DEVICE_LIMIT]（登录设备数超限，码的出处见
     * [QqDeviceFactory] 的类注释）：原始串"上游拒绝（… upstream=20279）"对用户
     * 没有任何可行动性，而这个错**只能**用户在手机 QQ 音乐的登录设备管理里
     * 踢掉一个设备才能解 —— 文案必须把动作说到位。
     */
    private fun friendlyReason(stage: String, t: Throwable): String {
        if (stage == QqLoginStage.EXCHANGE &&
            t is QqProtocolException &&
            t.upstreamCode == UPSTREAM_DEVICE_LIMIT
        ) {
            return "登录设备数超限：请在手机 QQ 音乐「设置 → 登录设备管理」中" +
                "移除不常用的设备，然后重试"
        }
        return t.message ?: t.javaClass.simpleName
    }

    private fun ensureProfile() {
        if (device == null) device = QqDeviceFactory.loadOrCreate(context)
    }

    /**
     * 取 QIMEI。
     *
     * ⚠️ **拿不到不致命，本次以空 QIMEI 继续** —— 这是本轮刻意选的务实取舍，
     * 并且**尚未验证**：Phase 0 那份凭证里 QIMEI 是齐的，所以"空 QIMEI 上游收不收"
     * 我们没有证据。把它当必需会把一次接口抖动变成一次登录失败；
     * 当成可选则最多是"上游可能拒绝"，而那是**可观察的**（Login 会报错，日志里有阶段名）。
     *
     * 真机跑通后应当回来把这条验掉，并在文档里改成确定结论。
     */
    private suspend fun ensureQimei() {
        val current = device ?: return
        if (current.hasFreshQimei) {
            QqLog.i(TAG, "QIMEI 仍在新鲜期内，复用")
            return
        }
        val pair = QqQimei.bootstrap(httpClient, current)
        if (pair == null) {
            QqLog.w(TAG, "QIMEI 未取到，以空 QIMEI 继续（未验项：上游是否强制要求）")
            return
        }
        val updated = current.copy(
            qimei = pair.first,
            qimei36 = pair.second,
            qimeiSavedAtMs = System.currentTimeMillis(),
        )
        device = updated
        QqDeviceFactory.save(context, updated)
    }

    /**
     * 匿名会话引导（uid / sid）。
     *
     * 同样的取舍：失败只记日志。Phase 0 实测"有 uid/sid 能取到链接"，
     * 但没有实测"没有它就不能" —— 而这一步在上游是关键路径上，说明它大概率有用，
     * 所以**做，但不拿它当一票否决**。
     */
    private suspend fun ensureSession() {
        val current = device ?: return
        try {
            val (uid, sid) = api.getSession(current)
            val updated = current.copy(sessionUid = uid, sessionSid = sid)
            device = updated
            QqDeviceFactory.save(context, updated)
            QqLog.i(TAG, "会话引导完成（uid 长度=${uid.length}，sid 长度=${sid.length}）")
        } catch (t: Throwable) {
            QqLog.w(TAG, "会话引导失败，继续（未验项）：${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "QqLogin"

        /**
         * 上游拒绝码：登录设备数超限。见 [friendlyReason] 与 [QqDeviceFactory] 类注释
         * （设备指纹每次会话重造 → 同一个账号被当成一台又一台新设备 → 攒到这个码）。
         */
        const val UPSTREAM_DEVICE_LIMIT = 20279
    }
}
