package com.qingke.desk.core.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * 日出日落 —— 目前用来决定什么时候切白天/夜间地图样式。
 *
 * 三条前置约定（改之前先读，每条都是想清楚才定的）：
 *
 * 1. **时区写死北京时间（UTC+8）**，不读系统时区。
 *    "日落是几点几分"问的是墙上时钟。去读系统时区的话，设备初次联网前时区还没同步、
 *    或者用户手动改了一下，同一天的结论就能跳变 —— 而这件事每天只该翻两次。
 *
 * 2. **本地算法的坐标写死北京**（[LATITUDE] / [LONGITUDE]），不读设备定位。
 *    本项目明确要这么做：地图用什么底色是纯观感的事，不该为此去开定位权限，
 *    也不该让同一个城市的不同工位算出不同的日落时间。
 *
 * 3. **本地算法不联网**：日出日落是纯天文学问题，只需要年月日 + 经纬度。
 *    但它只是**兜底** —— 天气 API 的 sunrise/sunset 一旦通过 [publish] 注入
 *    就优先用（见 [sunTimes]）。天气 API 会失败（离线、配额、Key 过期），
 *    而"这张地图用什么底色"不能因为一次请求失败就没有答案，所以两路都留着。
 *
 * 本地算法是 NOAA 的太阳位置近似式，中国境内误差约 ±1 分钟 —— 兜底这个用途
 * 远远够用，不值得为它引入第三方天文库。
 *
 * 本地算法那段是纯 Kotlin + `java.util.Calendar`，不碰 Android。
 * 之所以会出现一个 [StateFlow]：天气数据什么时候到由网络决定，
 * 到了之后正在显示的地图得立刻跟着换底色，UI 需要有个东西可以订阅。
 */
object Daylight {

    /**
     * 北京时间，固定 +8 小时。
     *
     * 用纯偏移 ID 而不是 `"Asia/Shanghai"`：后者要设备上的 tzdata 里有这个名字才解析得
     * 出来，拿不到时 [TimeZone.getTimeZone] 会**静默退回 GMT**（不是抛异常），
     * 昼夜结论当场反掉。这里要的恰恰是一个不含夏令时的固定偏移。
     */
    private val TIME_ZONE: TimeZone = TimeZone.getTimeZone("GMT+08:00")

    private const val TZ_OFFSET_HOURS = 8.0

    /**
     * 坐标：**北京**（39.9042°N, 116.4074°E）。
     *
     * 选它的理由：一是不打算碰设备定位（见类注释第 2 条），需要一个固定代表点；
     * 二是"北京时间"这个名字本身来源于北京；
     * 三是地图上这家服务商在隐私政策里写的第三方主体 —— 北京高德图强科技有限公司
     * —— 所在地也在北京。三点指向同一个坐标，不用再挑了。
     *
     * 换城市只改这两个常量：日出日落对经度极其敏感（中国东西跨 5 个时区，
     * 统一的北京时间下，乌鲁木齐的实际天黑比北京晚约 2 小时），对纬度相对迟钝。
     */
    private const val LATITUDE = 39.9042
    private const val LONGITUDE = 116.4074

    /**
     * 判定"日落"用的太阳天顶角：90.833° = 地平 90° + 0.833°。
     *
     * 那 0.833° 是太阳视半径（约 0.267°）与海平面附近的大气折射（约 0.566°）之和 ——
     * 合起来才是"日轮上边缘刚沉进地平线"那一刻，也就是通常说的日落，
     * 而不是几何上的太阳中心落地平。
     */
    private const val SUNSET_ZENITH_DEG = 90.833

    private const val MINUTES_PER_DAY = 24 * 60
    private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

    /** 当天太阳始终在地平线下（极夜）。 */
    private const val POLAR_NIGHT = -1

    /** 当天太阳始终在地平线上（极昼）。 */
    private const val POLAR_DAY = -2

    /**
     * 一天的日出日落。两个时刻都是**北京时间**下"从零点到那一刻过了多少分钟"。
     */
    data class SunTimes(
        val sunriseMinuteOfDay: Int?,
        val sunsetMinuteOfDay: Int?,
        /** 极昼：太阳整天不落。两个时刻都是 null。 */
        val polarDay: Boolean = false,
        /** 极夜：太阳整天不升。两个时刻都是 null。 */
        val polarNight: Boolean = false,
    ) {

        /** @param minuteOfDay 北京时间下当天的分钟数，见 [Daylight.minuteOfDay]。 */
        fun isDaylight(minuteOfDay: Int): Boolean = when {
            polarDay -> true
            polarNight -> false
            sunriseMinuteOfDay == null || sunsetMinuteOfDay == null ->
                minuteOfDay in 6 * 60 until 18 * 60
            else -> minuteOfDay >= sunriseMinuteOfDay && minuteOfDay < sunsetMinuteOfDay
        }
    }

