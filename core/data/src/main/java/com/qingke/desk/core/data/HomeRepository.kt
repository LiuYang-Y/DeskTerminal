package com.qingke.desk.core.data

import com.qingke.desk.core.model.CardLine
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.model.HomeCardKind
import com.qingke.desk.core.platform.Daylight
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 首页卡片数据源。
 *
 * 观察型接口：卡片内容会自己变（时钟每分钟走一格、天气每十分钟刷一次），
 * 所以是 Flow 而不是一次性的 List。真实实现换成 Room / 天气 API 时签名不变。
 */
interface HomeRepository {

    /** 卡片列表。 */
    fun observeCards(): Flow<List<HomeCard>>
}

/**
 * 时钟用的时区：**东八区，纯偏移**。
 *
 * 用 `"GMT+08:00"` 而不是 `"Asia/Shanghai"`：后者要设备上的 tzdata 里有这个名字才解析得出，
 * 拿不到时 [TimeZone.getTimeZone] 会**静默退回 GMT**（不是抛异常），时钟当场差 8 小时。
 * 这里要的恰恰是一个不含夏令时的固定偏移。
 *
 * 与 :core:platform 的 `Daylight.TIME_ZONE` 是同一个口径（都是 GMT+08:00）：
 * "现在几点"和"日落几点"问的都是同一个墙上时钟，两处不能各算各的。
 */
private val CLOCK_ZONE: TimeZone = TimeZone.getTimeZone("GMT+08:00")

/**
 * 醒来之后再往后多等一点，保证越过整分钟线。
 *
 * 正好卡在 0 秒 0 毫秒上唤醒时，取到的时间戳**可能还差几毫秒**到整分钟，
 * 于是这一次读到的还是"上一分钟"，只能再等一分钟 —— 表现为时钟偶尔停一格。
 * 多睡 50 ms 花不掉什么，但把这一类边界抖动整个消掉。
 */
private const val TICK_GUARD_MILLIS = 50L

/** 天气刷新间隔。十分钟：天气是缓变量，而这是一台 24 小时常亮的设备。 */
private const val WEATHER_REFRESH_MILLIS = 10 * 60 * 1000L

/** 天气卡的城市名。坐标写死在 [OpenMeteoWeatherRepository] 里，这里只是显示用的名字。 */
private const val WEATHER_CITY = "西安"

/** 天气数据缺失时卡面显示的文案。**不再退回到"上一次的假数据"**。 */
private const val WEATHER_EMPTY = "暂无数据"

/**
 * Open-Meteo 的署名（CC BY 4.0 要求）。
 *
 * 放在卡面最底部的小字里，而不是只写在 README：署名要跟着数据一起出现才有意义。
 */
private const val WEATHER_ATTRIBUTION = "数据来自 Open-Meteo"

/** 星期几的中文。`Calendar.DAY_OF_WEEK` 是 1..7 且 1 = 周日。 */
private val WEEKDAY_CN = arrayOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 本地数据源：时钟读系统时间，天气走 [WeatherRepository]（现在是 Open-Meteo）。
 *
 * 2026-10-12 起，首页上**没有一条写死的假数据**了：
 *  · 时钟卡读系统时间（东八区），刷新对齐到整分钟 ——
 *    原来的假时钟不但与真实时间无关，走一分钟还会因为 30 秒打点而在一分钟内
 *    显示两次同一个值、然后跳过一分钟，是"假数据"里最容易被当成 bug 报的那一类；
 *  · 天气卡来自 Open-Meteo，**失败时显示"暂无数据"**，不显示任何旧的示例值；
 *  · 音乐卡不由本仓库提供：首页那张卡由 :feature:media 的 MediaSessionMonitor
 *    驱动（真实播放状态），这里再放一条只会是一份永远不同步的假曲目。
 *
 * 手机投屏 / 日程 / 待办三张卡此前已删除 —— 投屏在侧栏有入口，日程和待办没有真实数据源。
 *
 * @param weather 天气数据源。传 null 表示完全不要天气（测试或离线构建），
 *                此时天气卡恒为"暂无数据"。
 */
