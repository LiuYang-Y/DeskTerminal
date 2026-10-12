package com.qingke.desk.map.navi

import android.content.Context
import android.graphics.Bitmap
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.model.NaviPoi
import com.qingke.desk.core.data.GeoPoint
import com.qingke.desk.map.AMapPrivacy
import com.qingke.desk.map.MapLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 一条算好的路线的摘要。只取界面要显示、且**能从 SDK 直接读到**的三个数。 */
data class RouteSummary(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val trafficLights: Int,
) {
    /** "38.5 公里" —— 不到 1 公里时给米，避免显示"0.4 公里"这种别扭的读数。 */
    val distanceText: String
        get() = if (distanceMeters < 1000) "$distanceMeters 米" else "%.1f 公里".format(distanceMeters / 1000.0)

    /** "45 分钟" —— 超过 1 小时给"1 小时 12 分"。 */
    val durationText: String
        get() {
            val minutes = durationSeconds / 60
            return if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分"
        }

    val trafficLightText: String get() = "$trafficLights 个红绿灯"
}

/**
 * 一条**可选**的路线（多路径策略下会返回若干条，界面并列出来让人挑）。
 *
 * @param id 路线 id。[NaviSession.select] 用它让 SDK 真的走这一条。
 * @param labels 高德给这条路线的标签串（`AMapNaviPath.getLabels()`），
 *               诸如"红绿灯少""收费"之类。**原样展示**，不自己编标签。
 */
data class RouteOption(
    val id: Int,
    val summary: RouteSummary,
    val labels: String = "",
)

/** 算路状态机。界面只认这四个态，不存在"半算好"这种中间态。 */
sealed interface RouteState {
    /** 还没有目的地。 */
    data object Idle : RouteState

    /** 正在算（有 20 秒兜底超时，见 [NaviSession.CALC_TIMEOUT_MILLIS]）。 */
    data object Calculating : RouteState

    /**
     * 算好了。[options] 通常有多条（用多路径策略时），[selectedIndex] 是当前选中的下标 ——
     * 界面因此能画一张"几条路线 + 选中态"的列表，而不是只显示一条。
     */
    data class Ready(
        val options: List<RouteOption>,
        val selectedIndex: Int = 0,
    ) : RouteState {
        val selected: RouteOption? get() = options.getOrNull(selectedIndex)
    }

    data class Failed(val message: String) : RouteState
}

/**
 * 导航会话的真实阶段。**这是"导航还在不在"的唯一权威**。
 *
 * 为什么要有它（2026-10-12 的严重缺陷）：原先判断"是不是在导航"用的是界面自己的
 * 一个 `_navigating` 布尔，而"导航中"这件事实际上由**用户切到了哪一屏**决定 ——
 * 切到首页那一帧，导航 View 被 dispose（`onDestroy`），引擎却还在导航。
 * 两个状态从此各说各话，用户看到的现象是"导航没了、再点开始导航被打断"。
 *
 * 现在阶段由**引擎回调**驱动（`onStartNavi` / `onStopNavi`），界面只读它。
 *
 * 六个阶段，其中 [STARTING] 是原方案里没有、但实现上必须有的一个：
 * `AMapNavi.startNavi()` 必须在 `AMapNaviView.onCreate()` **之后**调用
 * （否则 SDK 拿不到 View，把导航状态丢在地上）。所以"请求开始"和"真正开始"
 * 之间必然存在一段等待期，它就是 [STARTING]。把它显式化之后
 * 界面能正确地在"还没开始"时就挂上导航 View，而不是先挂 View 再撞运气。
 */
enum class NaviPhase {
    /** 空闲：没有目的地，也没在导航。 */
    IDLE,

    /** 算路中。 */
    CALCULATING,

    /** 路线预览：算好了几条，等用户挑一条并点"开始导航"。 */
    PREVIEW,

    /** 已请求开始：导航 View 正在创建 / 等待 `startNavi()` 生效。 */
    STARTING,

    /** 导航中。**只有它意味着"导航还在"**。 */
    NAVIGATING,

    /** 停止中：已经下发 `stopNavi()`，等 `onStopNavi` 回调把状态收干净。 */
    STOPPING,
}

/**
 * 实时引导数据。界面（全屏导航页、首页地图卡）只读这一份。
 *
 * 每一条都对应 `NaviInfo` 上的一个 getter（javap 核实过），**没有一条是推算的**：
 * 推算出来的"距离/时间"会和导航语音说的数字对不上，那比没有还糟。
 *
 * @param iconType `IconType` 常量（左转=2、右转=3、直行=9……），界面自己画图标时用。
 * @param icon SDK 直接给的转向图标位图（`NaviInfo.getIconBitmap()`）。
 *             有它就优先用它 —— SDK 的图标和它自己的语音是一套的，不会打架。
 * @param turnDistanceMeters 到**下一个转向口**还有多远（`getCurStepRetainDistance`）。
 * @param currentRoad 当前路名。
 * @param nextRoad 下一段路名（"进入 XX 路"里的那个 XX）。
 * @param remainDistanceMeters 剩余总里程（`getPathRetainDistance`）。
 * @param remainSeconds 剩余总时间（`getPathRetainTime`）。
 * @param remainLights 剩余红绿灯数。
 * @param speedKph 当前车速。
 * @param car 车当前所在的位置（`AMapNaviLocation.getCoord()`）。没拿到定位时 null。
 */
