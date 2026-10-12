package com.qingke.desk.map.navi

import android.content.Context
import com.amap.api.maps.model.LatLng
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
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
 * @param id 路线 id。`AMapNavi.selectRouteId(id)` 用它选中 —— 这是"用户点了第二条"
 *           能否真的按第二条导航的关键（见 [NaviEngine.select]）。
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

    /** 正在算（有 20 秒兜底超时，见 [NaviEngine.CALC_TIMEOUT_MILLIS]）。 */
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
 * 导航引擎。**进程级单例**（`AMapNavi` 本身就是单例，见 javap：
 * `public static synchronized AMapNavi getInstance(Context)`），
 * 所以它也用 object，而不是每个界面 new 一个 —— 两个实例会互相抢导航状态。
 *
 * 三条硬约束（都是 SDK 层面的事实，不是风格选择）：
 *   1. **合规闸门必须在 `getInstance` 之前**：`AMapPrivacy.applyIfAgreed()` 会读用户
 *      真实同意状态并同步给地图与导航两套闸门（缺导航那套时，实例拿得到但回调永远不来）；
 *   2. **`destroy()` 是静态方法**（`public static synchronized void destroy()`），
 *      退出导航必须调它，否则下次进来会拿到"半死"的引擎 —— 现象是算路不回调；
 *   3. **回调在主线程**，所以界面可以放心在回调里改 Compose state。
 */
object NaviEngine {

    private const val TAG_LOG = "navi"

    /** 算路兜底超时。真机实测正常情况下 5 秒内出结果，20 秒还没来就是真出问题了。 */
    private const val CALC_TIMEOUT_MILLIS = 20_000L

    /**
     * 界面上可选的策略。**这是给用户的三档偏好，不是 SDK 常量的全集**：
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

    private val _route = MutableStateFlow<RouteState>(RouteState.Idle)
    val route: StateFlow<RouteState> = _route.asStateFlow()

    /** 当前策略。换策略会自动重算（见 [switchStrategy]）。 */
    private val _strategy = MutableStateFlow(Strategy.RECOMMENDED)
    val strategy: StateFlow<Strategy> = _strategy.asStateFlow()

    /** 是否正在导航中。界面靠它决定挂导航 View 还是搜索界面。 */
    private val _navigating = MutableStateFlow(false)
    val navigating: StateFlow<Boolean> = _navigating.asStateFlow()

    private var navi: AMapNavi? = null
    private var timeoutJob: Job? = null

    /** 上一次算路的起终点：换策略时要拿它重算，不然就得让界面再传一遍。 */
    private var lastStart: GeoPoint? = null
    private var lastEnd: PlaceSuggestion? = null

