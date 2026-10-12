package com.qingke.desk.map

import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.qingke.desk.core.data.GeoPoint
import com.qingke.desk.map.navi.NaviShape
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 导航路线的线色。与高德导航页上的主路线是同一族蓝，切过去不会有"换了条路"的错觉。 */
private const val NAVI_LINE_COLOR: Int = 0xFF2F80ED.toInt()

/** 路线压在所有其他覆盖物之上，但仍在 SDK 自己的定位蓝点之下（Marker 天然在上层）。 */
private const val NAVI_LINE_Z: Float = 8f

/**
 * 折线宽度（dp）。由调用方按当前密度换算成像素再传进来 ——
 * 高德的 `PolylineOptions.width()` 吃的是**屏幕像素**，写死像素值换台机器就不对了。
 */
internal const val NAVI_OVERVIEW_LINE_WIDTH_DP: Float = 4f

/**
 * 首页地图卡上的**导航总览**叠加层：整条路线 + 沿路线移动的车点。
 *
 * ## 为什么是进程级单例，而不是组合里的 `remember`
 *
 * 首页卡与全屏地图页**共用同一个 `MapView`**，但它们是两个互斥的 Compose 组合。
 * 退出导航时用户很可能正站在地图页上，那一刻首页卡的组合早已销毁 ——
 * 折线若只被首页卡的 `remember` 持有，就再也没人拿得到它的引用，
 * 结果是"导航结束了，地图上还挂着一条不作数的路线"，而且看起来完全正常。
 * 存成单例之后，**任何一个组合都能把它清干净**。
 *
 * ## 相机：跟车（2026-10-12 从"整条路线总览"改过来）
 *
 * 车点压在屏幕中心偏下，地图按行进方向转过来，zoom 由视口高度反推
 * （纵向固定覆盖 700 米左右），不写死。这是导航该有的样子：瞟一眼就知道
 * 前面路口长什么样、下一个弯往哪拐。
 *
 * 原先"框住整条路线不动"那版的理由有两条：省电，以及"卡片小、跟车只能看见
 * 眼前一小段"。第一条仍然成立 —— 跟车会让卡片持续加载瓦片，所以翻页 / 息屏时
 * `renderEnabled=false` 会把整块渲染停掉（见 AMapCanvas），不是一直烧。
 * 第二条在真机上站不住：总览那一版卡片上只剩一条细线和一颗点，看不出车正走在
 * 什么样的路上，而"还要走多远"早就有文字告诉你了，不需要靠地图的比例尺去读。
 *
 * 车点还没到时（刚算完路、定位未回）退回总览框住整条路线，拿到车点后自动切跟车。
 * 这不是两种模式，只是**没有车可跟时的兜底**。
 */
internal object NaviMapOverlay {

    /** 当前叠加层挂在哪个 `AMap` 上。MapView 重建后旧引用全部失效，必须靠它认出来。 */
    private var owner: AMap? = null

    private var line: Polyline? = null

    /** 路线底下的白色描边线（和 [line] 用同一组顶点，只是更宽、更白、z 更低）。 */
    private var lineUnderlay: Polyline? = null

    /** 上次画的是哪份几何（**引用比较**：每次 publishShape 都是新实例，够用且零开销）。 */
    private var renderedShape: NaviShape? = null

    /** 已走过的那一段：灰色主线，压在蓝色 [line] 之上。没有车点时不存在。 */
    private var lineTraveled: Polyline? = null

    /** 灰度段的白色描边（和 [lineTraveled] 同顶点、更高更宽，让灰线在深色路面上也看得见）。 */
    private var lineTraveledUnderlay: Polyline? = null

    /**
     * 路线上的方向箭头（一枚 = 一条 V 形折线）。
     *
     * **为什么要它们**：用户 2026-10-13 提的 —— 卡片上只有一条蓝线和一颗车点，
     * 能看出"车在路线上"，看不出"沿哪头走"。尤其路线折返、或者卡片跟车视角下
     * 前方有分岔时，方向这件事完全是猜的。
     *
     * **为什么用 Polyline 而不是 Marker/纹理**：这一整块叠加层在真机上验过四路覆盖物
     * （.verify/reg 的像素统计）：卡片地图上**只有 Polyline 会渲染** —— 自绘 Bitmap 的
     * Marker、SDK 内置 `defaultMarker`、`addCircle`、`addPolygon` 的 `addXxx` 都返回
     * 非 null、位置回读也正确，但**一帧都不画**。所以箭头只能"画成折线"。
     *
     * 用池子（更新 `points` 而不是删了重加）：1 Hz 下删加会看到箭头闪 ——
     * 这条是和车标同一个理由，见 [upsertCarPolyline]。
     */
    private val routeArrows = mutableListOf<Polyline>()

    /**
     * 上次铺箭头时**第一枚箭头钉在路线的第几米处**、zoom 是多少；用来判"要不要重铺"。
     *
     * 记**里程**而不是"锚点坐标"，是这次修 bug 的关键（见 [firstArrowMeters]）：
     * 箭头按绝对里程对齐，车往前走时第一枚的里程只在**跨过一枚**时才变一格，
     * 其余时刻完全相同 —— 按坐标记的话车每挪一米坐标都变，闸门会一直放行，
     * 等于每帧重铺；按"位移超过 40 m 才重铺"记，又会让箭头滞后到灰段里去。
     */
    private var hasArrows = false
    private var arrowFirstMeters = Double.NaN
    private var arrowZoom = 0f
    private var lastArrowLogAt = 0L

    /** 上次成功框完之后，相机停在哪。用它来自检"当前相机还是不是我框的那个"。 */
    private var fittedCenterLat: Double? = null
    private var fittedCenterLng: Double? = null
    private var fittedZoom: Float = 0f

    /**
     * 上次跟车时相机停在哪。用它判"这次的位移值不值得动一次相机"。
     *
     * 与上面的 fitted* 是**两套独立的记号**：前者记"跟车帧"，后者记"总览帧"。
     * 混用会出现"从跟车退回总览时，因为 fitted* 还是旧的而判定不用重框"。
     */
    private var followLat: Double? = null
    private var followLng: Double? = null
    private var followBearing: Float? = null
    private var followZoom: Float = 0f

    /**
     * 车标：**同一组三角顶点的两条 Polyline**（白色宽描边在下、蓝色车头在上）。
     *
     * ## 为什么不用 Marker
     *
     * 2026-10-12 真机四路对照实测（`.verify/reg/G1-home-follow.png`）：
     * 同一张首页卡上放四个覆盖物，只有 Polyline 被画出来 ——
     *
     * | 覆盖物 | 创建 | 像素检测 |
     * | --- | --- | --- |
     * | Polyline（路线，品红试验色） | ok | **25913 px** ✓ |
     * | Marker（我们画的 Bitmap 图标） | ok | 0 |
     * | Marker（SDK 内置 `defaultMarker`） | ok | 0 |
     * | Circle（`addCircle`） | ok | 0 |
     * | Polygon（`addPolygon`） | ok | 0 |
     *
     * 四者的 `addXxx` 都返回非 null、回读 `getPosition()` 也正确，**几何数据全在**，
     * 只是"点/面类"覆盖物在首页卡这张地图上一帧都没画出来（连 SDK 自带的图钉也一样，
     * 所以不是我们那张 Bitmap 的问题）。
     *
     * 于是改用 Polyline 画车点 —— 它是**唯一被验证过能渲染**的覆盖物类型，
     * 而且路线本身就是用它画的，同一个图层，不会出现"线在、点在不了"的情况。
     */
    private var carCore: Polyline? = null
    private var carRing: Polyline? = null


