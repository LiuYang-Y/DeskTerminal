package com.qingke.desk.core.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次天气读数。字段是**卡面需要的最小集合**，不是 API 返回的全部 ——
 * 多存一份用不上的字段，以后每次改卡片都要回来重新想"这个字段还有人用吗"。
 *
 * @param sunriseClock / @param sunsetClock `"06:52"` 这种 HH:mm（**北京时间的墙上时钟**）。
 *        它们不是给天气卡用的，是喂给 `Daylight` 决定地图白天/夜间底色的
 *        （见 [LocalHomeRepository] 的说明）。
 */
data class WeatherReading(
    val temperatureC: Int,
    val apparentC: Int,
    val humidityPercent: Int,
    /** 中文天气短语，如"多云"。由 WMO 天气码翻译而来。 */
    val description: String,
    val sunriseClock: String,
    val sunsetClock: String,
    val observedAtMillis: Long,
)

/**
 * 天气数据源。
 *
 * 只有 `fetch()` 一个方法，返回可空：调用方需要的状态只有"有数据 / 没数据"两种，
 * 而失败原因（离线、超时、HTTP 5xx、JSON 变了）对卡片来说没有区别 ——
 * 都显示"暂无数据"。把失败原因也建模进来，就会在 UI 里长出一套没人看的错误分支。
 */
interface WeatherRepository {

    /** 拉一次当前天气。**失败返回 null，不抛异常**。 */
    suspend fun fetch(): WeatherReading?
}

/** 西安。与 `Daylight` 本地兜底算法用的是同一个点，两处不能各写各的。 */
private const val XIAN_LATITUDE = 34.3416
private const val XIAN_LONGITUDE = 108.9398

/**
 * Open-Meteo 天气源（非商业用途免费，数据 CC BY 4.0）。
 *
 * 选它的理由：
 *  · **不需要 API Key** —— 没有 Key 就没有"Key 过期 / 配额用完"这一类只有半年后才发作的故障，
 *    也就不需要在应用里放一个加密的密钥（那东西放哪儿都别扭）；
 *  · 日出日落与当前天气在**同一个响应**里（`daily.sunrise/sunset`），一次请求够用；
 *  · 它明确按 CC BY 4.0 提供数据，署名要求写在卡面上（见天气卡的 footnote）。
 *
 * 请求形态（2026-10-12 定，参数名以官方文档为准）：
 * ```
 * https://api.open-meteo.com/v1/forecast
 *   ?latitude=34.3416&longitude=108.9398
 *   &current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code
 *   &daily=sunrise,sunset
 *   &timezone=Asia%2FShanghai&forecast_days=1
 * ```
 * `timezone=Asia/Shanghai` 是必须的：不传时 Open-Meteo 按 UTC 切"哪一天"，
 * 北京时间凌晨 8 点之前算出来的"今天"其实是昨天，日出日落会**整整错一天**。
 *
 * ## 为什么用 HttpURLConnection 而不是 OkHttp / Retrofit
 *
 * 就两个 GET、一个 JSON，加一个网络库要给 :core:data 引入一条会跟着别的功能一起升级的依赖，
 * 还要为它配一套线程与超时策略。`HttpURLConnection` 是平台自带的，够用，且不留长期包袱。
 * 真到了"多个接口 + 鉴权 + 重试策略"的规模再换，那时换也只动这一个文件。
 */
class OpenMeteoWeatherRepository(
    private val latitude: Double = XIAN_LATITUDE,
    private val longitude: Double = XIAN_LONGITUDE,
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 5_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : WeatherRepository {

    override suspend fun fetch(): WeatherReading? = withContext(io) {
        runCatching { request() }
            .onFailure { WeatherLog.w("fetch failed: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull()
    }

    private fun request(): WeatherReading {
        val url = URL(buildUrl())
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = connection.responseCode
            check(code == HttpURLConnection.HTTP_OK) { "HTTP $code" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } finally {
            // 不 disconnect 的话连接会留在池子里直到 GC，常亮设备上每 10 分钟漏一条。
            runCatching { connection.disconnect() }
        }
    }

    /**
     * 解析响应。
     *
     * 只读需要的字段，**不做完整性校验**：任何一处取不到都会抛 `JSONException`，
     * 由 [fetch] 统一吞成 null。给每个字段写一段"缺失时用什么默认值"的判断，
     * 只会让"响应结构变了"这件事从一次可见的失败变成一张悄悄显示着错误温度的卡片。
     */
    private fun parse(body: String): WeatherReading {
        val root = JSONObject(body)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")

        return WeatherReading(
            temperatureC = current.getDouble("temperature_2m").let { Math.round(it).toInt() },
            apparentC = current.getDouble("apparent_temperature").let { Math.round(it).toInt() },
            humidityPercent = current.getInt("relative_humidity_2m"),
            description = wmoDescription(current.getInt("weather_code")),
            // "2026-10-12T06:52" -> "06:52"
            sunriseClock = daily.getJSONArray("sunrise").getString(0).substringAfter('T'),
            sunsetClock = daily.getJSONArray("sunset").getString(0).substringAfter('T'),
            observedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun buildUrl(): String = buildString {
        append("https://api.open-meteo.com/v1/forecast")
        append("?latitude=").append(latitude)
        append("&longitude=").append(longitude)
        append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code")
        append("&daily=sunrise,sunset")
        // 斜杠必须编码，否则会被当成路径分隔符。
        append("&timezone=Asia%2FShanghai")
        append("&forecast_days=1")
    }
}

/**
 * WMO 天气码 → 中文短语。
 *
 * 官方把码分成"晴 / 多云 / 雾 / 毛毛雨 / 雨 / 雪 / 阵雨 / 雷"几族，
 * 这里压到卡面装得下的长度（205 dp 的卡，字号按宽度反推，两三个字最舒服）。
 * 认不出来的码返回"未知"，而不是猜一个近似的 —— 猜错比说不知道更糟。
 */
private fun wmoDescription(code: Int): String = when (code) {
    0 -> "晴"
    1 -> "晴间多云"
    2 -> "多云"
    3 -> "阴"
    45, 48 -> "雾"
    51, 53, 55 -> "毛毛雨"
    56, 57 -> "冻雨"
    61 -> "小雨"
    63 -> "中雨"
    65 -> "大雨"
    66, 67 -> "冻雨"
    71 -> "小雪"
    73 -> "中雪"
    75 -> "大雪"
    77 -> "米雪"
    80 -> "阵雨"
    81 -> "中阵雨"
    82 -> "强阵雨"
    85 -> "阵雪"
    86 -> "强阵雪"
    95 -> "雷阵雨"
    96, 99 -> "雷暴冰雹"
    else -> "未知"
}

/**
 * 天气请求的日志。
 *
 * 与 `MapLog` / `HostLog` 同样的理由：这台基准机（以及小米 11 Ultra）
 * `adb logcat -d | grep` 恒为空，排查只能靠落盘的文件。
 * 天气在 :core:data 里，拿不到 Context，所以只写 logcat —— 它失败时的表现
 * 是卡面显示"暂无数据"，本身已经是一个可见的信号。
 */
private object WeatherLog {
    private const val TAG = "DeskWeather"

    fun w(message: String) {
        runCatching { android.util.Log.w(TAG, message) }
    }
}
