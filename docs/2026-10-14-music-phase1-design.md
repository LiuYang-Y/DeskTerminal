# 音乐模块 · Phase 1 设计（扫码登录闭环 + ExoPlayerPlaybackSource 接入）

日期：2026-10-14 ｜ 分支：`spike/music-phase0-qq` ｜ 前置：Phase 0 第二步（`edc9c36`）

**本文是设计先行件**：先定文件结构、类签名与尺寸推导，再写第一行代码。
交付顺序按用户要求 —— 第 1 步（登录落盘）先跑通并真机验证，第 2、3 步随后。

---

## 1. 这一步的位置

Phase 0 证明了「拿到合法凭证后，原生 ExoPlayer 能低开销地把 QQ 音乐放出声」。
但 Phase 0 的凭证是**手动 adb 推上去的文件**，且那份会话 `expiresAt` 只有约 2 天。
所以 Phase 1 的第一件事不是播放，而是**把凭证的来源换成用户自己在设备上扫一次码**。

| 步骤 | 内容 | 状态 |
| --- | --- | --- |
| 1 | 本机扫码登录 + 凭证落盘（替代 adb 传文件）+ 状态/退出入口 | **本次实现** |
| 2 | `ExoPlayerPlaybackSource` + 双源回退 + 歌单 + 音频焦点/MediaSession | 设计已出，待实现 |
| 3 | `TeeAudioProcessor` 挂 PCM 频谱，驱动左右声波圆环 | 设计已出，待实现 |

---

## 2. 为什么登录可以完全放在手机本地做

这一条决定整个架构（**不需要任何自建服务器**），三条都是此前实测的结论：

| 事实 | 出处 | 后果 |
| --- | --- | --- |
| `musicu.fcg` **无签名参数**，凭证只在 `Cookie` 头 | Phase 0 §2 | 一次普通 POST，`HttpURLConnection` 就够 |
| `wss://mu.y.qq.com/ws/handshake` **标准 WS 握手、不校验 Origin** | 2026-10-14 手写握手实测（101 + `Sec-WebSocket-Protocol: mqtt`） | 客户端可自己持长连接 → **不需要 relay / Durable Object** |
| `mobile`(=qq) 扫码是 **push** 通道（MQTT），`wechat` 是 pull | 同上 | 两条通道都可在客户端实现，本轮先做 qq |

短生命周期的服务端运行时挂不住长连接，所以只能宣告 wechat。**本项目是原生 App，长连接是免费的**，
所以这条限制对我们不存在。

---

## 3. 登录协议链路（照 `qrLogin.js` 逐字段复刻）

```
① QIMEI bootstrap   POST api.tencentmusic.com/tme/trpc/proxy  (RSA-PKCS1v1.5 包 AES-128-CBC)
        ↓  拿到 qimei / qimei36（24h 新鲜期）
② GetSession        musicu  music.getSession.session / GetSession      {uid:"", vkey:0, caller:0}
        ↓  拿到 session.uid / session.sid（匿名会话引导）
③ CreateQRCode      musicu  music.login.LoginServer / CreateQRCode     {tmeAppID,ct:11,cv:14090008}
        ↓  comm overrides {ct:23, cv:0}；拿到 qrcodeID + PNG
④ MQTT 连接          wss://mu.y.qq.com/ws/handshake  subprotocol "mqtt"
        CONNECT  clientId = "${Date.now()}${4位随机数}"，props 带 hashTag/userID=qrcodeID/…
        SUBSCRIBE management.qrcode_login/${qrcodeID}
        ↓  ★ 必须等到 SUBACK 才显示二维码（clean session + unicast 订阅，订阅前的事件不重放）
⑤ 等事件             waiting → scanned → cookies
        ↓  cookies 事件带 payload.cookies.qqmusic_uin(=musicid) / qqmusic_key(=token)
⑥ Login             musicu  music.login.LoginServer / Login  {musicid:Int, qrCodeID, token}
        ↓  comm overrides {tmeLoginType: 6}
⑦ 凭证              credentialFrom(data)：musicid / musickey / loginType(=6) / refresh_key / …
                    expiresAt = clamp(musickeyCreateTime + keyExpiresIn, now+1h, now+7d)
```

**逐字节实现的三处要点**（写代码时照抄，不要"看起来差不多"）：