    /**
     * 让叠加层与 [shape] / [car] 保持一致。**幂等**，可以每帧调。
     *
     * @param lineWidthPx 折线宽度（屏幕像素）。由调用方按密度算好传进来 ——
     *                    写死的话换台密度的机器上要么细成头发丝、要么粗成色块。
     * @param densityPxPerDp 1 dp 等于多少屏幕像素。车点的尺寸按 dp 定义、按它换算，
     *                       和折线宽度同一个道理：不能写死像素。
     * @param viewportWidth 地图区域的像素宽。**必须传真实的布局尺寸**，理由见下面相机那段。
     * @param viewportHeight 地图区域的像素高。
     * @param carBearing 车的行进方向（度，正北 0，顺时针）。跟车时地图按它转，
     *                   让"前方"朝屏幕上方。null 表示暂不知道朝向，此时只平移不转向。
     * @param selfPoint 退出导航后视角要回到的位置（一般是上次定位）。
     *                  传 null 表示不知道自己在哪，那就**不动相机** ——
     *                  猜一个坐标把用户扔到别处，比停在原地更糟。
     */
    fun render(
        aMap: AMap,
        shape: NaviShape?,
        car: GeoPoint?,
        lineWidthPx: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        carBearing: Float? = null,
        densityPxPerDp: Float = 1f,
        selfPoint: GeoPoint? = null,
    ) {
        if (owner !== aMap) {
            // MapView 换了实例（Activity 重建 / 首次挂载）：旧引用全部失效，先弃掉。
            dropRefs()
            owner = aMap
        }

        val usable = shape != null && shape.usable
        if (!usable) {
            val had = renderedShape != null
            dropLine()
            dropCar()
            if (had) {
                MapLog.i("navi overlay: cleared (no usable shape)")
                // 退出导航：把视角送回当前位置，别把用户留在一段已经不作数的路线总览上。
                restoreCamera(aMap, selfPoint)
            }
            renderedShape = null
            return
        }

        val shapeChanged = renderedShape !== shape

        if (shapeChanged) {
            dropLine()
            val routePoints = shape.points.map { LatLng(it.latitude, it.longitude) }
            if (routePoints.size >= 2) {
                // 和全屏导航页同款画法：**下面一条白色宽线当描边、上面压一条蓝色主线**。
                // 只有蓝线的话，浅色路面上"路线"和"道路"分不太开（两者都是偏浅的线条）；
                // 白描边是让路线"浮"在底图上的那点立体感，SDK 导航页就是这么画的。
                lineUnderlay = runCatching {
                    aMap.addPolyline(
                        PolylineOptions()
                            .addAll(routePoints)
                            .width(lineWidthPx + NAVI_LINE_UNDERLAY_EXTRA_DP * densityPxPerDp)
                            .color(NAVI_LINE_UNDERLAY_COLOR)
                            .zIndex(NAVI_LINE_Z - 0.5f),
                    )
                }.onFailure { MapLog.w("navi overlay: addPolyline(underlay) failed") }.getOrNull()
                line = runCatching {
                    aMap.addPolyline(
                        PolylineOptions()
                            .addAll(routePoints)
                            .width(lineWidthPx)
                            .color(NAVI_LINE_COLOR)
                            .zIndex(NAVI_LINE_Z),
                    )
                }.onFailure { MapLog.w("navi overlay: addPolyline failed ${it.javaClass.simpleName}") }
                    .getOrNull()
            }
            renderedShape = shape
            MapLog.i(
                "navi overlay: route drawn points=${shape.points.size} " +
                    "box=${shape.south},${shape.west}..${shape.north},${shape.east}",
            )
        }

        // ---- 相机：跟车（有车点）/ 框整条路线（还没拿到车点）------------------
        //
        // 2026-10-12 改成**跟车**。之前是"框住整条路线不动"，理由有两条：省电、
        // 以及"卡片小，跟车只能看见眼前一小段"。真机上用下来后者站不住 ——
        // 总览那一版卡片上只剩一条细线和一颗点，看不出车正走在什么样的路上、
        // 前面路口长什么样，而"接下来要拐哪儿"恰恰是瞟一眼卡片时最想知道的。
        // 所以现在跟着车走，代价是卡片会持续加载瓦片（预览仍是 15 fps，
        // 且翻页 / 息屏时 `renderEnabled=false` 会整块停渲染）。
        //
        // 车点还没到时（刚算完路、定位未回）退回总览，拿到车点后自动切跟车。
        // 这不是"两种模式"，只是**没有车可跟时的兜底**。
        if (car != null) {
            followCamera(aMap, car, carBearing, viewportHeight, densityPxPerDp)
            // 跟车之后，上一次"框好的总览"就不作数了：万一车点断了要退回总览，
            // 必须重新框，否则会拿着旧的 fitted* 判定"不用动"。
            fittedCenterLat = null
            fittedCenterLng = null
            fittedZoom = 0f
        } else {
            overviewCamera(aMap, shape, viewportWidth, viewportHeight, shapeChanged)
            followLat = null
            followLng = null
            followBearing = null
        }

        renderCar(aMap, car, carBearing, densityPxPerDp)
        renderRouteSections(
            aMap = aMap,
            shape = shape,
            car = car,
            lineWidthPx = lineWidthPx,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            densityPxPerDp = densityPxPerDp,
            shapeChanged = shapeChanged,
        )
    }

    /**
     * 相机跟着车走：**车点在正中心**，地图转成行进方向朝上。
     *
     * 车点居中是用户口径（2026-10-12）。中间实现过一版"车点压在中心偏下、前方多露一段"，
     * 那样确实能多看几百米路，但代价是卡片上"我在哪儿"这个点不在视觉中心 ——
     * 瞟一眼的时候需要先把眼睛找过去。卡片只有 460 dp 宽，找这一眼的成本
     * 比多看几百米路的价值大，所以按居中来。
     */
    private fun followCamera(
        aMap: AMap,
        car: GeoPoint,
        bearing: Float?,
        viewportHeightPx: Int,
        densityPxPerDp: Float,
    ) {
        val zoom = followZoom(viewportHeightPx, car.latitude, densityPxPerDp)
        val target = LatLng(car.latitude, car.longitude)

        // 位移太小就不动相机：定位回调 1 Hz，60 km/h 下每次也就十几米，
        // 但**停车等灯**时会连续几十次原地不动，每次都 move 一遍纯属浪费。
        val settled = followLat != null && followLng != null &&
            distanceMeters(followLat!!, followLng!!, car.latitude, car.longitude) <
            FOLLOW_MIN_MOVE_METERS &&
            bearingDelta(followBearing, bearing) < FOLLOW_BEARING_EPSILON &&
            kotlin.math.abs(followZoom - zoom) < CAMERA_ZOOM_EPSILON
        if (settled) return

        val position = CameraPosition(
            target,
            zoom,
            NAVI_FOLLOW_TILT,
            // 行进方向朝屏幕上方。拿不到方向时沿用上一次的，不要转回正北 ——
            // 那会让画面在丢失方向的一瞬间整体转一下。
            bearing ?: followBearing ?: 0f,
        )
        val update = CameraUpdateFactory.newCameraPosition(position)
        val first = followLat == null
        runCatching {
            if (first) {
                // **首次必须瞬移**：上一处视角可能在几十公里外（全屏页 / 上一次导航），
                // 让它慢慢飞过来要飞好几秒，用户以为卡片卡住了。
                aMap.moveCamera(update)
            } else {
                aMap.animateCamera(update, FOLLOW_ANIM_MS, noopCallback)
            }
        }.onFailure { MapLog.w("navi overlay: follow camera failed ${it.javaClass.simpleName}") }

        followLat = car.latitude
        followLng = car.longitude
        followBearing = bearing
        followZoom = zoom

        // 节流：1 Hz 全记会把日志撑爆（导航一小时 3600 行）。
        val now = System.currentTimeMillis()
        if (now - lastFollowLogAt >= FOLLOW_LOG_INTERVAL_MILLIS) {
            lastFollowLogAt = now
            val after = runCatching { aMap.cameraPosition }.getOrNull()
            MapLog.i(
                "navi overlay: camera follow h=${viewportHeightPx}px -> zoom=${after?.zoom} " +
                    "bearing=${after?.bearing} target=${after?.target?.latitude},${after?.target?.longitude}",
            )
        }
    }

