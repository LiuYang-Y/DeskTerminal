package com.qingke.desk.core.data

import com.qingke.desk.core.model.CardLine
import com.qingke.desk.core.model.HomeCard
import com.qingke.desk.core.model.HomeCardKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 首页卡片数据源。
 *
 * 观察型接口：卡片内容会自己变（时钟每分钟走一格），所以是 Flow 而不是一次性的 List。
 * 真实实现换成 Room / 天气 API 时签名不变。
 */
interface HomeRepository {

    /** 卡片列表。真实实现换成 Room / 天气 API 时签名不变。 */
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

/** 星期几的中文。`Calendar.DAY_OF_WEEK` 是 1..7 且 1 = 周日。 */
private val WEEKDAY_CN = arrayOf("日", "一", "二", "三", "四", "五", "六")

/**
 * 本地数据源。
 *
 * 2026-10-12 起「假数据」只剩天气一张卡：
 *  · **时钟卡读系统时间**（东八区），不再每 30 秒自己 +1 分钟 ——
 *    那个假时钟不但与真实时间无关，走一分钟还会因为 30 秒打点而**在一分钟内显示两次
 *    同一个值，然后跳过一分钟**，是"假数据"里最容易被当成 bug 报的那一类；
 *  · 天气卡仍待接真实数据源（Open-Meteo 的接入在下一步），
 *    它的 `subtitle` / `lines` 现在还是写死的示例值，**不是真实天气**；
 *  · 音乐卡不再由本仓库提供：首页上那张卡由 :feature:media 的 MediaSessionMonitor
 *    驱动（真实播放状态），这里再放一条只会是一份永远不同步的假曲目。
 *
 * 手机投屏 / 日程 / 待办三张卡此前已删除 —— 投屏在侧栏有入口，日程和待办没有真实数据源。
 */
class LocalHomeRepository : HomeRepository {

    override fun observeCards(): Flow<List<HomeCard>> = flow {
        while (true) {
            val now = System.currentTimeMillis()
            emit(cards(now))
            // **对齐到下一个整分钟**而不是固定 30 秒一跳：
            // 固定间隔会与"分钟"这个刻度错开，时钟读数看上去忽快忽慢；
            // 对齐之后每一分钟恰好刷新一次，不会漏格也不会重格。
            delay(millisUntilNextMinute(now) + TICK_GUARD_MILLIS)
        }
    }

    private fun cards(now: Long): List<HomeCard> = listOf(
        HomeCard(
            id = "clock",
            kind = HomeCardKind.CLOCK,
            title = clockTime(now),
            subtitle = clockDate(now),
        ),
        HomeCard(
            id = "weather",
            kind = HomeCardKind.WEATHER,
            title = "西安",
            subtitle = "18° 多云",
            lines = listOf(CardLine("体感 17°", "湿度 62%")),
        ),
    )

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

    /** `"10月12日 星期一"`。月份不补零（`10月` 比 `10月` 更好读，1 月也不必写成 01 月）。 */
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