data class NaviGuidance(
    val iconType: Int,
    val icon: Bitmap?,
    val turnDistanceMeters: Int,
    val currentRoad: String,
    val nextRoad: String,
    val remainDistanceMeters: Int,
    val remainSeconds: Int,
    val remainLights: Int,
    val speedKph: Int,
    /**
     * 车的位置。放在引导数据里**而不是单开一条流**：首页卡上那颗"车点"必须和
     * "剩余 12.3 公里"同帧更新，拆成两条流就会出现"点动了、数字还是上一秒的"。
     */
    val car: GeoPoint? = null,

    /**
     * 车的行进方向（度，正北为 0，顺时针）。首页卡**跟车**时要靠它把地图转过来，
     * 让"前方"始终朝着屏幕上方 —— 没有它跟车就只是平移，看不出要往哪拐。
     *
     * 取值顺序见 `onLocationChange`：先用 [AMapNaviLocation.getBearing]（实际航向），
     * 拿不到再用 [AMapNaviLocation.getRoadBearing]（道路方向，静止时也有值）。
     */
    val carBearing: Float? = null,
) {
    /** "300 米" / "1.2 公里" —— 转向距离的通行写法。 */
    val turnDistanceText: String
        get() = if (turnDistanceMeters < 1000) {
            "$turnDistanceMeters 米"
        } else {
            "%.1f 公里".format(turnDistanceMeters / 1000.0)
        }

    val remainDistanceText: String
        get() = if (remainDistanceMeters < 1000) {
            "$remainDistanceMeters 米"
        } else {
            "%.1f 公里".format(remainDistanceMeters / 1000.0)
        }

    val remainTimeText: String
        get() {
            val minutes = remainSeconds / 60
            return if (minutes < 60) "$minutes 分钟" else "${minutes / 60} 小时 ${minutes % 60} 分"
        }
}

/**
 * 整条路线的几何 —— **纯数据，一个 SDK 类型都不带**。
 *
 * 为什么要拷一份，而不是把 `AMapNaviPath` 直接交给界面：
 *
 *  1. `:shell` 在编译期看不到导航 aar（`NaviLatLng` / `LatLngBounds` 都取不到引用），
 *     而首页地图卡要用这份几何画总览 —— 它必须是一串 Kotlin 基础类型才递得过去；
 *  2. SDK 的 path 对象会随重算 / 换路线**被换掉或失效**，拷出来的这份不受影响；
 *  3. 首页卡每帧都要读它，缓存住比每帧回头问 SDK 便宜。
 *
 * @param points 整条路线的形状点（`AMapNaviPath.getCoordList()`），按行进顺序排列。
 *               几万个点是正常的（70 公里实测 5000+ 个），只在算路 / 换路线时重建一次。
 * @param south 包围盒南边界（`getBoundsForPath()` 的 southwest.latitude）。
 * @param west 包围盒西边界。
 * @param north 包围盒北边界。
 * @param east 包围盒东边界。
 */
data class NaviShape(
    val points: List<GeoPoint>,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    /** 少于两个点连不成线。false 时界面按"没有几何"处理（不画线、不摆镜头）。 */
    val usable: Boolean get() = points.size >= 2

    /** 包围盒中心 —— 没有 bounds 可用时的兜底镜头目标。 */
    val centerLat: Double get() = (south + north) / 2.0
    val centerLng: Double get() = (west + east) / 2.0
}

/**
 * 导航会话管理器 —— **进程级、与界面无关**。
 *
 * ## 它要解决的缺陷（2026-10-12 真机复现，日志见 .verify/nav/）
 *
 * 老结构里"导航中"这件事同时被三处各记了一份：界面枚举 [DeskScreen.NAVI]、
 * `NaviEngine._navigating`、SDK 引擎自己的内部状态。切一次屏就能让它们分家：
 *
 * ```
 * 19:58:56.730 I navi: view disposing          ← 切首页，只销毁了 View
 *                 （没有 stopNavi、没有 onStopNavi —— 引擎仍在导航）
 * 20:00:06.290 I navi: startNavi ok=false      ← 回来再点开始导航 → SDK 拒绝
 * 20:00:06.296 I navi: stopped & destroyed     ← 兜底退出，于是播"退出导航，导航结束"
 * 20:00:06.499 I navi: navi stopped
 * ```
 *
 * 修法只有一条：**把"在不在导航"的唯一权威交给引擎回调**，界面只订阅。
 * 于是切屏、翻页、息屏、切投屏再回来，都不会改这个状态；只有两条路径会改它 ——
 * 用户点"退出导航"（[requestExit]），或者到了终点（`onArriveDestination`）。
 *
 * ## 三条硬约束（都是 SDK 层面的事实，不是风格选择）
 *   1. **合规闸门必须在 `getInstance` 之前**：[AMapPrivacy.applyIfAgreed] 会读用户
 *      真实同意状态并同步给地图与导航两套闸门（缺导航那套时，实例拿得到但回调永远不来）；
 *   2. **`destroy()` 是静态方法**（`public static synchronized void destroy()`），
 *      退出导航必须调它，否则下次进来会拿到"半死"的引擎 —— 现象是算路不回调；
 *   3. **`startNavi` 在已导航时会返回 false**：真机上 4 次 `ok=false` 全部对应
 *      "上一次导航没停"。所以"导航中再次发起导航"必须先停、等 `onStopNavi` 回来再启，
 *      不允许新旧并存（[requestStart] / [calculate] 的停-等-启链路就是干这个的）。
 */
object NaviSession {

    private const val TAG_LOG = "navi"

    /**
     * 算路兜底超时。真机实测正常情况下 5 秒内出结果，20 秒还没来就是真出问题了。
     *
     * 超时后**先自动重试一次**（真机上偶发过一次 20 秒不回调，紧接着重算就正常），
     * 第二次仍超时才判失败并把"重试"按钮交给用户。
     */
    private const val CALC_TIMEOUT_MILLIS = 20_000L

    /** 超时（含自动重试一次之后仍超时）时给用户看的话。要指明"下一步该做什么"。 */
    private const val CALC_TIMEOUT_MESSAGE = "算路超时，请检查网络后重试"