    /**
     * 车点还没到时的兜底：把整条路线框进视野。
     *
     * **两个坑，各自对应一处设计。**
     *
     * 坑一：用哪个重载。只给 padding 的 `newLatLngBounds` 读的是 MapView
     * **内部的尺寸缓存**，而 MapView 刚从全屏页被搬进首页卡时，那份缓存还是上一个
     * 父容器的值（2755×1440）。所以必须用带显式 `viewportWidth/Height` 的重载，
     * 尺寸由 AMapCanvas 用 `onSizeChanged` 采真实布局值传进来。
     *
     * 坑二：什么时候框。第一版只在"路线变了"时框一次，而那一次恰好落在
     * MapView 尚未完成搬运的那一帧上 —— 路线画对了，镜头还停在上一处视角。
     * 真机截图 .verify/nav/Z1-home-overview.png：980 个点的线伸出卡片外，
     * 视野还停在起点街区（能看见"重庆鸡公煲"这种店铺标注，说明 zoom 根本没降下去）。
     *
     * 所以改成**自检式重框**：不去判断"这一帧准备好了没有"（判断不了，那是
     * View 内部的事），而是去量"当前相机是不是我上次框成的那个"。不是就再框一次。
     */
    private fun overviewCamera(
        aMap: AMap,
        shape: NaviShape,
        viewportWidth: Int,
        viewportHeight: Int,
        shapeChanged: Boolean,
    ) {
        val camera = runCatching { aMap.cameraPosition }.getOrNull()
        val cameraDrifted = camera == null ||
            fittedCenterLat == null ||
            kotlin.math.abs(camera.zoom - fittedZoom) > CAMERA_ZOOM_EPSILON ||
            kotlin.math.abs(camera.target.latitude - (fittedCenterLat ?: 0.0)) > CAMERA_CENTER_EPSILON ||
            kotlin.math.abs(camera.target.longitude - (fittedCenterLng ?: 0.0)) > CAMERA_CENTER_EPSILON

        if (viewportWidth <= 0 || viewportHeight <= 0) return
        if (!shapeChanged && !cameraDrifted) return

        val bounds = runCatching {
            LatLngBounds(
                LatLng(shape.south, shape.west),
                LatLng(shape.north, shape.east),
            )
        }.getOrNull() ?: return

        runCatching {
            aMap.moveCamera(
                CameraUpdateFactory.newLatLngBounds(
                    bounds,
                    viewportWidth,
                    viewportHeight,
                    NAVI_OVERVIEW_PADDING_PX,
                ),
            )
        }.onFailure { MapLog.w("navi overlay: camera failed ${it.javaClass.simpleName}") }

        // **量结果，不猜。** "调了 moveCamera" 和 "相机真的动了" 是两件事，
        // 只有把 cameraPosition 读回来才分得清。读回来的值同时充当下次的判据。
        val after = runCatching { aMap.cameraPosition }.getOrNull()
        fittedCenterLat = after?.target?.latitude
        fittedCenterLng = after?.target?.longitude
        fittedZoom = after?.zoom ?: 0f
        MapLog.i(
            "navi overlay: camera fit (no car yet) viewport=${viewportWidth}x$viewportHeight " +
                "-> zoom=${after?.zoom} target=${after?.target?.latitude},${after?.target?.longitude}",
        )
    }

    /** 主动清空（Activity 销毁 / 探针复位时用）。 */
    fun clear() {
        dropRefs()
    }

    // ---- 内部 --------------------------------------------------------------

    /**
     * 车点：白环 + 蓝芯，两个同心圆 Polyline（为什么不用 Marker，见字段处的实测表）。
     *
     * 尺寸按 dp 定义，**圆的顶点半径按当前 zoom 换算成米** ——
     * 换算这一步不能省：拉远拉近时车点在屏幕上要保持同样大小，
     * 写死米数就会出现"拉远了缩成一个点、拉近了糊满半张卡"。
     * 线宽本来就是屏幕像素，用 dp 换算即可，与 zoom 无关。
     */
    private fun renderCar(aMap: AMap, car: GeoPoint?, bearing: Float?, densityPxPerDp: Float) {
        if (car == null) {
            dropCar()
            return
        }

        val zoom = runCatching { aMap.cameraPosition }.getOrNull()?.zoom?.toDouble()
            ?: NAVI_FOLLOW_ZOOM_FALLBACK.toDouble()
        val metersPerDp = metersPerDp(zoom, car.latitude)

        // 车标：**朝行进方向的箭头**（和全屏导航页 SDK 自己画的车头是同一个语义），
        // 白色描边在下、蓝色车头在上。
        //
        // 顶点朝"行进方向"而不是固定朝北：地图虽然已经按 bearing 转了（行进方向朝上），
        // 但那要靠 bearing 一直有值；把朝向画进几何里，二者一致时才好看，
        // 不一致（比如刚起步 bearing 还是 0）时箭头至少指着真实方向。
        //
        // 半径按"每 dp 多少米"换算 —— **不要乘屏幕密度**（见 [metersPerDp] 的标定过程）。
        //
        // **描边与车头用同一组顶点、只改线宽**（和上面路线包边同一个做法）。
        // 第一版给描边用了更大的三角（12 dp vs 7 dp），结果尖端各自做斜接，
        // 白三角的尖刺从蓝三角上方戳出来 —— 看着像车标上多了一根白刺。
        // 同形加粗就没有这个问题：白边在每个方向上都是等宽的一条。
        val carShape = arrowPoints(car, CAR_ARROW_RADIUS_DP * metersPerDp, bearing)
        carCore = upsertCarPolyline(
            aMap, carCore, carShape,
            widthPx = (CAR_ARROW_WIDTH_DP * densityPxPerDp).toFloat(),
            color = NAVI_LINE_COLOR,
            z = NAVI_LINE_Z + 1f,
        )
        carRing = upsertCarPolyline(
            aMap, carRing, carShape,
            widthPx = ((CAR_ARROW_WIDTH_DP + 2f * CAR_ARROW_BORDER_DP) * densityPxPerDp).toFloat(),
            color = CAR_RING_COLOR,
            z = NAVI_LINE_Z + 0.5f,
        )

        val now = System.currentTimeMillis()
        if (now - lastCarMarkerLogAt >= FOLLOW_LOG_INTERVAL_MILLIS) {
            lastCarMarkerLogAt = now
            MapLog.i(
                "navi overlay: car arrow zoom=$zoom bearing=$bearing " +
                    "rDp=$CAR_ARROW_RADIUS_DP wDp=$CAR_ARROW_WIDTH_DP " +
                    "metersPerDp=$metersPerDp at ${car.latitude},${car.longitude}",
            )
        }
    }