    /** 当前时刻是白天还是夜间。 */
    fun isDaylight(nowMillis: Long = System.currentTimeMillis()): Boolean =
        sunTimes(nowMillis).isDaylight(minuteOfDay(nowMillis))

    /**
     * 外部数据源（天气 API）给的日出日落。
     *
     * @param dateMillis 数据对应**那一天的开始**（北京时间当天 0 点）。跨天之后这条
     *                   数据自动作废 —— 昨天的日落时间拿来切今天的底色，会错整整一天。
     */
    data class External(
        val dateMillis: Long,
        val sunriseMinuteOfDay: Int,
        val sunsetMinuteOfDay: Int,
    )

    private val externalState = MutableStateFlow<External?>(null)

    /**
     * 天气 API 提供的日出日落。非空且未过期时优先于本地算出来的值。
     *
     * 用 Flow 而不是普通的可变变量：数据什么时候到由网络决定，而它到了之后
     * 正显示着的地图得**立刻**换底色，不能等到下次自己想起来再看一眼。
     */
    val external: StateFlow<External?> = externalState.asStateFlow()

    /**
     * 注入天气 API 的日出日落。
     *
     * @param sunriseClock / @param sunsetClock `"06:20"` 这种 HH:mm，天气服务基本都给这个格式。
     *                     解析不出来就什么都不做（保留上一次的值，或者继续用本地算法），
     *                     不抛异常 —— 一条格式变了的字符串不该把地图搞坏。
     */
    fun publish(
        sunriseClock: String,
        sunsetClock: String,
        forMillis: Long = System.currentTimeMillis(),
    ) {
        val sunrise = minuteFromClock(sunriseClock) ?: return
        val sunset = minuteFromClock(sunsetClock) ?: return
        externalState.value = External(startOfDay(forMillis), sunrise, sunset)
    }

    /** 天气数据源不可用时调用：清掉旧值，回到本地算法。 */
    fun clearExternal() {
        externalState.value = null
    }

    /** 北京时间下的"这一天的第几分钟"，取值 [0, 1440)。 */
    fun minuteOfDay(millis: Long): Int {
        val calendar = Calendar.getInstance(TIME_ZONE).apply { timeInMillis = millis }
        return calendar[Calendar.HOUR_OF_DAY] * 60 + calendar[Calendar.MINUTE]
    }

    /**
     * 当天实际生效的日出日落。
     *
     * 优先级：
     *   1. 天气 API 给的（[external]），前提是它的数据日期就是这一天；
     *   2. 本地天文算法（[compute]）。
     *
     * 为什么两路都要留：天气服务的日出日落带地形遮挡与气象意义上的天光，
     * 更接近"天亮了/天黑了"的体感；但它会失败（离线、配额、Key 过期），
     * 而"地图片用什么底色"不能因为一次请求失败就没有答案。
     */
    fun sunTimes(millis: Long = System.currentTimeMillis()): SunTimes =
        externalState.value
            ?.takeIf { it.dateMillis == startOfDay(millis) }
            ?.let { SunTimes(it.sunriseMinuteOfDay, it.sunsetMinuteOfDay) }
            ?: compute(millis)

    /** 本地天文算法的结果。不联网、不会失败，作为兜底永远可用。 */
    private fun compute(millis: Long): SunTimes {
        val calendar = Calendar.getInstance(TIME_ZONE).apply { timeInMillis = millis }
        val day = dayOfYear(
            year = calendar[Calendar.YEAR],
            // Calendar 的月份从 0 起算，这里统一按 1..12 传给递推式。
            month = calendar[Calendar.MONTH] + 1,
            dayOfMonth = calendar[Calendar.DAY_OF_MONTH],
        )
        val sunrise = solve(day, rising = true)
        val sunset = solve(day, rising = false)
        return when {
            sunrise == POLAR_NIGHT || sunset == POLAR_NIGHT ->
                SunTimes(null, null, polarNight = true)
            sunrise == POLAR_DAY || sunset == POLAR_DAY ->
                SunTimes(null, null, polarDay = true)
            else -> SunTimes(sunrise, sunset)
        }
    }