    /** 引擎直接拒收算路请求时的话。与超时区分开，两种原因的处置不一样。 */
    private const val CALC_REJECTED_MESSAGE = "算路请求被拒绝，请重试"

    /**
     * 等 `onStopNavi` 回调的兜底。回调不来也必须把状态推进 ——
     * 否则界面会永久卡在"停止中"，那比慢一点更糟。
     */
    private const val STOP_TIMEOUT_MILLIS = 4_000L

    /**
     * 界面上可选的策略。**这是给用户的四档偏好，不是 SDK 常量的全集**：
     * 全量有 30 多个（javap 数过），摆出来没人看得懂。每项都注明对应的常量值，
     * 真机排查时可以直接跟日志里的 `strategy=数字` 对上。
     */
    enum class Strategy(val label: String, val value: Int) {
        /** 推荐：多路径默认（速度优先，同时给备选）。 */
        RECOMMENDED("推荐", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_DEFAULT),

        /** 高速优先：多路径 + 优先高速。 */
        HIGHWAY_FIRST("高速优先", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_PRIORITY_HIGHSPEED),

        /** 不走高速：多路径 + 避开高速。 */
        NO_HIGHWAY("不走高速", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_HIGHSPEED),

        /** 躲避拥堵：多路径 + 避堵。 */
        AVOID_CONGESTION("躲避拥堵", PathPlanningStrategy.DRIVING_MULTIPLE_ROUTES_AVOID_CONGESTION),
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _phase = MutableStateFlow(NaviPhase.IDLE)

    /** 当前阶段。**界面判断"在不在导航"只准读它**。 */
    val phase: StateFlow<NaviPhase> = _phase.asStateFlow()

    private val _route = MutableStateFlow<RouteState>(RouteState.Idle)
    val route: StateFlow<RouteState> = _route.asStateFlow()

    /** 当前策略。换策略会自动重算（见 [switchStrategy]）。 */
    private val _strategy = MutableStateFlow(Strategy.RECOMMENDED)
    val strategy: StateFlow<Strategy> = _strategy.asStateFlow()

    /** 实时引导。没在导航时是 null。 */
    private val _guidance = MutableStateFlow<NaviGuidance?>(null)
    val guidance: StateFlow<NaviGuidance?> = _guidance.asStateFlow()

    /**
     * 当前选中路线的几何（首页地图卡画"整条路线总览"用）。没有路线时是 null。
     *
     * 它只在**算好一条路**和**换了一条路线**这两个时刻变，是个低频流 ——
     * 首页卡订阅它不会带来持续重组（高频的是 [guidance]）。
     */
    private val _shape = MutableStateFlow<NaviShape?>(null)
    val shape: StateFlow<NaviShape?> = _shape.asStateFlow()

    /** 当前目的地（带坐标）。退出导航后清空。 */
    private val _destination = MutableStateFlow<PlaceSuggestion?>(null)
    val destination: StateFlow<PlaceSuggestion?> = _destination.asStateFlow()

    private var navi: AMapNavi? = null

    /** 给 `applicationContext`：停/启链路里要用，而那时可能已经没有界面的 Context 了。 */
    private var appContext: Context? = null

    private var calcJob: Job? = null
    private var stopJob: Job? = null

    /** 上一次算路的起终点：换策略时要拿它重算，不然就得让界面再传一遍。 */
    private var lastStart: GeoPoint? = null
    private var lastEnd: PlaceSuggestion? = null

    /** 等停止回调期间攒下来的活：先算新的（换目的地），或直接重启。 */
    private var pendingCalc: Pair<GeoPoint, PlaceSuggestion>? = null
    private var restartAfterStop = false

    /** 算完就自动开始。只有"导航中换目的地"这条链会置它。 */
    private var autoStartAfterCalc = false

    /** 引导数据的日志节流（真机 1 Hz 左右，不节流会把日志撑爆）。 */
    private var lastGuidanceLogAt = 0L

    /**
     * 车当前的位置，来自 `onLocationChange`。
     *
     * 存成字段而不是只塞进 `_guidance`：定位回调与引导回调是**两条独立的时间线**，
     * 定位往往先到。先存下来，下一条引导数据就能带上它，不会出现"先有引导没有车点"。
     */
    private var carPoint: GeoPoint? = null

    /**
     * 车的行进方向（度）。跟 [carPoint] 同一条时间线，一起更新、一起清理。
     *
     * 为什么单独存而不直接用某一条引导里的值：定位回调比引导回调先到，
     * 而且方向只有在**连续两个位置**都拿到之后才谈得上"朝哪儿走"。
     */
    private var carBearing: Float? = null

    /** 车点日志的节流时间戳。定位回调约 1 Hz，不节流会把日志撑爆。 */
    private var lastCarLogAt = 0L

    /** 车点日志的最短间隔。5 秒一条，足够证明"在动"，又不至于淹没引导日志。 */
    private const val CAR_LOG_INTERVAL_MILLIS = 5_000L

    /**
     * 本次算路是否已经用掉了"自动重试一次"的机会。见 [doCalculate]。
     *
     * 每次真正开始算路（[calculate] / [switchStrategy]）时复位。
     */
    private var calcRetryUsed = false

