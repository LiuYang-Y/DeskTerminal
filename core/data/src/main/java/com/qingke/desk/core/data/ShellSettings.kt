package com.qingke.desk.core.data

import android.content.Context

/**
 * 侧栏在屏幕的哪一侧。
 *
 * 之所以做成可切换：同一台机器可能左手或右手更顺，也可能被摆在车里某个固定朝向
 * （那时"靠近驾驶员的一侧"取决于装法，写死一侧就是错的）。
 */
enum class SidebarSide {
    LEFT,
    RIGHT,
    ;

    /** 切到另一侧。 */
    fun flipped(): SidebarSide = if (this == LEFT) RIGHT else LEFT
}

/**
 * 一个经纬度点。放在 :core:data 而不是用高德的 `LatLng`：
 * 存位置的模块不该知道地图 SDK 的存在，:map 拿到它自己转 `LatLng`。
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * 一个"常用地点"（家 / 公司）。
 *
 * 存**标题 + 坐标 + 地址**三项，**不存高德的 POI ID**：
 * ID 是服务端给的，Key 换一把或 SDK 升一版就可能查不回来，而坐标是过了今天还能用的东西。
 * 这条口径与上面的 [lastLocation] 一致 —— 宁可少一个字段，也不要一个会失效的字段。
 *
 * 放在 :core:data 而不是用高德的类型（同 [GeoPoint] 的理由）：存位置的模块不该知道地图 SDK 的存在。
 */
data class NaviPlace(
    val title: String,
    val point: GeoPoint,
    val address: String = "",
)

/**
 * 壳层的持久设置：**侧栏在哪一侧、屏幕是否强制常亮、上次定位到的位置、家与公司的地址**。
 *
 * 用 SharedPreferences 而不是 DataStore：全工程只有这几个开关要存，
 * 为它引入一条协程 + Proto 的链路不划算（:hosting 的 ShizukuBootPrompt、
 * :map 的 AMapPrivacy 也都是这个口径，`apply()` 即可，不阻塞主线程写盘）。
 *
 * 注意这里用 [Context.getApplicationContext] 之外的 context 也安全 ——
 * 取的是 applicationContext 的 prefs，不持有 Activity。
 */
object ShellSettings {

    private const val PREFS_NAME = "desk_shell"
    private const val KEY_SIDEBAR_SIDE = "sidebar_side"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_LAST_LAT = "last_lat"
    private const val KEY_LAST_LON = "last_lon"

    // 家 / 公司。各 4 个 key：标题、纬度、经度、地址。
    // 坐标照旧用 Float（约 1 m 精度）—— 目的地只要落在"几十米内是它"就够了，
    // 没必要为它引入 Double 到 String 的往返。
    private const val KEY_HOME_TITLE = "home_title"
    private const val KEY_HOME_LAT = "home_lat"
    private const val KEY_HOME_LON = "home_lon"
    private const val KEY_HOME_ADDR = "home_addr"
    private const val KEY_WORK_TITLE = "work_title"
    private const val KEY_WORK_LAT = "work_lat"
    private const val KEY_WORK_LON = "work_lon"
    private const val KEY_WORK_ADDR = "work_addr"

    /** 默认在左：基准机横屏时挖孔在左侧，侧栏贴左能把挖孔条一起吃掉。 */
    private val DEFAULT_SIDE = SidebarSide.LEFT

    /**
     * 默认**常亮**。
     *
     * 桌面终端的核心用法就是"抬眼就能看"，时钟、地图、卡片 24 小时在线；
     * 一旦跟随系统息屏，每次看时间都要先点亮（有锁屏密码还得解锁），体验是断崖式的。
     * 代价是屏幕长期点亮带来的 OLED 老化与耗电 —— 这正是保留防烧屏微位移
     * （[com.qingke.desk.core.platform.BurnInGuard]）的理由：它只对亮着的画面有用。
     *
     * 做成开关而不是写死：省不省电、怕不怕烧屏是**用户自己的取舍**，
     * 该由用户在设置面板里选，不该由我们替他决定。
     */
    private const val DEFAULT_KEEP_SCREEN_ON = true

    fun sidebarSide(context: Context): SidebarSide {
        val raw = prefs(context).getString(KEY_SIDEBAR_SIDE, null) ?: return DEFAULT_SIDE
        return runCatching { SidebarSide.valueOf(raw) }.getOrDefault(DEFAULT_SIDE)
    }

    /**
     * 写入并**立刻**返回新值，调用方拿它更新 state，避免"存了但界面没变"。
     *
     * 用 commit() 而不是 apply()：切侧栏是低频动作，但紧接着就要读 ——
     * apply() 异步落盘，万一进程被杀（用户切完就熄屏）这次选择会丢。
     */
    fun setSidebarSide(context: Context, side: SidebarSide): SidebarSide {
        prefs(context).edit().putString(KEY_SIDEBAR_SIDE, side.name).commit()
        return side
    }