- `encodeUtf8` 的 2 字节长度前缀是 **UTF-8 字节数**，不是 `String.length`（UTF-16 单元数）。
- `encodeVariableInteger` 是 MQTT 变长整数（每字节 7 位 + 最高位续接），不是普通 varint。
- PUBLISH 解析：跳过 topic 后，**若 QoS>0 还要跳 2 字节 packetId**，然后才是 properties，
  properties 之后剩下的整段才是 payload（JSON 字符串的 UTF-8）。

**密码学原语全部有原生对应**（这是"能不能移植"的第一优先判据）：

| 协议用到的 | Kotlin 对应 |
| --- | --- |
| MD5（QIMEI 的两个 sign） | `MessageDigest.getInstance("MD5")` |
| RSAES-PKCS1-v1_5 加密（包 AES 密钥） | `Cipher.getInstance("RSA/ECB/PKCS1Padding")` |
| AES-128-CBC（QIMEI payload，key=iv=16 字节 hex 串的 UTF-8） | `Cipher.getInstance("AES/CBC/PKCS5Padding")` |
| 随机数 | `SecureRandom` |

**没有 WASM、没有混淆 VM、没有 Node 专有 API** —— 全部是标准加密原语，Kotlin 可直接复刻。

---

## 4. 文件结构

全部落在 `:feature:media` 内，**不新建模块**（这些代码与音乐页同生共死，
拆模块只会多一层 Gradle 配置与一条 `api/implementation` 边界）。

```
feature/media/src/main/java/com/qingke/desk/feature/media/music/
├── PlaybackSource.kt                    （已有，本步不改）
├── MediaSessionPlaybackSource.kt        （已有，本步不改）
├── MusicLayout.kt                       （改：+登录浮层与选歌抽屉的尺寸）
├── MusicHud.kt                          （改：+右上账号 chip）
├── MusicPage.kt                         （改：+浮层挂载、登录状态读取）
└── online/                              ← 新增子树
    ├── QqAuthModels.kt          凭证 / 设备档案 / 登录状态（纯数据 + 脱敏渲染）
    ├── QqDeviceProfile.kt       合成设备指纹 + QIMEI bootstrap + 持久化
    ├── QqMusicuApi.kt           musicu POST 客户端（GetSession / CreateQRCode / Login）
    ├── QqMqttLogin.kt           MQTT over WebSocket（字节协议 + 事件流）
    ├── QqCredentialStore.kt     AndroidKeyStore AES-GCM + filesDir 落盘
    ├── QqLoginController.kt     登录状态机（对外 StateFlow<QqLoginState>）
    └── QqLoginPane.kt           登录浮层（二维码 / 状态 / 退出）

shell/src/main/java/com/qingke/desk/shell/DeskRoot.kt   （改：创建 controller，与 mediaMonitor 同级）
gradle/libs.versions.toml                               （改：+okhttp）
feature/media/build.gradle.kts                          （改：+okhttp 依赖）
```

**为什么 controller 归壳层持有**：登录是一次**跨页面的会话**（用户可能在等扫码时切走），
与 `MediaSessionMonitor` 同性质。壳层 `remember` 一次、页面向下注入 —— 这样切页不中断扫码，
且"是否已登录"这个状态全局只有一个来源。页面只认状态，不认识 MQTT。

---

## 5. 核心类签名

### 5.1 数据（`QqAuthModels.kt`）

```kotlin
/** 一份 QQ 音乐凭证。**含密钥字段，绝不进日志、绝不 toString()。** */
data class QqCredential(
    val musicId: String,        // 账号 id（str_musicid 优先，见 credentialMusicId）
    val musickey: String,       // ★ 密钥
    val loginType: Int,         // 6 = QQ App 扫码
    val nick: String,           // 昵称（可能为空）
    val avatarUrl: String,
    val createdAtMs: Long,      // 落盘时刻
    val expiresAtMs: Long,      // 由上游 musickeyCreateTime + keyExpiresIn 推导，已夹取
) {
    fun isExpired(nowMs: Long) = nowMs >= expiresAtMs
    /** 日志/界面可用的脱敏视图。**唯一允许外流的形式。** */
    fun mask(): String = "uin=${musicId.take(4)}****  key=${musickey.length}字符  type=$loginType"
}

/** 合成设备档案。上游不校验真实性，只要求**稳定**。 */
data class QqDeviceProfile(
    val display: String, val product: String, val device: String, val board: String,
    val model: String, val fingerprint: String, val procVersion: String,
    val imei: String, val brand: String, val androidId: String, val openUdid: String,
    val osRelease: String, val sdk: Int,
    val qimei: String, val qimei36: String, val qimeiSavedAtMs: Long,
    val sessionUid: String, val sessionSid: String,
) {
    val hasFreshQimei: Boolean get() = qimei.isNotEmpty() && qimei36.isNotEmpty() &&
        System.currentTimeMillis() - qimeiSavedAtMs < QIMEI_TTL_MS
}

/** 登录状态的**唯一**对外类型。界面只读它。 */
sealed interface QqLoginState {
    data object LoggedOut : QqLoginState
    /** 未登录且有活跃二维码。 */
    data class QrReady(val pngBase64: String, val expiresInSec: Int) : QqLoginState
    data object Scanned : QqLoginState            // 已扫，等手机确认
    data object Exchanging : QqLoginState         // 收到 cookies，正在换凭证
    data class LoggedIn(val cred: QqCredential) : QqLoginState
    data class Expired(val cred: QqCredential) : QqLoginState
    data class Failed(val stage: String, val reason: String) : QqLoginState
}
```