    // ==== 路线分段：已走过（灰） / 还没走（蓝 + 方向箭头）====================

    /**
     * 按车点把路线切成两段，各画各的。
     *
     * 用户 2026-10-13 的口径：「在未通过的路线加箭头，通过的路线不用箭头，
     * 要是可以做成通过的路线变灰色的话就这样去做」。所以：
     *
     *   · **已走过**（车点之前）：灰色、无箭头；
     *   · **还没走**（车点之后）：蓝色主线 + 白色描边，沿路铺 V 形箭头。
     *
     * 这样"走到哪了"和"往哪走"是同一眼能看到的两件事 ——
     * 比整条路线都铺箭头信息量更大（走到最后整条都是灰的，一眼就知道快到了）。
     *
     * 切点用**投影**而不是"最近的顶点"：车在两顶点之间时，按最近顶点切会让灰/蓝
     * 的分界跳到顶点上（一次跳十几米），而投影点就落在车正下方，分界跟着车平滑走。
     */
    private fun renderRouteSections(
        aMap: AMap,
        shape: NaviShape,
        car: GeoPoint?,
        lineWidthPx: Float,
        viewportWidth: Int,
        viewportHeight: Int,
        densityPxPerDp: Float,
        shapeChanged: Boolean,
    ) {
        if (viewportWidth <= 0 || viewportHeight <= 0) return
        val points = shape.points
        val cut = if (car == null) null else projectOnRoute(points, car)

        val traveled = if (cut == null) {
            emptyList()
        } else {
            ArrayList<GeoPoint>(cut.segIndex + 2).apply {
                for (i in 0..cut.segIndex) add(points[i])
                add(cut.point)
            }
        }
        val remaining = if (cut == null) {
            points
        } else {
            ArrayList<GeoPoint>(points.size - cut.segIndex).apply {
                add(cut.point)
                for (i in cut.segIndex + 1 until points.size) add(points[i])
            }
        }
        // remaining 的**头**在路线上的绝对里程。箭头要靠它对齐到固定的格子上，
        // 见 [renderRouteArrows] —— 没有车点时它是 0（整条路线从头算）。
        val baseMeters = cut?.travelledMeters ?: 0.0

        renderTraveledPart(aMap, traveled, lineWidthPx, densityPxPerDp)
        renderRouteArrows(
            aMap = aMap,
            remaining = remaining,
            baseMeters = baseMeters,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            densityPxPerDp = densityPxPerDp,
            shapeChanged = shapeChanged,
        )
    }

    /** 车在路线上的位置：落在哪一段、投影点在哪、以及**已经走了多少米**。 */
    private data class RouteCut(
        val segIndex: Int,
        val point: GeoPoint,
        val travelledMeters: Double,
    )

    /**
     * 已走过的那一段：灰色主线 + 白色描边，**直接压在蓝色主线之上**（z 更高）。
     *
     * 为什么不"把蓝线砍短、另画一条灰线"：那样每次车一动就得重排两条折线的顶点，
     * 而蓝色那条的顶点是全程的，砍短意味着每帧重建上千个 LatLng。
     * 叠一层灰色覆盖物只需要维护"车点之前"这一小段，且能复用 [upsertRoutePolyline]
     * 的 setPoints 更新（不删不加，1 Hz 下不会闪）。
     */
    private fun renderTraveledPart(
        aMap: AMap,
        traveled: List<GeoPoint>,
        lineWidthPx: Float,
        densityPxPerDp: Float,
    ) {
        if (traveled.size < 2) {
            dropTraveled()
            return
        }
        val latLngs = traveled.map { LatLng(it.latitude, it.longitude) }
        lineTraveledUnderlay = upsertRoutePolyline(
            aMap, lineTraveledUnderlay, latLngs,
            widthPx = lineWidthPx + NAVI_LINE_UNDERLAY_EXTRA_DP * densityPxPerDp,
            color = NAVI_LINE_UNDERLAY_COLOR,
            z = NAVI_TRAVELED_UNDERLAY_Z,
        )
        lineTraveled = upsertRoutePolyline(
            aMap, lineTraveled, latLngs,
            widthPx = lineWidthPx,
            color = NAVI_TRAVELED_COLOR,
            z = NAVI_TRAVELED_Z,
        )
    }