    /**
     * 监听器只注册一次，挂在进程级引擎上。
     *
     * 为什么不做 add/remove 配对：引擎的生命周期就是进程的生命周期，
     * 界面来去不影响它；反过来"每进一次导航屏 add 一次"会积累监听器，
     * 同一次回调被调用多次（真机上表现为日志重复、首页卡重复刷新）。
     */
    private val listener = object : SimpleNaviListener() {

        override fun onInitNaviSuccess() {
            MapLog.i("$TAG_LOG: engine init ok")
        }

        override fun onInitNaviFailure() {
            MapLog.i("$TAG_LOG: engine init FAILED")
        }

        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult) {
            calcJob?.cancel()
            calcJob = null
            val ids = result.routeid?.toList().orEmpty()
            MapLog.i("$TAG_LOG: calc ok code=${result.errorCode} routeIds=$ids")
            val options = collectOptions(ids)
            if (options.isEmpty()) {
                MapLog.i("$TAG_LOG: calc ok but no usable path")
                failCalc("没有可用路线")
                return
            }
            // 默认选中第一条，并把"选中"这件事**同步给 SDK**：
            // 否则 startNavi 走的可能是 SDK 自己的主路线，出现"界面高亮第二条、
            // 实际按第一条导航"这种最坏情况（界面在撒谎，而且没人能一眼看出来）。
            selectInternal(options.first().id)
            // 几何要在 `_route` 之前发布：首页卡拿到 Ready 那一刻就要有路线可画，
            // 否则会先看见"有剩余里程、没有路线"的一帧。
            publishShape(options.first().id)
            _route.value = RouteState.Ready(options, 0)

            if (autoStartAfterCalc) {
                // "导航中换目的地"：旧导航已经停干净了，直接接上新的一条。
                autoStartAfterCalc = false
                MapLog.i("$TAG_LOG: auto start after recalc (destination changed while navigating)")
                _phase.value = NaviPhase.STARTING
            } else {
                _phase.value = NaviPhase.PREVIEW
            }
        }

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult) {
            // 错误原文照抄进日志：真机排查时它就是去控制台对账的唯一线索。
            // （真机见过 1806 = CLIENT_NETWORK_EXCEPTION，客户端网络偶发，下次请求就成功。）
            MapLog.i(
                "$TAG_LOG: calc FAILED code=${result.errorCode} " +
                    "desc=${result.errorDescription} detail=${result.errorDetail}",
            )
            failCalc("算路失败（${result.errorCode}）")
        }

        override fun onStartNavi(type: Int) {
            MapLog.i("$TAG_LOG: onStartNavi type=$type")
            _phase.value = NaviPhase.NAVIGATING
        }

        override fun onArriveDestination() {
            MapLog.i("$TAG_LOG: arrive destination")
            // 到终点也要走"停止"这条路，不能直接置 IDLE ——
            // 那样引擎还在导航态，用户马上再点开始导航就会撞上 `startNavi ok=false`。
            beginStop()
        }

        override fun onStopNavi() {
            MapLog.i("$TAG_LOG: onStopNavi callback")
            handleStopped()
        }

        override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {
            publishGuidance(naviInfo)
        }

        /**
         * 位置回调。**这是首页卡上那颗车点的唯一来源**（`getCoord()`）。
         *
         * 不拿 `NaviInfo.getCurPoint()` 去 `coordList` 里查点：那是"当前形状点下标"，
         * 模拟导航下会一跳一跳地走，而且下标越界时还得自己兜底。
         * `AMapNaviLocation` 是引擎给的真实位置，直接可用。
         */
        override fun onLocationChange(location: AMapNaviLocation?) {
            val coord = location?.coord ?: return
            val point = GeoPoint(coord.latitude, coord.longitude)

            // 方向：先用**实际航向**（getBearing），它拿不到（车静止时为 0）再退到
            // 道路方向（getRoadBearing，静止时也有值）。两个都没有就保留上一次的 ——
            // 车没动的时候把地图转回正北，看着像"画面自己转了一下"，比不转更怪。
            val rawBearing = runCatching { location.bearing }.getOrDefault(0f)
            val bearing = rawBearing.takeIf { it > 0f }
                ?: runCatching { location.roadBearing }.getOrDefault(0f).takeIf { it > 0f }
                ?: carBearing

            val moved = carPoint != point
            val turned = bearing != carBearing
            if (!moved && !turned) return
            carPoint = point
            carBearing = bearing
            // 已经有一条引导数据就把车点补进去；还没有就等 publishGuidance 带上它。
            _guidance.value = _guidance.value?.copy(car = point, carBearing = bearing)

            // 节流落盘：这是"车点数据真的在流"的唯一现场证据（模拟导航下约 1 Hz）。
            // 首页卡上那颗点动不动，全看这条有没有输出。
            val now = System.currentTimeMillis()
            if (now - lastCarLogAt >= CAR_LOG_INTERVAL_MILLIS) {
                lastCarLogAt = now
                MapLog.i(
                    "$TAG_LOG: car ${point.latitude},${point.longitude} bearing=$bearing",
                )
            }
        }