### 5.2 设备与 QIMEI（`QqDeviceProfile.kt`）

```kotlin
object QqDeviceFactory {
    fun create(): QqDeviceProfile                       // 随机造一台，字段形状照 createAndroidDevice
    fun load(ctx: Context): QqDeviceProfile             // 落盘读；缺失则 create() 并落盘
    fun save(ctx: Context, p: QqDeviceProfile)
}

object QqQimei {
    /** POST api.tencentmusic.com，RSA-PKCS1v1.5 包 AES-128-CBC。成功后回填 qimei/qimei36。 */
    suspend fun bootstrap(profile: QqDeviceProfile): Pair<String, String>?
}
```

### 5.3 musicu 客户端（`QqMusicuApi.kt`）

```kotlin
class QqMusicuApi(private val client: OkHttpClient) {

    /** 统一出口：POST {comm, req_0:{module,method,param}}，凭证在 Cookie 头。无签名。 */
    suspend fun call(
        module: String, method: String,
        param: JSONObject,
        profile: QqDeviceProfile,
        credential: QqCredential? = null,
        commOverrides: Map<String, Any> = emptyMap(),
    ): JSONObject

    suspend fun getSession(profile: QqDeviceProfile): Pair<String, String>   // uid to sid
    suspend fun createQrCode(profile: QqDeviceProfile): QrCode               // id + png base64
    suspend fun login(profile: QqDeviceProfile, qrcodeId: String,
                      musicId: String, token: String): JSONObject           // → QqCredential
    suspend fun loginUserInfo(profile: QqDeviceProfile, cred: QqCredential): JSONObject
}
```

### 5.4 MQTT（`QqMqttLogin.kt`）

```kotlin
/** 二维码登录的事件。与上游 onQrEvent 的 type 一一对应。 */
sealed interface QrEvent {
    data object Waiting : QrEvent
    data object Scanned : QrEvent
    data class Cookies(val musicId: String, val token: String) : QrEvent
    data object Canceled : QrEvent
    data object TimedOut : QrEvent
}

class QqMqttLogin(private val client: OkHttpClient) {
    /**
     * 连上并**订阅成功之后**才回调 onReady —— 上游的 ready 语义是 SUBACK 而非 connect：
     * clean session + unicast 订阅，订阅之前发的事件不重放，码早显示 = 事件会丢。
     */
    suspend fun listen(
        qrcodeId: String,
        timeoutMs: Long,
        onReady: () -> Unit,
        onEvent: (QrEvent) -> Unit,
    )
}
```

### 5.5 落盘（`QqCredentialStore.kt`）

```kotlin
/**
 * 凭证落盘。三层：
 *   ① 位置 —— 应用私有目录 filesDir（非 root 设备其他应用读不到）；
 *   ② 加密 —— AndroidKeyStore 里的 AES-256-GCM 密钥（**密钥不进文件系统**，由 TEE/StrongBox 管）；
 *   ③ 形状 —— 解密后仍要过 isAuthSession 同款校验（被改写/截断的一律拒绝）。
 * 密钥不可用时**拒绝登录并如实报错**，不降级成明文 —— 降级即红线失守。
 */
class QqCredentialStore(private val context: Context) {
    fun save(cred: QqCredential)                 // filesDir/qq-auth.bin
    fun load(): QqCredential?
    fun clear()
    val hasCredential: Boolean
}
```

### 5.6 状态机（`QqLoginController.kt`）

