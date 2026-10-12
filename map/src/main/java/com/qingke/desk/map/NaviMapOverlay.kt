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
import kotlin.math.cos
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

    /** 有就更新、没有就新建。**更新而不是删了重加**：1 Hz 下删加会看到车点闪。 */
    private fun upsertCarPolyline(
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