class LocalHomeRepository(
    private val weather: WeatherRepository? = null,
) : HomeRepository {

    /**
     * 时钟与天气两条流合并。
     *
     * 为什么用 [combine] 而不是"一条流里什么都干"：两者的**刷新节奏完全不同**
     * （分钟 vs 十分钟），塞进一个 while 循环就只能取两者中最短的那个周期，
     * 天气会被迫每分钟请求一次 —— 那是对别人免费服务的滥用。
     */
    override fun observeCards(): Flow<List<HomeCard>> = combine(
        clockFlow(),
        weatherFlow(),
    ) { now, reading -> cards(now, reading) }

    /** 每分钟对齐一次的时间流。 */
    private fun clockFlow(): Flow<Long> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(now)
            // **对齐到下一个整分钟**而不是固定间隔：固定 30 秒会与"分钟"这个刻度错开，
            // 时钟读数看上去忽快忽慢；对齐之后每一分钟恰好刷新一次，不漏格也不重格。
            delay(millisUntilNextMinute(now) + TICK_GUARD_MILLIS)
        }
    }

    /**
     * 天气流。第一次先发一个 null 再发真实数据 —— 顺序很重要：
     *
     * [combine] 要两条流都发过值才会产出第一份卡片列表，而网络请求少则几百毫秒、
     * 多则几秒。若先等在请求上，首页开头几秒会**一张卡都没有**（空白）。
     * 先发 null 就是"先把'暂无数据'摆上去，数据到了再换"，页面永远不空。
     *
     * 请求成功顺手把日出日落喂给 [Daylight]（地图白天/夜间底色用它）；失败则清掉外部值，
     * 让 Daylight **回到本地天文算法** —— 这就是"本地算法保留为兜底"的落点。
     */
    private fun weatherFlow(): Flow<WeatherReading?> = flow {
        emit(null)
        while (true) {
            val reading = weather?.fetch()
            if (reading == null) {
                Daylight.clearExternal()
            } else {
                Daylight.publish(reading.sunriseClock, reading.sunsetClock)
            }
            emit(reading)
            delay(WEATHER_REFRESH_MILLIS)
        }
    }

    private fun cards(now: Long, reading: WeatherReading?): List<HomeCard> = listOf(
        HomeCard(
            id = "clock",
            kind = HomeCardKind.CLOCK,
            title = clockTime(now),
            subtitle = clockDate(now),
        ),
        weatherCard(reading),
    )

    /**
     * 天气卡。没有数据时**只有城市名与"暂无数据"** —— 明细行与署名都不出现：
     * 署名是"这几行数据来自 Open-Meteo"的意思，没有数据就没有可署名的东西。
     */
    private fun weatherCard(reading: WeatherReading?): HomeCard = if (reading == null) {
        HomeCard(
            id = "weather",
            kind = HomeCardKind.WEATHER,
            title = WEATHER_CITY,
            subtitle = WEATHER_EMPTY,
        )
    } else {
        HomeCard(
            id = "weather",
            kind = HomeCardKind.WEATHER,
            title = WEATHER_CITY,
            subtitle = "${reading.temperatureC}° ${reading.description}",
            lines = listOf(
                CardLine("体感 ${reading.apparentC}°", "湿度 ${reading.humidityPercent}%"),
            ),
            footnote = WEATHER_ATTRIBUTION,
        )
    }

    /** `"08:37"`。24 小时制、两位补零 —— 时钟卡的字宽是按 HH:mm 反推的，位数必须固定。 */
    private fun clockTime(now: Long): String {
        val calendar = calendarAt(now)
        return String.format(
            Locale.ROOT,
            "%02d:%02d",
            calendar[Calendar.HOUR_OF_DAY],
            calendar[Calendar.MINUTE],
        )
    }

    /** `"10月12日 星期一"`。月份不补零（`10月` 比 `010月` 好读）。 */
    private fun clockDate(now: Long): String {
        val calendar = calendarAt(now)
        val weekday = WEEKDAY_CN[calendar[Calendar.DAY_OF_WEEK] - 1]
        return "${calendar[Calendar.MONTH] + 1}月${calendar[Calendar.DAY_OF_MONTH]}日 星期$weekday"
    }

    private fun calendarAt(now: Long): Calendar =
        Calendar.getInstance(CLOCK_ZONE).apply { timeInMillis = now }

    /**
     * 距离下一个整分钟还有多少毫秒。
     *
     * 秒数用 60 减、再扣掉已经过去的毫秒；正好踏在整分钟上时差值为 0，
     * 用 [coerceAtLeast] 兜一个下限，免得协程空转。
     */
    private fun millisUntilNextMinute(now: Long): Long {
        val calendar = calendarAt(now)
        val passedMillis = calendar[Calendar.SECOND] * 1000L + calendar[Calendar.MILLISECOND]
        return (60_000L - passedMillis).coerceAtLeast(1L)
    }
}