```kotlin
class QqLoginController(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    val state: StateFlow<QqLoginState>

    /** 读盘 + 判过期。构造后调一次。 */
    fun restore()
    /** 走完 ①~⑦，成功后落盘并把状态推到 LoggedIn。 */
    fun startLogin()
    fun cancelLogin()
    fun logout()
    /** 当前凭证。第 2 步的 ExoPlayerPlaybackSource 从这里取。 */
    suspend fun credentialOrNull(): QqCredential?
}
```

---

## 6. 尺寸推导

**唯一输入是 `BoxWithConstraints` 的 `maxWidth` / `maxHeight`**（容器实测约束，不是 `DeviceProfile`），
**每一项都是 `Float`**，落在 `MusicLayout` 里与既有 `metrics()` 同一套写法 —— 这样浮层尺寸
可以直接进 `MusicLayoutTest` 的尺寸矩阵，比例一改就有人来提醒。

### 6.1 登录浮层 `loginPane(w, h): LoginPaneMetrics`

卡片内容上下依次是：标题行 → 二维码方块 → 状态行 → 按钮行。**二维码是弹性项**，
所以约束链的正确方向是「**总高预算 → 二维码边长**」，而不是反过来（反过来会在矮屏上溢出）。

```kotlin
outerY  = (h * 0.045f).coerceIn(8f, 18f)        // 浮层距上下边缘的安全区
padY    = (h * 0.035f).coerceIn(8f, 16f)        // 卡片内上下留白
padX    = (w * 0.020f).coerceIn(12f, 22f)       // 卡片内左右留白

titleSize  = (h * 0.042f).coerceIn(12f, 18f)
titleLine  = titleSize * 1.55f                  // ★ 行高显式，系数与 NoticeLineHeight 同源
statusSize = titleSize * 0.85f
statusLine = statusSize * 1.55f
btnH       = maxOf(MIN_TOUCH_DP, h * 0.115f)    // 48 = Material 触控下限
btnText    = btnH * 0.36f
gapY       = padY * 0.55f

chrome   = padY*2 + titleLine + statusLine + btnH + gapY*3     // 除二维码外的固定开销
qrBudget = (h - outerY*2 - chrome).coerceAtLeast(MIN_QR_DP)
qrSide   = minOf(qrBudget, w * 0.34f).coerceAtLeast(MIN_QR_DP) // w*0.34 只是防超宽的兜底

cardW = qrSide + padX*2
cardH = chrome + qrSide
```

`MIN_QR_DP = 48f`（再小扫不动）。**不变式两条**（进单测）：

- `cardH + outerY*2 <= h` —— 卡片不越出上下安全区；
- `cardW <= w` —— 卡片不超宽。

### 6.2 两台机器代入

| 元素 | 一加 666×361 | 小米 786×411 |
| --- | --- | --- |
| `outerY` | 16.2 | **18.0**（361×0.045=16.2 未触顶；411×0.045=18.5 被 18 夹住） |
| `padY` / `padX` | 12.6 / 13.3 | 14.4 / 15.7 |
| 标题字号 / 行高 | 15.2 / 23.6 | 17.3 / 26.8 |
| 状态字号 / 行高 | 12.9 / 20.0 | 14.7 / 22.8 |
| 按钮高 | 48.0（`max(48, 41.5)`） | 48.0（`max(48, 47.3)`） |
| `chrome` | **137.6** | **150.1** |
| `qrBudget` | 361 − 32.4 − 137.6 = **191.0** | 411 − 36 − 150.1 = **224.9** |
| `qrSide` | **191.0**（宽向兜底 226.4 未生效） | **224.9**（宽向兜底 267.2 未生效） |
| `cardW × cardH` | 217.6 × 328.6 | 256.3 × 375.0 |
| 校验 | 328.6 + 32.4 = **361 = h** ✓；217.6 ≤ 666 ✓ | 375.0 + 36 = **411 = h** ✓；256.3 ≤ 786 ✓ |

两条值得记住的读数：

1. **两台的卡高都恰好顶满**（by construction），而卡宽只用到 33% / 33% ——
   说明横屏矮屏上「高度」是唯一紧的约束，二维码边长该由**高度**定。这正是不写死尺寸的价值：
   若按"二维码固定 160dp"写，一加上下就溢出 361。
2. **`w*0.34` 在两台上都没生效**。它不是没用 —— 它管的是另一种极端（超宽超高时
   二维码不至于变成一个超大正方形），留着是**第二种失效模式**的兜底，不是死代码。