    /**
     * 沿**还没走**的那一段铺一串 V 形箭头，指示行进方向。
     *
     * ## 三个"不这么做就会出问题"的决定
     *
     * 1. **只在看得见的那一段上铺**。整条路线可能有 70 公里、上千个顶点，
     *    按屏幕间距铺满全程就是几千条折线 —— 卡片上根本看不见，白白吃内存和渲染。
     *    所以从车点往前只走"视口半径"那么远就停。视口半径按**对角线的一半**算，
     *    所以总览模式（整条路线塞进视口、没有车点）也覆盖得住。
     *
     * 2. **间距用 dp 换算成米，不写死米数**。高德的 zoom 是 dp 基准的，
     *    一个 dp 代表多少米随 zoom 变（见 [metersPerDp]）。写死"每 50 米一枚"的话，
     *    跟车（zoom≈17.8）时挤成一串，总览（zoom≈10）时稀得看不出方向。
     *    按 dp 走，屏幕上就是恒定的疏密。
     *
     * 3. **朝向烘进几何、不是靠地图旋转**。和车标同一条理由：地图按 bearing 转是
     *    "行进方向朝上"，可箭头要表达的是**每一段自己的走向**（前方要左拐还是右拐）。
     *    所以每枚箭头按它所在那一段的方位角画出顶点，地图怎么转它都对。
     *
     * ## 箭头钉在哪：按**绝对里程**对齐，不按"离车多远"（2026-10-13 修）
     *
     * 第一版是"从车点往前，每 24 m 放一枚"，重铺的闸门是"车点位移超过 40 m"。
     * 真机上那版有一个能被截图证伪的错：**灰段（已走过）上挂着一枚箭头**
     * （`.verify/reg/crop-arrows-down.png`）。原因就是这两个数对不上 ——
     * 相邻两枚箭头只隔 24 m，而允许滞后 40 m，于是车已经走过了最靠前那枚的位置，
     * 箭头还停在原地，落在了灰段里。
     *
     * 现在改成把箭头钉在**路线上距起点 spacing 的整数倍**处（[firstArrowMeters]）：
     * 位置只由"路线几何 + 间距"决定，跟车在哪无关。车往前走只是**吃掉最前面那一枚**，
     * 整串箭头在屏幕上是静止的 —— 顺带也解决了"车一挪整串箭头跳一格"的观感问题。
     *
     * ## 什么时候重铺
     *
     * 几何换了、zoom 变了超过 [ARROW_REBUILD_ZOOM_EPSILON]、或者
     * **第一枚箭头的里程变了**（也就是车刚跨过一枚）。对齐到格子上之后，
     * 车在两枚之间移动的那些帧里第一枚的里程是同一个数，闸门不放行 ——
     * 既不会把箭头落在灰段，也不用每帧重算。
     */
    private fun renderRouteArrows(
        aMap: AMap,
        remaining: List<GeoPoint>,
        baseMeters: Double,
        viewportWidth: Int,
        viewportHeight: Int,
        densityPxPerDp: Float,
        shapeChanged: Boolean,
    ) {
        if (remaining.size < 2) {
            dropArrows()
            return
        }

        // 相机 zoom 回读有一帧延迟，但这里只拿它算"间距该多密"，差一帧无所谓。
        val zoom = runCatching { aMap.cameraPosition }.getOrNull()?.zoom?.toDouble()
            ?: NAVI_FOLLOW_ZOOM_FALLBACK.toDouble()

        val mPerDp = metersPerDp(zoom, remaining.first().latitude)
        val spacingMeters = ARROW_SPACING_DP * mPerDp
        val wingMeters = ARROW_WING_DP * mPerDp
        if (spacingMeters <= 0.0 || wingMeters <= 0.0) return

        val firstMeters = firstArrowMeters(baseMeters, spacingMeters)
        val unchanged = hasArrows &&
            abs(firstMeters - arrowFirstMeters) < ARROW_REBUILD_METER_EPSILON &&
            abs(zoom - arrowZoom) < ARROW_REBUILD_ZOOM_EPSILON &&
            !shapeChanged
        if (unchanged) return

        hasArrows = true
        arrowFirstMeters = firstMeters
        arrowZoom = zoom.toFloat()

        // 覆盖范围：视口对角线的一半，再放宽一点，免得边缘上出现"半个间距"的空白。
        val halfDiagonalDp = 0.5 * hypot(viewportWidth.toDouble(), viewportHeight.toDouble()) /
            densityPxPerDp.coerceAtLeast(0.1f)
        val rangeMeters = halfDiagonalDp * mPerDp * ARROW_RANGE_FACTOR

        val placements = ArrayList<ArrowPlacement>(ARROW_MAX)
        collectArrowPlacements(
            points = remaining,
            baseMeters = baseMeters,
            firstAtMeters = firstMeters,
            spacingMeters = spacingMeters,
            maxDistanceMeters = rangeMeters,
            out = placements,
        )

        // 用池子：先按需增减条数，再逐条 setPoints。**不整批删了重加**，否则会闪。
        val used = placements.size.coerceAtMost(ARROW_MAX)
        while (routeArrows.size > used) {
            val extra = routeArrows.removeAt(routeArrows.size - 1)
            runCatching { extra.remove() }
        }
        while (routeArrows.size < used) {
            val placement = placements[routeArrows.size]
            val created = runCatching {
                aMap.addPolyline(
                    PolylineOptions()
                        .addAll(chevronPoints(placement.point, placement.bearing, wingMeters))
                        .width(ARROW_WIDTH_DP * densityPxPerDp)
                        .color(ARROW_COLOR)
                        .zIndex(NAVI_ARROW_Z),
                )
            }.onFailure { MapLog.w("navi overlay: addPolyline(arrow) failed") }.getOrNull() ?: break
            routeArrows += created
        }
        for (i in routeArrows.indices) {
            val placement = placements.getOrNull(i) ?: break
            runCatching {
                routeArrows[i].points =
                    chevronPoints(placement.point, placement.bearing, wingMeters)
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastArrowLogAt >= FOLLOW_LOG_INTERVAL_MILLIS) {
            lastArrowLogAt = now
            MapLog.i(
                "navi overlay: arrows n=${routeArrows.size} zoom=$zoom " +
                    "spacing=${spacingMeters.toInt()}m range=${rangeMeters.toInt()}m " +
                    "base=${baseMeters.toInt()}m first=${firstMeters.toInt()}m " +
                    "remaining=${remaining.size}pts",
            )
        }
    }

    /** 一枚箭头该画在哪、朝哪。 */
    private data class ArrowPlacement(val point: GeoPoint, val bearing: Float)

    /**
     * 沿 [points] 铺箭头，位置钉在**绝对里程为 spacing 整数倍**的地方。
     *
     * [baseMeters] 是 `points[0]` 在整条路线上的里程（跟车时就是车正下方那一点），
     * [firstAtMeters] 由 [firstArrowMeters] 算出、已经是绝对里程；
     * 这里先折成"相对 `points[0]` 的弧长"再沿段推进。走出 [maxDistanceMeters] 就停。
     */
    private fun collectArrowPlacements(
        points: List<GeoPoint>,
        baseMeters: Double,
        firstAtMeters: Double,
        spacingMeters: Double,
        maxDistanceMeters: Double,
        out: MutableList<ArrowPlacement>,
    ) {
        if (spacingMeters <= 0.0) return
        var travelled = 0.0
        var nextAt = firstAtMeters - baseMeters
        var i = 0
        while (i < points.size - 1 && out.size < ARROW_MAX) {
            val a = points[i]
            val b = points[i + 1]
            val seg = distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
            if (seg > 0.0) {
                val bearing = bearingBetween(a, b)
                while (nextAt <= travelled + seg && out.size < ARROW_MAX) {
                    val t = ((nextAt - travelled) / seg).coerceIn(0.0, 1.0)
                    out += ArrowPlacement(interpolate(a, b, t), bearing)
                    nextAt += spacingMeters
                }
                travelled += seg
                if (travelled > maxDistanceMeters) return
            }
            i++
        }
    }

    /**
     * 第一枚箭头钉在路线的第几米处：**距起点 [spacingMeters] 的整数倍**，
     * 并且至少在 [baseMeters]（车当前位置）前方半个间距。
     *
     * 取整数倍是为了让箭头**钉死在路线上**：车往前挪一米，整数倍还是同一个格子，
     * 整串箭头一帧都不动（而不是跟着车一起飘）。
     * 让出半个间距是为了不压在车标上、也不压到已经走过的那一侧。
     */
    private fun firstArrowMeters(baseMeters: Double, spacingMeters: Double): Double {
        if (spacingMeters <= 0.0) return baseMeters
        val at = (floor(baseMeters / spacingMeters) + 1.0) * spacingMeters
        return if (at < baseMeters + spacingMeters * 0.5) at + spacingMeters else at
    }

    /**
     * V 形箭头的三个点：**左翼 → 顶点 → 右翼**（一条折线两个线段）。
     *
     * 顶点从采样点沿行进方向前移半个翼长，让整枚箭头大致以采样点为中心。
     *
     * **翼长必须显著大于线宽**：高德会把"段长 < 线宽"的几何当退化直接丢掉 ——
     * 这不是推测，车标第一版画圆环（半径 6 dp、24 段、每段约 5 px，比线宽 10 px 还短）
     * 在真机上**一帧都没渲染出来**。这里翼长 7 dp、线宽 2.5 dp，折算约 24 px : 9 px，
     * 留了两倍余量。
     */
    private fun chevronPoints(vertex: GeoPoint, bearingDeg: Float, wingMeters: Double): List<LatLng> {
        val back = (bearingDeg + 180f) % 360f
        val tip = offsetBy(vertex.latitude, vertex.longitude, bearingDeg, wingMeters * 0.5)
        val left = offsetBy(
            tip.latitude, tip.longitude,
            (back + ARROW_SPREAD_DEG) % 360f, wingMeters,
        )
        val right = offsetBy(
            tip.latitude, tip.longitude,
            (back - ARROW_SPREAD_DEG + 360f) % 360f, wingMeters,
        )
        return listOf(left, tip, right)
    }

    /**
     * 从一个点按方位角与距离推出另一个点（小距离下的平面近似，十几米量级够用）。
     *
     * 参数用两个数而不是 [GeoPoint]/[LatLng]：箭头三个顶点里后两个是**从前一个推出来的**，
     * 而 SDK 返回的是 [LatLng] —— 为了这一步去来回转换类型，不如直接吃经纬度。
     */
    private fun offsetBy(
        fromLat: Double,
        fromLng: Double,
        bearingDeg: Float,
        meters: Double,
    ): LatLng {
        val dLat = meters / METERS_PER_DEGREE_LAT
        val dLng = dLat / cos(rad(fromLat))
        val b = rad(bearingDeg.toDouble())
        return LatLng(
            fromLat + dLat * cos(b),
            fromLng + dLng * sin(b),
        )
    }

    /** 两点之间的方位角（正北 0、顺时针）。 */
    private fun bearingBetween(a: GeoPoint, b: GeoPoint): Float {
        val lat1 = rad(a.latitude)
        val lat2 = rad(b.latitude)
        val dLng = rad(b.longitude - a.longitude)
        val y = sin(dLng) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }

    private fun interpolate(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint = GeoPoint(
        a.latitude + (b.latitude - a.latitude) * t,
        a.longitude + (b.longitude - a.longitude) * t,
    )

    /**
     * 把 [target] 投影到路线上，返回**（落在哪一段, 投影点, 已经走了多少米）**。
     *
     * 用投影而不是"最近的顶点"，是为了让灰/蓝的分界平滑跟着车走 ——
     * 按最近顶点切的话，车在两个顶点之间移动时分界会一次跳一整段。
     *
     * 里程是给箭头用的（[firstArrowMeters] 要的是"车在路线的第几米"）：
     * 先在找最近段那一遍里记下段号，再沿 0..段号 累加一遍弧长。
     * 多一遍 O(n) —— 981 个顶点、1 Hz，比把"每段的累计里程"另存一份便宜。
     */
    private fun projectOnRoute(points: List<GeoPoint>, target: GeoPoint): RouteCut? {
        if (points.size < 2) return null
        val cosLat = cos(rad(target.latitude))
        var bestIndex = 0
        var bestT = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            // 在"以 target 为原点、单位为米"的平面里算，省掉每段一次三角函数
            val ax = (a.longitude - target.longitude) * cosLat * METERS_PER_DEGREE_LAT
            val ay = (a.latitude - target.latitude) * METERS_PER_DEGREE_LAT
            val bx = (b.longitude - target.longitude) * cosLat * METERS_PER_DEGREE_LAT
            val by = (b.latitude - target.latitude) * METERS_PER_DEGREE_LAT
            val dx = bx - ax
            val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 <= 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
            val px = ax + dx * t
            val py = ay + dy * t
            val dist = px * px + py * py
            if (dist < bestDist) {
                bestDist = dist
                bestIndex = i
                bestT = t
            }
        }
        val a = points[bestIndex]
        val b = points[bestIndex + 1]
        val point = interpolate(a, b, bestT)
        var travelled = 0.0
        for (i in 0 until bestIndex) {
            travelled += distanceMeters(
                points[i].latitude, points[i].longitude,
                points[i + 1].latitude, points[i + 1].longitude,
            )
        }
        travelled += distanceMeters(
            a.latitude, a.longitude, point.latitude, point.longitude,
        )
        return RouteCut(bestIndex, point, travelled)
    }

    /** 有就更新、没有就新建。**更新而不是删了重加**：1 Hz 下删加会看到车点闪。 */
    private fun upsertCarPolyline(        aMap: AMap,
        existing: Polyline?,
        points: List<LatLng>,
        widthPx: Float,
        color: Int,
        z: Float,
    ): Polyline? {
        if (existing != null) {
            runCatching {
                existing.points = points
                existing.width = widthPx
            }.onFailure { MapLog.w("navi overlay: car polyline update failed ${it.javaClass.simpleName}") }
            return existing
        }
        return runCatching {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .width(widthPx)
                    .color(color)
                    .zIndex(z),
            )
        }.onFailure { MapLog.w("navi overlay: car polyline failed ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    /**
     * 路线类折线的"有就更新、没有就新建"。和 [upsertCarPolyline] 同一个理由：
     * 1 Hz 下删了重加会看到线段闪一下。
     */
    private fun upsertRoutePolyline(
        aMap: AMap,
        existing: Polyline?,
        points: List<LatLng>,
        widthPx: Float,
        color: Int,
        z: Float,
    ): Polyline? {
        if (existing != null) {
            runCatching {
                existing.points = points
            }.onSuccess { return existing }
        }
        return runCatching {
            aMap.addPolyline(
                PolylineOptions()
                    .addAll(points)
                    .width(widthPx)
                    .color(color)
                    .zIndex(z),
            )
        }.onFailure { MapLog.w("navi overlay: addPolyline(route part) failed") }.getOrNull()
    }

    /**
     * 箭头（三角形的三个顶点 + 回到起点）。
     *
     * **为什么是三角不是圆**：两个原因。
     * ① 语义上要跟全屏导航页一致 —— SDK 画的是朝行进方向的车头，圆点表达不出"朝哪走"。
     * ② 几何上圆画不出来：真机上 24 段、半径 6 dp 的圆环一帧都没渲染
     *    （每段约 5 px，比线宽 10 px 还短，高德把这种"段长 < 线宽"的几何当退化丢掉）。
     *    三角形的边长 = 半径 × √3，半径 7 dp 时约 12 dp，长于线宽，稳定。
     *
     * @param bearingDeg 行进方向（正北 0、顺时针）。null 时按正北画 —— 总比不画好。
     */
    private fun arrowPoints(center: GeoPoint, radiusMeters: Double, bearingDeg: Float?): List<LatLng> {
        val base = bearingDeg ?: 0f
        val dLat = radiusMeters / METERS_PER_DEGREE_LAT
        val dLng = dLat / cos(rad(center.latitude))
        val tip = LatLng(
            center.latitude + dLat * cos(rad(base.toDouble())),
            center.longitude + dLng * sin(rad(base.toDouble())),
        )
        val left = LatLng(
            center.latitude + dLat * cos(rad((base + 120f).toDouble())),
            center.longitude + dLng * sin(rad((base + 120f).toDouble())),
        )
        val right = LatLng(
            center.latitude + dLat * cos(rad((base + 240f).toDouble())),
            center.longitude + dLng * sin(rad((base + 240f).toDouble())),
        )
        return listOf(tip, right, left, tip)
    }

    /** 给定 zoom 与纬度，**一个 dp** 代表多少米。
     *
     * ## 为什么是 dp 不是物理像素（2026-10-12 实测标定）
     *
     * 一开始按"物理像素"理解这个公式，结果车点画出来是个直径 **327 px** 的大白圈 ——
     * 比设计尺寸大了 3.5 倍，正好是本机屏幕密度。同一版里还有两个内联探针能互相标定：
     * 两个圆心南北相隔 0.0004°（44.5 米），屏幕上量出来相距 259 px，即 1 px ≈ 0.174 米；
     * 而按物理像素算出来的是 0.55 米/px，**差 3.16 ≈ density**。
     *
     * 结论：高德的 zoom 是**以 dp 为基准**的（它把 1 个"像素"当成 1 dp），
     * 屏幕密度只影响最后上屏时的放大倍数。所以：
     *   · 几何半径（米）→ 用"每 dp 多少米"换算；
     *   · `PolylineOptions.width()` → 仍然是**物理像素**（路线 4 dp 宽实测就是 14 px），
     *     所以线宽要乘 density 之后传进去。
     * 这两条不一致，是高德自己的约定，别想着统一它，按实测来。
     */
    private fun metersPerDp(zoom: Double, latitude: Double): Double =
        MERCATOR_METERS_PER_PX_AT_Z0 * cos(rad(latitude)) / 2.0.pow(zoom)

    /**
     * 把视角送回设备当前位置。拿不到定位时什么都不做 —— 宁可停在原处，
     * 也不要猜一个坐标把用户扔到别的地方去。
     */
    private fun restoreCamera(aMap: AMap, target: GeoPoint?) {
        if (target == null) {
            MapLog.i("navi overlay: camera kept (no known self position)")
            return
        }
        runCatching {
            aMap.moveCamera(
                CameraUpdateFactory.newLatLngZoom(
                    LatLng(target.latitude, target.longitude),
                    MAP_ZOOM_STREET,
                ),
            )
        }
        MapLog.i("navi overlay: camera restored to self")
    }

    private fun dropLine() {
        runCatching { line?.remove() }
        line = null
        runCatching { lineUnderlay?.remove() }
        lineUnderlay = null
        // 方向箭头与灰段都属于"路线"这一组：路线没了它们也必须一起走，
        // 否则退出导航后底图上会剩一串指着空处的 V 形、和一条不作数的灰线。
        dropArrows()
        dropTraveled()
    }

    private fun dropTraveled() {
        runCatching { lineTraveled?.remove() }
        lineTraveled = null
        runCatching { lineTraveledUnderlay?.remove() }
        lineTraveledUnderlay = null
    }

    private fun dropArrows() {
        routeArrows.forEach { runCatching { it.remove() } }
        routeArrows.clear()
        // 里程也要清：留着旧值的话，下次进来"第一枚的里程没变"会误判成不用重铺，
        // 于是路线换了、箭头却还是上一趟那几枚。
        hasArrows = false
        arrowFirstMeters = Double.NaN
    }

    private fun dropCar() {
        runCatching { carCore?.remove() }
        carCore = null
        runCatching { carRing?.remove() }
        carRing = null
    }

    private fun dropRefs() {
        dropLine()
        dropCar()
        renderedShape = null
        owner = null
        // 跟车 / 总览两套记号一起清。**不清 follow* 的话 MapView 换实例之后会被当成
        // "已经在跟车"，首次不瞬移而是 animateCamera —— 从几十公里外飞过来的那几秒，
        // 看起来就像卡片卡住了。**
        followLat = null
        followLng = null
        followBearing = null
        followZoom = 0f
    }

    // ---- 车标尺寸（dp）------------------------------------------------------
    //
    // 箭头的大小用"外接圆半径"表示。等边三角形的内切圆半径 = 外接圆半径 ÷ 2。
    // **线宽必须大于 2×内切圆半径，否则中心漏白**：折线的描边是沿路径往两边各铺
    // 线宽的一半，三角形正中心到三条边的距离就是内切圆半径 —— 半径 8 dp 时内切圆
    // 4 dp，线宽若只有 6 dp（半宽 3 dp）就盖不到中心。真机上那一小块露出的是底下
    // 路线的白色包边，看起来像车标中间挖了个白洞（`.verify/reg/B-zoom.png` 第一版）。
    //   · 蓝色车头：半径 8 dp、线宽 9 dp（半宽 4.5 dp > 内切圆 4 dp，填满）；
    //   · 白色描边：**同一组顶点**、线宽 9 + 2×1.5 = 12 dp，压在下层，每边露 1.5 dp 白。
    // 白边不是装饰：卡片的地图底色有深有浅，只有蓝箭头在深色路面上会糊成一块。
    // 整体外接圆约 19 dp，和全屏导航页 SDK 自己画的车头同一个量级
    // （SDK 那个是"圆形徽章 + 白色车头"，圆盘画不出来，见字段处的四路实测表）。
    //
    // **半径不能太小**：三角形边长 = 半径 × √3，必须长于线宽，否则高德不画
    // （圆环那版半径 6 dp、24 段，每段只有 5 px，比线宽还短，真机上一帧都没出来）。

    private const val CAR_ARROW_RADIUS_DP: Double = 8.0
    private const val CAR_ARROW_WIDTH_DP: Float = 9f

    /** 车头蓝线两侧各露多少 dp 白边。 */
    private const val CAR_ARROW_BORDER_DP: Float = 1.5f

    /** 描边的颜色（车头用折线色）。 */
    private const val CAR_RING_COLOR: Int = 0xFFFFFFFF.toInt()

    /** 路线白色底衬比蓝色主线每边宽多少 dp。 */
    private const val NAVI_LINE_UNDERLAY_EXTRA_DP: Float = 2.5f

    /** 路线白色底衬的颜色。和 SDK 导航页一样，让路线从底图上"浮"起来。 */
    private const val NAVI_LINE_UNDERLAY_COLOR: Int = 0xFFFFFFFF.toInt()

    // ---- 路线方向箭头 ------------------------------------------------------

    /** 箭头压在路线之上、但仍在车标之下（车标是 NAVI_LINE_Z + 0.5 / +1）。 */
    private const val NAVI_ARROW_Z: Float = NAVI_LINE_Z + 0.7f

    /**
     * **已走过**那段的主线颜色。中性的冷灰：
     * 它要"退到背景里去"，所以不能像蓝色主线那样跳；但也得和底图的浅色路面区分开，
     * 所以不用纯灰 —— 带一点蓝，和整条路线的色系是同一族。
     */
    private const val NAVI_TRAVELED_COLOR: Int = 0xFF9AA5B1.toInt()

    /** 灰段的白色描边 z 比主线低一点（和蓝段的白描边同一个层级关系）。 */
    private const val NAVI_TRAVELED_UNDERLAY_Z: Float = NAVI_LINE_Z + 0.2f

    /**
     * 灰段主线的 z。**必须高于蓝色主线 [NAVI_LINE_Z]** ——
     * 蓝线画的是全程，灰段是"盖在它上面"表达"这段走过了"。
     */
    private const val NAVI_TRAVELED_Z: Float = NAVI_LINE_Z + 0.3f

    /**
     * 箭头颜色。用纯白：路线主线是 #2F80ED，白在蓝上对比度最高，
     * 而且和 SDK 导航页上那排白色转向箭头是同一套观感。
     */
    private const val ARROW_COLOR: Int = 0xFFFFFFFF.toInt()

    /** 箭头线宽（dp）。**翼长与它的比值不能小**，理由见 [chevronPoints]。 */
    private const val ARROW_WIDTH_DP: Float = 2.5f

    /** 单翼长度（dp）。7 dp 对 2.5 dp 线宽，约 2.7 倍余量。 */
    private const val ARROW_WING_DP: Double = 7.0

    /** 两翼张开的角度。32° 是"看得出是箭头、又不会宽到盖住路面"的折中。 */
    private const val ARROW_SPREAD_DEG: Float = 32f

    /**
     * 相邻箭头的间距（dp）。44 dp 在卡片（460 dp 宽）上大约能同时看到 8~10 枚，
     * 密到一眼看出走向、又不至于把路线糊成虚线。
     */
    private const val ARROW_SPACING_DP: Double = 44.0

    /** 覆盖半径比"视口对角线的一半"再放宽多少，免得视口边缘上差半个间距就没了箭头。 */
    private const val ARROW_RANGE_FACTOR: Double = 1.15

    /** 条数上限。防呆：真出现异常密集的几何时也不让卡片被几千条折线拖死。 */
    private const val ARROW_MAX: Int = 40

    /**
     * 第一枚箭头的里程变化不到这个数（半米）就不重铺。
     *
     * 原来这里是"锚点移动 40 米"，配 24 米的间距正好让箭头滞后一整格 ——
     * 那正是"箭头跑到灰段里"的原因，别再改回去。
     */
    private const val ARROW_REBUILD_METER_EPSILON: Double = 0.5

    /** zoom 变化小于这个值不重铺（间距是靠 zoom 算的，变了就得重排）。 */
    private const val ARROW_REBUILD_ZOOM_EPSILON: Double = 0.1

    /** 相机"算不算还停在框好的位置上"的容差。zoom 差 0.05、中心差 0.0005°（约 50 米）。 */
    private const val CAMERA_ZOOM_EPSILON: Float = 0.05f
    private const val CAMERA_CENTER_EPSILON: Double = 0.0005

    /** 路线包围盒四周留的边距（屏幕像素）。一点不留的话路线会贴着卡片边缘，看着很挤。 */
    private const val NAVI_OVERVIEW_PADDING_PX: Int = 64

    // ---- 跟车 --------------------------------------------------------------

    /**
     * 卡片纵向**在屏幕上**要覆盖多少米的路。**zoom 由它和视口高度反推，不写死** ——
     * 写死 zoom 的话换一台卡片高度不同的机器（或以后把卡片做大做小），
     * 看到的远近就变了。
     *
     * 200 米是"城市道路下一个路口看得见、又不会糊成一团"的量级：
     * 60 km/h 下大约是 12 秒的路。注意这个值**不是** dp 意义上的（换算见 [followZoom]）。
     */
    private const val LOOK_AHEAD_METERS: Double = 200.0

    /** 墨卡托投影下 zoom 0 时、赤道上一个像素代表多少米。反推 zoom 要用。 */
    private const val MERCATOR_METERS_PER_PX_AT_Z0: Double = 156543.03392804097

    /** zoom 的上下限。算出来的值再合理也要夹住 —— 太高会糊，太低看不清路口。 */
    private const val NAVI_FOLLOW_ZOOM_MIN: Float = 15f
    private const val NAVI_FOLLOW_ZOOM_MAX: Float = 19f

    /** 视口尺寸还没量到时的兜底 zoom（卡片高度约 1270 px 时算出来是 17.8）。 */
    private const val NAVI_FOLLOW_ZOOM_FALLBACK: Float = 17.5f

    /** 跟车视角不带俯仰：卡片只有 411 dp 高，2D 俯视比 3D 更能看清路口形状。 */
    private const val NAVI_FOLLOW_TILT: Float = 0f

    /** 位移小于这个值（3 米）就不动相机。停车等灯时定位仍在 1 Hz 回调。 */
    private const val FOLLOW_MIN_MOVE_METERS: Double = 3.0

    /** 朝向变化小于 8° 不重新转地图 —— 一点点抖动就转，画面会一直微微晃。 */
    private const val FOLLOW_BEARING_EPSILON: Float = 8f

    /**
     * 跟车的动画时长。位置回调 1 Hz，60 km/h 下每次前进约 17 米；
     * 用 900 ms 把它摊平，看起来是连续移动而不是每秒跳一格。
     */
    private const val FOLLOW_ANIM_MS: Long = 900L

    /** 跟车日志的节流间隔。1 Hz 全记会把日志撑爆。 */
    private const val FOLLOW_LOG_INTERVAL_MILLIS: Long = 5_000L

    private var lastFollowLogAt = 0L

    /** 车点回读日志的节流。和相机日志错开写，免得同一秒挤两条。 */
    private var lastCarMarkerLogAt = 0L

    /** `animateCamera` 要一个回调，我们不需要。两个方法都不是 SAM，只能老实实现。 */
    private val noopCallback = object : AMap.CancelableCallback {
        override fun onFinish() {}
        override fun onCancel() {}
    }

    /**
     * 按视口高度反推 zoom：让纵向正好覆盖 [LOOK_AHEAD_METERS] 米。
     * 墨卡托下每像素米数 = `MERCATOR_METERS_PER_PX_AT_Z0 × cos(纬度) ÷ 2^zoom`。
     */
    private fun followZoom(viewportHeightPx: Int, latitude: Double, densityPxPerDp: Float): Float {
        if (viewportHeightPx <= 0) return NAVI_FOLLOW_ZOOM_FALLBACK
        // 视口高度先折成 dp —— zoom 是 dp 基准的（见 metersPerDp 的标定过程）。
        val heightDp = viewportHeightPx / densityPxPerDp
        val metersPerDp = LOOK_AHEAD_METERS / heightDp
        val z = log2(MERCATOR_METERS_PER_PX_AT_Z0 * cos(rad(latitude)) / metersPerDp)
        return z.toFloat().coerceIn(NAVI_FOLLOW_ZOOM_MIN, NAVI_FOLLOW_ZOOM_MAX)
    }

    /** 两点距离的平面近似。判"动没动"只要个量级，不需要测地线精度。 */
    private fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = (lat2 - lat1) * METERS_PER_DEGREE_LAT
        val dLng = (lng2 - lng1) * METERS_PER_DEGREE_LAT * cos(rad((lat1 + lat2) / 2.0))
        return sqrt(dLat * dLat + dLng * dLng)
    }

    /** 两个朝向的最小夹角（0..180）。有一边是 null 就当作完全不同，强制更新。 */
    private fun bearingDelta(a: Float?, b: Float?): Float {
        if (a == null || b == null) return 180f
        val d = abs(a - b) % 360f
        return if (d > 180f) 360f - d else d
    }

    /** 纬度一度约多少米。 */
    private const val METERS_PER_DEGREE_LAT: Double = 111_320.0

    /** 角度转弧度。stdlib 的 `kotlin.math` 没有 toRadians，自己写一个比拉 java.lang.Math 干净。 */
    private fun rad(degrees: Double): Double = degrees * kotlin.math.PI / 180.0
}