        @Suppress("DEPRECATION")
        override fun onGetNavigationText(text: String?) {
            // 内置语音自己会播；这一条只用来在真机上确认"播报确实发生了"。
            // 设备静止时它不会有输出（GPS 导航没有位移就没有引导事件）——
            // 要验证语音得用模拟导航（NaviType.EMULATOR）。
            MapLog.i("$TAG_LOG: speak ${text?.take(30)}")
        }
    }

    // ---- 外部 API ----------------------------------------------------------

    /**
     * 算一条自驾路线。**不抛异常**：所有失败都落到 [RouteState.Failed]，
     * 界面不需要 try/catch。
     *
     * 如果此刻**正在导航**，会先停掉旧的、等 `onStopNavi` 回来再算新的并自动开始 ——
     * 不允许新旧两条导航并存（见类注释的硬约束 3）。
     */
    fun calculate(context: Context, start: GeoPoint, end: PlaceSuggestion) {
        // 新一轮算路：重新享有一次"自动重试"的机会。
        calcRetryUsed = false
        when (_phase.value) {
            NaviPhase.NAVIGATING, NaviPhase.STARTING, NaviPhase.STOPPING -> {
                MapLog.i("$TAG_LOG: calculate while phase=${_phase.value} -> stop old first")
                pendingCalc = start to end
                autoStartAfterCalc = true
                restartAfterStop = false
                if (_phase.value != NaviPhase.STOPPING) beginStop()
            }

            else -> doCalculate(context, start, end)
        }
    }

    /**
     * 用户点了错误提示旁边那颗"重试"。
     *
     * 用上一轮的起终点重算，并且**复位"自动重试已用过"的标志** —— 手动重试是新的一轮，
     * 理应重新享有一次自动重试，否则用户在弱网下点一次就少一次容错。
     *
     * @return false = 连起终点都没有（界面上不该出现这种状态，兜一下）。
     */
    fun retryCalculate(context: Context): Boolean {
        val start = lastStart
        val end = lastEnd
        if (start == null || end == null) {
            MapLog.i("$TAG_LOG: retry ignored, no last start/end")
            return false
        }
        MapLog.i("$TAG_LOG: manual retry for ${end.name}")
        calcRetryUsed = false
        // 复用 doCalculate：它会把 destination / 几何 / 阶段铺成和首次一致的状态。
        doCalculate(context, start, end)
        return true
    }

    /**
     * 换策略并**立刻重算**。界面点"高速优先 / 不走高速"走这条。
     *
     * 不做"只记住策略、等用户再点一次算路"：那样用户点了按钮却什么都没发生，
     * 还得再去找一次目的地。
     */
    fun switchStrategy(context: Context, strategy: Strategy) {
        if (_strategy.value == strategy) return
        _strategy.value = strategy
        val start = lastStart
        val end = lastEnd
        if (start == null || end == null) {
            MapLog.i("$TAG_LOG: strategy=${strategy.name} but nothing to recalc")
            return
        }
        MapLog.i("$TAG_LOG: strategy -> ${strategy.name}(${strategy.value}), recalculating")
        // 预览阶段的换策略是"重算候选"，不该触发自动开始；把那个标志压掉。
        autoStartAfterCalc = false
        calculate(context, start, end)
    }

    /** 选中第几条路线。同时把选择同步给 SDK（见 [selectInternal] 的说明）。 */
    fun select(index: Int) {
        val current = _route.value as? RouteState.Ready ?: return
        val option = current.options.getOrNull(index) ?: return
        if (!selectInternal(option.id)) {
            MapLog.i("$TAG_LOG: selectRouteId(${option.id}) FAILED")
            return
        }
        MapLog.i("$TAG_LOG: selected route id=${option.id} (index=$index)")
        // 换了路线，首页卡上那条线也得跟着换 —— 否则用户选的是第 3 条，
        // 首页画的是第 1 条，正是"界面在撒谎"的另一张面孔。
        publishShape(option.id)
        _route.value = current.copy(selectedIndex = index)
    }

    /**
     * 请求开始导航。**这是唯一会把阶段推往 [NaviPhase.STARTING] 的入口**。
     *
     * 导航 View 建好之后由 [onViewReady] 真正下发 `startNavi()`。
     * 若此刻已经在导航：先停旧的，等 `onStopNavi` 回来再重新走一遍（不允许并存）。
     *
     * @param emulator 用 `NaviType.EMULATOR` 而不是 GPS。模拟导航会沿着算好的路线
     *                 自己"开"，所以在屋里就能把整条导航链路（引导数据、语音、
     *                 剩余里程/时间）跑完，不必真的把车开出去。只有 debug 验证驱动会传 true。
     *                 参数用 Boolean 而不是 NaviType 常量：`NaviType` 来自高德 aar，
     *                 :shell 未必能在编译期看到它（:map 的依赖不一定是 api 传递的）。
     */
    fun requestStart(context: Context, emulator: Boolean = false) {
        appContext = context.applicationContext
        val naviType = if (emulator) NaviType.EMULATOR else NaviType.GPS
        when (_phase.value) {
            NaviPhase.NAVIGATING, NaviPhase.STARTING -> {
                MapLog.i("$TAG_LOG: start requested while phase=${_phase.value} -> stop old first")
                restartAfterStop = true
                pendingCalc = null
                requestedNaviType = naviType
                beginStop()
            }

            NaviPhase.PREVIEW -> {
                requestedNaviType = naviType
                _phase.value = NaviPhase.STARTING
                MapLog.i("$TAG_LOG: request start type=$naviType phase=STARTING")
            }

            NaviPhase.STOPPING -> {
                // 已经在停的途中被点了第二次：把"停完就重启"记下来即可。
                restartAfterStop = true
                requestedNaviType = naviType
                MapLog.i("$TAG_LOG: start requested while STOPPING -> queue restart")
            }

            else -> MapLog.i("$TAG_LOG: start ignored in phase=${_phase.value}")
        }
    }

    /**
     * 导航 View 已经建好，可以下发 `startNavi()` 了。
     *
     * @return false = 引擎没能开始（界面应该说一句"启动失败"）。
     */
    fun onViewReady(context: Context): Boolean {
        appContext = context.applicationContext
        if (_phase.value != NaviPhase.STARTING) {
            // 常见于"切走又切回"：View 重建但导航早就在跑，这里什么都不该做。
            MapLog.i("$TAG_LOG: view ready but phase=${_phase.value} -> no start")
            return true
        }
        val engine = obtain(context) ?: run {
            MapLog.i("$TAG_LOG: start aborted, engine unavailable")
            _phase.value = NaviPhase.PREVIEW
            return false
        }

        // 先 selectRouteId 再 startNavi：`startNavi(int)` 的参数是 NaviType（GPS/EMULATOR），
        // 不是路线 id（javap 核实过签名），所以"走哪条"只能靠 selectRouteId 事先指定。
        (_route.value as? RouteState.Ready)?.selected?.let { selectInternal(it.id) }

        val ok = runCatching { engine.startNavi(requestedNaviType) }.getOrDefault(false)

        // 核验"界面高亮的那条 == 引擎真正在走的那条"。
        //
        // 这是本功能**唯一会撒谎的地方**，而且撒起谎来没人能一眼看出来：界面把第 3 条描边高亮，
        // 引擎却按第 1 条算距离/播报/转弯。所以这里**量引擎自己的 `naviPath`**，
        // 而不是问界面。真机核对方式：选第 3 条（90.5 公里）后 startNavi，
        // 日志里的 naviPath 必须 ≈ 90500 米。
        val actual = runCatching { engine.naviPath }.getOrNull()
        MapLog.i(
            "$TAG_LOG: startNavi ok=$ok type=$requestedNaviType actualPath=" +
                "${actual?.allLength}m ${actual?.allTime}s lights=${actual?.trafficLightCount} " +
                "labels=${actual?.labels}",
        )
        if (ok) {
            _phase.value = NaviPhase.NAVIGATING
        } else {
            // SDK 拒绝。已知唯一原因：引擎里还有一条没停干净的导航。
            // 这里不急着 destroy（那会把 log 里"到底为什么失败"的线索一起抹掉），
            // 只把阶段退回预览让用户能再点一次。
            MapLog.i("$TAG_LOG: startNavi refused -> back to PREVIEW")
            _phase.value = NaviPhase.PREVIEW
        }
        return ok
    }

    /**
     * 用户明确点"退出导航"（界面需二次确认）。停播报 → 等回调 → 释放引擎。
     *
     * **这是唯一允许结束导航的用户动作**。切屏、翻页、息屏、切到投屏再回来都不走这里。
     */
    fun requestExit() {
        if (_phase.value == NaviPhase.IDLE) return
        MapLog.i("$TAG_LOG: user exit requested in phase=${_phase.value}")
        pendingCalc = null
        restartAfterStop = false
        autoStartAfterCalc = false
        beginStop()
    }

    /** 只清算路结果（用户把目的地删了 / 换了），不动引擎，也不影响正在跑的导航。 */
    fun clearRoute() {
        if (_phase.value == NaviPhase.NAVIGATING || _phase.value == NaviPhase.STARTING) {
            // 导航中清预览没有意义，直接拒绝 —— 免得把 destination 清掉之后
            // 退出确认框连"要去哪儿"都说不出来。
            MapLog.i("$TAG_LOG: clearRoute ignored while phase=${_phase.value}")
            return
        }
        calcJob?.cancel()
        calcJob = null
        _route.value = RouteState.Idle
        _destination.value = null
        _shape.value = null
        lastStart = null
        lastEnd = null
        calcRetryUsed = false
        if (_phase.value != NaviPhase.STOPPING) _phase.value = NaviPhase.IDLE
    }

    // ---- 内部：停止链路 ----------------------------------------------------

    /**
     * 下发 `stopNavi()` 并把阶段推到 [NaviPhase.STOPPING]，等 `onStopNavi` 回来。
     * 回调不来也有 [STOP_TIMEOUT_MILLIS] 兜底，界面不会卡死。
     */
    private fun beginStop() {
        val engine = navi
        _phase.value = NaviPhase.STOPPING
        _guidance.value = null
        stopJob?.cancel()
        if (engine == null) {
            // 引擎都没了（进程刚起、或已经释放过），没有回调可等。
            handleStopped()
            return
        }
        runCatching { engine.stopNavi() }
            .onFailure { MapLog.i("$TAG_LOG: stopNavi threw ${it.javaClass.simpleName}") }
        stopJob = scope.launch {
            delay(STOP_TIMEOUT_MILLIS)
            if (_phase.value == NaviPhase.STOPPING) {
                MapLog.i("$TAG_LOG: stop callback timeout after ${STOP_TIMEOUT_MILLIS}ms -> force finish")
                handleStopped()
            }
        }
    }

    /**
     * 停止流程的收口。三条出路：
     *   · 攒着一次算路（导航中换了目的地）→ 算新的，算完自动开始；
     *   · 攒着一次重启（导航中又点了开始导航）→ 直接重新走 STARTING；
     *   · 都没有 → 真退出：释放引擎、回到 [NaviPhase.IDLE]。
     */
    private fun handleStopped() {
        stopJob?.cancel()
        stopJob = null

        val calc = pendingCalc
        val restart = restartAfterStop
        pendingCalc = null
        restartAfterStop = false

        when {
            calc != null -> {
                MapLog.i("$TAG_LOG: stopped -> recalc new destination then auto start")
                autoStartAfterCalc = true
                val ctx = appContext
                if (ctx == null) {
                    MapLog.i("$TAG_LOG: no context to recalc -> abort")
                    finishExit()
                } else {
                    _phase.value = NaviPhase.PREVIEW
                    doCalculate(ctx, calc.first, calc.second)
                }
            }

            restart -> {
                MapLog.i("$TAG_LOG: stopped -> restart requested, phase=STARTING")
                _phase.value = NaviPhase.STARTING
            }

            else -> finishExit()
        }
    }

    /**
     * 真正的退出。`destroy()` 必须调（静态方法）—— 不调的话下次进来会拿到
     * "半死"的引擎，现象是算路不回调，很难往"上次没释放"上想。
     */
    private fun finishExit() {
        calcJob?.cancel()
        calcJob = null
        stopJob?.cancel()
        stopJob = null
        runCatching { AMapNavi.destroy() }
        navi = null
        _phase.value = NaviPhase.IDLE
        _route.value = RouteState.Idle
        _strategy.value = Strategy.RECOMMENDED
        _destination.value = null
        _guidance.value = null
        // 几何也要清：留着的话退出导航后首页卡还会画着那条线，而路线早就不作数了。
        _shape.value = null
        carPoint = null
        carBearing = null
        lastStart = null
        lastEnd = null
        autoStartAfterCalc = false
        calcRetryUsed = false
        MapLog.i("$TAG_LOG: exit done, engine destroyed, phase=IDLE")
    }

    // ---- 内部：算路 --------------------------------------------------------

    private fun doCalculate(context: Context, start: GeoPoint, end: PlaceSuggestion) {
        val destination = end.point
        if (destination == null) {
            // 走到这里说明界面漏了一次 resolve。宁可在这里明确拒绝，也不要拿 (0,0) 去算路。
            _route.value = RouteState.Failed("这个地点没有坐标")
            _phase.value = NaviPhase.IDLE
            return
        }

        val engine = obtain(context)
        if (engine == null) {
            _route.value = RouteState.Failed("导航引擎不可用")
            _phase.value = NaviPhase.IDLE
            return
        }

        lastStart = start
        lastEnd = end
        _destination.value = end

        _route.value = RouteState.Calculating
        _phase.value = NaviPhase.CALCULATING
        calcJob?.cancel()

        // **一个 job 管两轮超时**：第一轮超时自动重试一次，第二轮还超时才判失败。
        //
        // 为什么不写成"超时后递归调用 doCalculate"：那会在协程内部取消到**自身**
        // （doCalculate 开头就有 `calcJob?.cancel()`），能不能继续跑取决于取消发生在
        // 哪一行 —— 这种事不该赌。线性走完两轮，行为一眼可见。
        calcJob = scope.launch {
            delay(CALC_TIMEOUT_MILLIS)
            if (_route.value !is RouteState.Calculating) return@launch

            if (calcRetryUsed) {
                MapLog.i("$TAG_LOG: calc timeout AGAIN after retry -> give up")
                failCalc(CALC_TIMEOUT_MESSAGE)
                return@launch
            }
            calcRetryUsed = true
            MapLog.i("$TAG_LOG: calc timeout after ${CALC_TIMEOUT_MILLIS / 1000}s -> auto retry once")
            if (!dispatchToEngine(engine, start, end)) {
                failCalc(CALC_REJECTED_MESSAGE)
                return@launch
            }

            delay(CALC_TIMEOUT_MILLIS)
            if (_route.value is RouteState.Calculating) {
                MapLog.i("$TAG_LOG: calc timeout after retry -> give up")
                failCalc(CALC_TIMEOUT_MESSAGE)
            }
        }

        if (!dispatchToEngine(engine, start, end)) {
            failCalc(CALC_REJECTED_MESSAGE)
        }
    }

    /**
     * 只做一件事：把这一次算路请求递给引擎，返回它有没有被接受。
     *
     * 抽出来是为了让"首次请求"与"超时后的重试"走**同一条下发路径** ——
     * 重试若走另一条路，验的就不是同一个东西了。
     */
    private fun dispatchToEngine(
        engine: AMapNavi,
        start: GeoPoint,
        end: PlaceSuggestion,
    ): Boolean {
        val destination = end.point ?: return false
        val startPoi = NaviPoi("起点", LatLng(start.latitude, start.longitude), null)
        val endPoi = NaviPoi(end.name, LatLng(destination.latitude, destination.longitude), end.poiId)
        val accepted = runCatching {
            engine.calculateDriveRoute(startPoi, endPoi, ArrayList<NaviPoi>(), _strategy.value.value)
        }.getOrElse { t ->
            MapLog.i("$TAG_LOG: calc threw ${t.javaClass.simpleName}")
            false
        }
        MapLog.i(
            "$TAG_LOG: calc dispatched start=${start.latitude},${start.longitude} " +
                "end=${destination.latitude},${destination.longitude} " +
                "strategy=${_strategy.value.value} accepted=$accepted",
        )
        return accepted
    }

    /**
     * 算路失败的统一收口（超时 / 被拒 / 没有可用路线都走这里）。
     *
     * 在超时协程**内部**被调用时，`calcJob?.cancel()` 取消的是它自己 —— 这没问题：
     * Kotlin 的取消是协作式的，打上取消标记之后**同步代码仍会继续执行**，
     * 所以紧随其后的几行状态赋值照样生效。
     */
    private fun failCalc(message: String) {
        calcJob?.cancel()
        calcJob = null
        autoStartAfterCalc = false
        _route.value = RouteState.Failed(message)
        _phase.value = NaviPhase.IDLE
    }

    // ---- 内部：引擎 --------------------------------------------------------

    /**
     * 拿引擎（懒创建）。返回 null = 拿不到（合规没过、so 没加载、Key 无效都会走这里）。
     *
     * `getInstance` 在 Java 层是 `throws AMapException`，Kotlin 不强制 catch，
     * 但**必须**接住 —— 探针页当初就是靠它拿到"取不到实例"这个结论的。
     */
    private fun obtain(context: Context): AMapNavi? {
        appContext = context.applicationContext
        navi?.let { return it }

        // 闸门先过：它读的是用户真实同意状态，不是无条件写 true。
        AMapPrivacy.applyIfAgreed(context)

        val engine = runCatching { AMapNavi.getInstance(context.applicationContext) }
            .onFailure { MapLog.i("$TAG_LOG: getInstance threw ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()
            ?: return null

        // 两处都设：`AMapNavi` 侧管运行期，`VoiceAssistSettings` 侧管 View 初始化时的默认值。
        // 漏一处就可能整段沉默（哪一处生效在不同版本里不一样）。
        runCatching { engine.setUseInnerVoice(true) }
        runCatching { engine.setControlMusicVolumeMode(MUSIC_VOLUME_MODE_DEPRESS) }
        engine.addAMapNaviListener(listener)
        navi = engine
        MapLog.i("$TAG_LOG: engine obtained, innerVoice=true, musicVolume=DEPRESS")
        return engine
    }

    private var requestedNaviType: Int = NaviType.GPS

    /**
     * 把 SDK 的多条路径转成界面用的 [RouteOption]，并把每条的数值打进日志 ——
     * "到底算出来几条、每条多远多久"是真机上最容易说不清、也最需要证据的一件事。
     */
    private fun collectOptions(ids: List<Int>): List<RouteOption> {
        val engine = navi ?: return emptyList()
        val paths = runCatching { engine.naviPaths }.getOrNull()

        val fromMap = ids.mapNotNull { id ->
            val path = paths?.get(id) ?: return@mapNotNull null
            RouteOption(
                id = id,
                summary = RouteSummary(path.allLength, path.allTime, path.trafficLightCount),
                labels = path.labels.orEmpty(),
            )
        }
        val result = fromMap.ifEmpty {
            // 某些版本在单路径策略下 routeid 为空，退回"主路径"这一条。
            runCatching { engine.naviPath }.getOrNull()?.let { path ->
                listOf(
                    RouteOption(
                        id = 0,
                        summary = RouteSummary(path.allLength, path.allTime, path.trafficLightCount),
                        labels = path.labels.orEmpty(),
                    ),
                )
            }.orEmpty()
        }
        result.forEach {
            MapLog.i(
                "$TAG_LOG: option id=${it.id} ${it.summary.distanceText} " +
                    "${it.summary.durationText} ${it.summary.trafficLightText} labels=${it.labels}",
            )
        }
        return result
    }

    /**
     * 把"当前选中那条路线"的几何拷成 [NaviShape] 并发布。
     *
     * 失败时**置 null 而不是留着上一条**：留旧几何的后果是首页卡上画着一条已经
     * 不作数的路线，而用户会以为那就是要走的路 —— 比不画更坏。
     */
    private fun publishShape(routeId: Int) {
        val engine = navi
        if (engine == null) {
            _shape.value = null
            return
        }
        val path = runCatching { engine.naviPaths?.get(routeId) }.getOrNull()
            ?: runCatching { engine.naviPath }.getOrNull()
        val coords = path?.let { runCatching { it.coordList }.getOrNull() }.orEmpty()
        val bounds = path?.let { runCatching { it.boundsForPath }.getOrNull() }
        if (coords.isEmpty() || bounds == null) {
            MapLog.i("$TAG_LOG: shape skipped id=$routeId coords=${coords.size} bounds=${bounds != null}")
            _shape.value = null
            return
        }
        // `getCoordList()` 在 Kotlin 眼里是平台类型，元素可能为 null，这里过一遍。
        val points = ArrayList<GeoPoint>(coords.size)
        for (c in coords) {
            if (c != null) points.add(GeoPoint(c.latitude, c.longitude))
        }
        val value = NaviShape(
            points = points,
            south = bounds.southwest.latitude,
            west = bounds.southwest.longitude,
            north = bounds.northeast.latitude,
            east = bounds.northeast.longitude,
        )
        _shape.value = value
        MapLog.i(
            "$TAG_LOG: shape id=$routeId points=${points.size} " +
                "box=${value.south},${value.west}..${value.north},${value.east}",
        )
    }

    /** 把"选中的是哪条"告诉 SDK。失败返回 false，由调用方决定要不要说话。 */
    private fun selectInternal(routeId: Int): Boolean =
        runCatching { navi?.selectRouteId(routeId) ?: false }.getOrDefault(false)

    /**
     * 把 `NaviInfo` 翻成界面要的那几个数。
     *
     * **必须逐字段拷贝**，不能把 `NaviInfo` 对象本身存下来：
     * SDK 在每帧回调里复用同一个对象，存引用等于所有历史快照都会跟着变
     * （现象是"首页卡上的数字自己跳回上一次的值"）。
     */
    private fun publishGuidance(info: NaviInfo?) {
        val src = info ?: return
        val value = NaviGuidance(
            iconType = src.iconType,
            icon = src.iconBitmap,
            turnDistanceMeters = src.curStepRetainDistance,
            currentRoad = src.currentRoadName.orEmpty(),
            nextRoad = src.nextRoadName.orEmpty(),
            remainDistanceMeters = src.pathRetainDistance,
            remainSeconds = src.pathRetainTime,
            remainLights = src.routeRemainLightCount,
            speedKph = src.currentSpeed,
            car = carPoint,
            carBearing = carBearing,
        )
        if (_guidance.value == value) return
        _guidance.value = value

        // 节流落盘：这条日志是"引导数据真的在流"的唯一现场证据，
        // 但 1 Hz 全记会把 log 撑爆（导航一小时 3600 行）。
        val now = System.currentTimeMillis()
        if (now - lastGuidanceLogAt >= GUIDANCE_LOG_INTERVAL_MILLIS) {
            lastGuidanceLogAt = now
            MapLog.i(
                "$TAG_LOG: guide icon=${value.iconType} turn=${value.turnDistanceText} " +
                    "road=${value.currentRoad}->${value.nextRoad} " +
                    "remain=${value.remainDistanceText}/${value.remainTimeText} " +
                    "lights=${value.remainLights} speed=${value.speedKph}",
            )
        }
    }

    private const val GUIDANCE_LOG_INTERVAL_MILLIS = 3_000L

    /**
     * `AMapNaviControlMusicVolumeMode.MUSIC_VOLUME_MODE_DEPRESS` 的常量值。
     *
     * 保留一层别名只是为了在调用点能一眼看出"这是压低音量那一档"。
     */
    private val MUSIC_VOLUME_MODE_DEPRESS: Int =
        com.amap.api.navi.enums.AMapNaviControlMusicVolumeMode.MUSIC_VOLUME_MODE_DEPRESS
}