    /**
     * 监听器只注册一次，挂在进程级引擎上。
     *
     * 为什么不做 add/remove 配对：引擎的生命周期就是进程的生命周期，
     * 界面来去不影响它；反过来"每进一次导航屏 add 一次"会积累监听器，
     * 同一次回调被调用多次（真机上表现为日志重复）。
     */
    private val listener = object : SimpleNaviListener() {

        override fun onInitNaviSuccess() {
            MapLog.i("$TAG_LOG: engine init ok")
        }

        override fun onInitNaviFailure() {
            MapLog.i("$TAG_LOG: engine init FAILED")
        }

        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult) {
            timeoutJob?.cancel()
            val ids = result.routeid?.toList().orEmpty()
            MapLog.i("$TAG_LOG: calc ok code=${result.errorCode} routeIds=$ids")
            val options = collectOptions(ids)
            _route.value = if (options.isEmpty()) {
                MapLog.i("$TAG_LOG: calc ok but no usable path")
                RouteState.Failed("没有可用路线")
            } else {
                // 默认选中第一条，并把"选中"这件事**同步给 SDK**：
                // 否则 startNavi 走的可能是 SDK 自己的主路线，出现"界面高亮第二条、
                // 实际按第一条导航"这种最坏情况（界面在撒谎，而且没人能一眼看出来）。
                selectInternal(options.first().id)
                RouteState.Ready(options, 0)
            }
        }

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult) {
            timeoutJob?.cancel()
            // 错误原文照抄进日志：真机排查时它就是去控制台对账的唯一线索。
            MapLog.i(
                "$TAG_LOG: calc FAILED code=${result.errorCode} " +
                    "desc=${result.errorDescription} detail=${result.errorDetail}",
            )
            _route.value = RouteState.Failed("算路失败（${result.errorCode}）")
        }

        override fun onStartNavi(type: Int) {
            MapLog.i("$TAG_LOG: onStartNavi type=$type")
            _navigating.value = true
        }

        override fun onArriveDestination() {
            MapLog.i("$TAG_LOG: arrive destination")
            _navigating.value = false
        }

        override fun onStopNavi() {
            MapLog.i("$TAG_LOG: navi stopped")
            _navigating.value = false
        }

        @Suppress("DEPRECATION")
        override fun onGetNavigationText(text: String?) {
            // 内置语音自己会播；这一条只用来在真机上确认"播报确实发生了"。
            MapLog.i("$TAG_LOG: speak ${text?.take(30)}")
        }
    }

    /**
     * 算一条自驾路线。**不抛异常**：所有失败都落到 [RouteState.Failed]，
     * 界面不需要 try/catch。
     */
    fun calculate(
        context: Context,
        start: GeoPoint,
        end: PlaceSuggestion,
        strategy: Int = _strategy.value.value,
    ) {
        val destination = end.point
        if (destination == null) {
            // 走到这里说明界面漏了一次 resolve。宁可在这里明确拒绝，也不要拿 (0,0) 去算路。
            _route.value = RouteState.Failed("这个地点没有坐标")
            return
        }

        val engine = obtain(context)
        if (engine == null) {
            _route.value = RouteState.Failed("导航引擎不可用")
            return
        }

        lastStart = start
        lastEnd = end

        _route.value = RouteState.Calculating
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CALC_TIMEOUT_MILLIS)
            if (_route.value is RouteState.Calculating) {
                MapLog.i("$TAG_LOG: calc timeout after ${CALC_TIMEOUT_MILLIS / 1000}s")
                _route.value = RouteState.Failed("算路超时")
            }
        }

        val startPoi = NaviPoi("起点", LatLng(start.latitude, start.longitude), null)
        val endPoi = NaviPoi(end.name, LatLng(destination.latitude, destination.longitude), end.poiId)

        val accepted = runCatching {
            engine.calculateDriveRoute(startPoi, endPoi, ArrayList<NaviPoi>(), strategy)
        }.getOrElse { t ->
            MapLog.i("$TAG_LOG: calc threw ${t.javaClass.simpleName}")
            false
        }

        MapLog.i(
            "$TAG_LOG: calc start=${start.latitude},${start.longitude} " +
                "end=${destination.latitude},${destination.longitude} " +
                "strategy=$strategy accepted=$accepted",
        )
        if (!accepted) {
            timeoutJob?.cancel()
            _route.value = RouteState.Failed("算路请求被拒绝")
        }
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
        calculate(context, start, end, strategy.value)
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
        _route.value = current.copy(selectedIndex = index)
    }

    /**
     * 开始实时导航（GPS），走**当前选中的那条路线**。
     *
     * 先 selectRouteId 再 startNavi：`startNavi(int)` 的参数是 NaviType（GPS/EMULATOR），
     * 不是路线 id（javap 核实过签名），所以"走哪条"只能靠 selectRouteId 事先指定。
     */
    fun startNavi(context: Context): Boolean {
        val engine = obtain(context) ?: return false
        (_route.value as? RouteState.Ready)?.selected?.let { selectInternal(it.id) }
        val ok = runCatching { engine.startNavi(NaviType.GPS) }.getOrDefault(false)

        // 核验"界面高亮的那条 == 引擎真正在走的那条"。
        //
        // 这是本功能**唯一会撒谎的地方**，而且撒起谎来没人能一眼看出来：界面把第 3 条描边高亮，
        // 引擎却按第 1 条算距离/播报/转弯。`startNavi(int)` 的参数是 NaviType 不是路线 id，
        // 所以"走哪条"完全依赖前面那次 `selectRouteId` 有没有被 SDK 接受 —— 它返回 false 我们也
        // 只能记一句日志，没法拦住。所以要**量引擎自己的 `naviPath`**，而不是问界面。
        // 真机核对方式：选第 3 条（90.5 公里）后 startNavi，日志里的 naviPath 必须 ≈ 90500 米。
        val actual = runCatching { engine.naviPath }.getOrNull()
        MapLog.i(
            "$TAG_LOG: startNavi ok=$ok actualPath=" +
                "${actual?.allLength}m ${actual?.allTime}s lights=${actual?.trafficLightCount} " +
                "labels=${actual?.labels}",
        )
        if (ok) _navigating.value = true
        return ok
    }

    /**
     * 退出导航并**清干净**。三件事缺一不可：
     * stopNavi（停播报）→ 复位状态 → destroy（释放引擎，静态方法）。
     */
    fun stopNavi() {
        val engine = navi
        timeoutJob?.cancel()
        timeoutJob = null
        if (engine != null) {
            runCatching { engine.stopNavi() }
            navi = null
        }
        runCatching { AMapNavi.destroy() }
        _navigating.value = false
        _route.value = RouteState.Idle
        lastStart = null
        lastEnd = null
        MapLog.i("$TAG_LOG: stopped & destroyed")
    }

    /** 只清算路结果（用户把目的地删了 / 换了），不动引擎。 */
    fun clearRoute() {
        timeoutJob?.cancel()
        timeoutJob = null
        _route.value = RouteState.Idle
        lastStart = null
        lastEnd = null
    }

    /**
     * 拿引擎（懒创建）。返回 null = 拿不到（合规没过、so 没加载、Key 无效都会走这里）。
     *
     * `getInstance` 在 Java 层是 `throws AMapException`，Kotlin 不强制 catch，
     * 但**必须**接住 —— 探针页当初就是靠它拿到"取不到实例"这个结论的。
     */
    private fun obtain(context: Context): AMapNavi? {
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

    /** 把"选中的是哪条"告诉 SDK。失败返回 false，由调用方决定要不要说话。 */
    private fun selectInternal(routeId: Int): Boolean =
        runCatching { navi?.selectRouteId(routeId) ?: false }.getOrDefault(false)

    /**
     * `AMapNaviControlMusicVolumeMode.MUSIC_VOLUME_MODE_DEPRESS` 的常量值。
     *
     * 保留一层别名只是为了在调用点能一眼看出"这是压低音量那一档"。
     */
    private val MUSIC_VOLUME_MODE_DEPRESS: Int =
        com.amap.api.navi.enums.AMapNaviControlMusicVolumeMode.MUSIC_VOLUME_MODE_DEPRESS
}