    /**
     * 距离下一次"白天 ⇌ 夜间"翻转还有多久（毫秒）。
     *
     * 调用方拿它去 `delay(...)`，就不需要每分钟心跳：这台设备是 24 小时常亮的，
     * 而昼夜结论一天只翻两次，跟着"下一个翻转点"睡过去是最省的做法。
     */
    fun millisUntilNextSwitch(nowMillis: Long = System.currentTimeMillis()): Long {
        val minute = minuteOfDay(nowMillis)
        val events = listOfNotNull(
            sunTimes(nowMillis).sunriseMinuteOfDay,
            sunTimes(nowMillis).sunsetMinuteOfDay,
            sunTimes(nowMillis + DAY_MILLIS).sunriseMinuteOfDay,
            sunTimes(nowMillis + DAY_MILLIS).sunsetMinuteOfDay,
        )
        val waitMinutes = events
            .map { event -> (event - minute + MINUTES_PER_DAY) % MINUTES_PER_DAY }
            .minOrNull()
            // 极昼/极夜：今天明天都没有可等的翻转点，一天后再看（季节会变）。
            ?: MINUTES_PER_DAY
        // 兜底 1 秒：正好踏在翻转点上时上面的差值是 0，不设下限会把协程转疯。
        return max(waitMinutes * 60_000L, 1_000L)
    }

    /**
     * 解一次"太阳到达 [SUNSET_ZENITH_DEG] 天顶角"的时刻。
     *
     * @param dayOfYear 一年中的第几天，见 [dayOfYear]。
     * @param rising true 求日出、false 求日落。
     * @return 北京时间下的分钟数 [0, 1440)；[POLAR_NIGHT] / [POLAR_DAY] 表示当天无此事件。
     */
    private fun solve(dayOfYear: Int, rising: Boolean): Int {
        // 初值：NOAA 用 6 点估日出、18 点估日落，它是 UT 下的粗估计，后面会被逐步修正。
        val hourApprox = if (rising) 6.0 else 18.0
        val longitudeHour = LONGITUDE / 15.0
        val t = dayOfYear + (hourApprox - longitudeHour) / 24.0

        // 平近点角 → 真黄经：两项中心差把椭圆轨道的影响补回去。
        val meanAnomalyDeg = 0.9856 * t - 3.289
        val trueLongitudeDeg = norm360(
            meanAnomalyDeg +
                1.916 * sin(rad(meanAnomalyDeg)) +
                0.020 * sin(2.0 * rad(meanAnomalyDeg)) +
                282.634,
        )

        // 赤经。atan 只给出 (-90°, 90°)，必须按象限搬回去 —— 赤经与黄经同象限。
        val rightAscensionDeg = norm360(deg(atan(0.91764 * tan(rad(trueLongitudeDeg)))))
        val rightAscensionHours = (
            rightAscensionDeg +
                (floor(trueLongitudeDeg / 90.0) * 90.0 - floor(rightAscensionDeg / 90.0) * 90.0)
            ) / 15.0

        val sinDeclination = 0.39782 * sin(rad(trueLongitudeDeg))
        val cosDeclination = cos(asin(sinDeclination))

        val latitudeRad = rad(LATITUDE)
        val cosHourAngle = (cos(rad(SUNSET_ZENITH_DEG)) - sinDeclination * sin(latitudeRad)) /
            (cosDeclination * cos(latitudeRad))
        if (cosHourAngle > 1.0) return POLAR_NIGHT
        if (cosHourAngle < -1.0) return POLAR_DAY

        // 时角：日落取 +acos，日出取它的补角（360° - acos，等价于负时角但保持在 [0,360)）。
        // **漏掉这个符号日出日落会算成同一个值** —— 首次实现就是这么错的。
        val hourAngleHours = (if (rising) 360.0 - deg(acos(cosHourAngle)) else deg(acos(cosHourAngle))) /
            15.0
        val utcHours = norm24(
            hourAngleHours + rightAscensionHours - 0.06571 * t - 6.622 - longitudeHour,
        )
        return (norm24(utcHours + TZ_OFFSET_HOURS) * 60.0).toInt()
    }

    /** NOAA 的"一年第几天"递推式。1 月 1 日 → 1。 */
    private fun dayOfYear(year: Int, month: Int, dayOfMonth: Int): Int {
        val n1 = floor(275.0 * month / 9.0)
        val n2 = floor((month + 9.0) / 12.0)
        val n3 = 1 + floor((year - 4 * floor(year / 4.0) + 2) / 3.0)
        return (n1 - n2 * n3 + dayOfMonth - 30).toInt()
    }

    /** `"06:20"` → 当天第 380 分钟。格式不对返回 null。 */
    fun minuteFromClock(clock: String): Int? {
        val parts = clock.trim().split(":")
        if (parts.size < 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    /** 北京时间下"这一天 0 点"的时间戳。用来给外部数据判有效期。 */
    fun startOfDay(millis: Long): Long = Calendar.getInstance(TIME_ZONE).run {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    private fun rad(degrees: Double): Double = degrees * PI / 180.0

    private fun deg(radians: Double): Double = radians * 180.0 / PI

    private fun norm360(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    private fun norm24(value: Double): Double = ((value % 24.0) + 24.0) % 24.0
}