    /**
     * 屏幕是否强制常亮（[android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]）。
     *
     * 关掉之后屏幕跟随系统休眠设置自己灭 —— 休眠时长由用户在系统设置里定，
     * 本应用改不了（也**不应该**改：那等于绕开用户的选择）。
     */
    fun keepScreenOn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_KEEP_SCREEN_ON, DEFAULT_KEEP_SCREEN_ON)

    /**
     * 写入并**立刻**返回新值。
     *
     * 这里用 `commit()` 而不是 `apply()`，理由和 [setSidebarSide] 不同：
     * 常亮状态存在窗口 flag 上，**进程被杀后 flag 随之消失**，下次启动会重新读设置，
     * 所以异步落盘丢一次不会造成"界面与设置不一致"。但紧跟着就要把它应用到窗口上，
     * 同步写完能保证"设置页显示的值"与"窗口实际生效的值"是同一个。
     */
    fun setKeepScreenOn(context: Context, enabled: Boolean): Boolean {
        prefs(context).edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).commit()
        return enabled
    }

    /**
     * 上次定位到的位置；从没定位过返回 null。
     *
     * 用途是**给首页地图卡一个像样的初始视角**：拿到第一个定位点之前，
     * 高德 MapView 的默认视角是全国，街道级的预览卡上先闪一下全国图很突兀。
     * 它不是"当前位置"—— 设备固定在桌上，昨天的坐标今天照样能用。
     */
    fun lastLocation(context: Context): GeoPoint? {
        val lat = prefs(context).getFloat(KEY_LAST_LAT, Float.NaN).toDouble()
        val lon = prefs(context).getFloat(KEY_LAST_LON, Float.NaN).toDouble()
        if (lat.isNaN() || lon.isNaN()) return null
        return GeoPoint(lat, lon)
    }

    /** 记下最新一次定位。写的是 Float（约 1 m 精度），对"初始视角"足够。 */
    fun setLastLocation(context: Context, point: GeoPoint) {
        prefs(context).edit()
            .putFloat(KEY_LAST_LAT, point.latitude.toFloat())
            .putFloat(KEY_LAST_LON, point.longitude.toFloat())
            .apply()
    }

    /**
     * 读"家"。没设过返回 null（三个字段缺任何一个也当没设过 ——
     * 半个地点比没有地点更危险：界面会显示一个点不开、也导不了航的按钮）。
     */
    fun naviHome(context: Context): NaviPlace? = readPlace(context, KEY_HOME_TITLE, KEY_HOME_LAT, KEY_HOME_LON, KEY_HOME_ADDR)

    /** 读"公司"。没设过返回 null。 */
    fun naviWork(context: Context): NaviPlace? = readPlace(context, KEY_WORK_TITLE, KEY_WORK_LAT, KEY_WORK_LON, KEY_WORK_ADDR)

    /**
     * 写"家"。传 null = **删除**（用户搬了家、或不想留这个地址）。
     *
     * 用 `commit()` 而不是 `apply()`：这是用户显式点出来的动作，紧接着就会被
     * 首页/搜索页读一次去刷新按钮，异步落盘会出现"存了但界面还是旧的"。
     */
    fun setNaviHome(context: Context, place: NaviPlace?) {
        writePlace(context, place, KEY_HOME_TITLE, KEY_HOME_LAT, KEY_HOME_LON, KEY_HOME_ADDR)
    }

    /** 写"公司"。传 null = 删除。 */
    fun setNaviWork(context: Context, place: NaviPlace?) {
        writePlace(context, place, KEY_WORK_TITLE, KEY_WORK_LAT, KEY_WORK_LON, KEY_WORK_ADDR)
    }

    private fun readPlace(
        context: Context,
        keyTitle: String,
        keyLat: String,
        keyLon: String,
        keyAddr: String,
    ): NaviPlace? {
        val p = prefs(context)
        val title = p.getString(keyTitle, null) ?: return null
        val lat = p.getFloat(keyLat, Float.NaN)
        val lon = p.getFloat(keyLon, Float.NaN)
        if (lat.isNaN() || lon.isNaN()) return null
        return NaviPlace(
            title = title,
            point = GeoPoint(lat.toDouble(), lon.toDouble()),
            address = p.getString(keyAddr, "").orEmpty(),
        )
    }

    private fun writePlace(
        context: Context,
        place: NaviPlace?,
        keyTitle: String,
        keyLat: String,
        keyLon: String,
        keyAddr: String,
    ) {
        val editor = prefs(context).edit()
        if (place == null) {
            editor.remove(keyTitle).remove(keyLat).remove(keyLon).remove(keyAddr)
        } else {
            editor.putString(keyTitle, place.title)
                .putFloat(keyLat, place.point.latitude.toFloat())
                .putFloat(keyLon, place.point.longitude.toFloat())
                .putString(keyAddr, place.address)
        }
        editor.commit()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