**设计极限（如实写进文档）**：`chrome ≈ 0.36h + 96`，`qrBudget ≈ 0.55h − 96`，
所以 **h ≈ 175dp 时二维码会触到 48dp 下限**。在役机器是 361 / 411（余量 2 倍以上），
但"更矮的屏"必须走回归 —— 这条与 `AdaptiveLayout` 的 `SidebarFit` 是同一类问题。

### 6.3 选歌抽屉 `trackDrawer(w, h): DrawerMetrics`（第 2 步用，本次一并定下）

```kotlin
drawerW   = (w * 0.42f).coerceIn(220f, 420f)
rowH      = maxOf(MIN_TOUCH_DP, h * 0.13f)      // 48 下限；361→48.0，411→53.4
rowGap    = rowH * 0.10f
coverSide = rowH * 0.72f
titleSize = rowH * 0.30f
artistSize= titleSize * 0.78f
visibleRows = ((h - outerY*2) / (rowH + rowGap)).toInt().coerceAtLeast(3)
```

代入：一加 `drawerW=279.7 / rowH=48.0 / cover=34.6`，小米 `drawerW=330.1 / rowH=53.4 / cover=38.5`。
抽屉从右侧推入，`drawerW ≤ w − 侧栏` 由 `coerceIn` 上限 420 与在役宽度（666/786）共同保证。

---

## 7. 未做 / 待验证（不许当成已验证）

### ✅ 2026-10-14 真机已验证（一加 PKR110 / Android 16，无 root）

| 项 | 证据 |
| --- | --- |
| 二维码生成 → 显示 | 状态机到达 `QrReady`；`SUBACK 收到，reason=0x0` |
| 扫码 → 登录闭环 | `Scanned → Exchanging → LoggedIn` 全走通（收到 642 / 630 / 1763 字节的 PUBLISH 事件） |
| 凭证加密落盘 | `files/qq-auth.bin`（321 字节，权限 0600）；`QqCredStore: 凭证已落盘` |
| 重启后自动恢复 | 强杀 + 清日志 + 重启 → 日志仅一行 `状态=LoggedIn`，无重新引导 |
| 日志脱敏 | 只出现 `uin=1508****`、`key=159字符`，无 musickey / refresh_key 明文 |

留档：`.verify/phase1/qq-login-success.log`（81 行完整成功链路）。

**本轮修掉的两个真实 bug** —— 都是"功能也在跑，但跑得不对"，全靠打印原始字节才劈开：

1. **MQTT 包类型符号扩展**（致命）：`packet[0].toInt() shr 4` 漏了 `and 0xFF`。首字节 ≥ 0x80 的包
   （SUBACK `0x90` / PINGREQ `0xC0` / PINGRESP `0xD0`）`toInt()` 为负，`shr 4` 得 -7/-4/-3，与
   `TYPE_*` 常量永不相等 → **永远卡在等 SUBACK，状态停在 Preparing**。CONNACK 是 `0x20`（正数）
   所以"能连上"，迷惑性极强。已收口为单点 `packetType()`。
2. **OkHttp WebSocket ping 误杀**：`pingInterval(20s)` 发的 WS 层 ping 服务端不回 pong（它只回
   MQTT `PINGRESP`），20 秒必然 `SocketTimeoutException` 把好连接判死。已去掉，保活完全交给
   MQTT `PINGREQ`（pingJob）。另把 `socketClient` 连接池置 0 空闲，防 WS#2 复用 WS#1 那条
   已被 WebSocket 升级过的 socket。

### ❓ 仍未验

| 项 | 状态 |
| --- | --- |
| QIMEI bootstrap 失败时能否降级为空串继续 | **未验**。实现里先当作必需，失败即报错 |
| `refresh_key` 能否用于续期（免重扫） | **未验**。上游 WeChat 通道有 refresh 路径，QQ 通道未见 |
| 后台/息屏时登录流程能否存活 | 设计上 controller 归壳层，但**未在真机验过** |
| 小米 11 Ultra 上 WS#2 收不到字节 | **未复验**。一加同一份代码正常。已加 `connectionPool(0)` 待小米回归 |
| 与 `MediaSessionMonitor` 双源冲突 | 第 2 步的问题（自建会话会给 monitor 认出来），本轮不涉及 |
| 凭证过期行为（`Expired` 态 UI） | **未验**。本轮取到的是新鲜凭证，有效期至 1792246479000（约 3 天后） |
| 凭证加密后的**零调试连接**内存对照 | 未做（Phase 0 遗留） |
