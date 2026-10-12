package com.qingke.desk.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptor
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.Polyline
import com.amap.api.maps.model.PolylineOptions
import com.qingke.desk.core.data.GeoPoint
import com.qingke.desk.map.navi.NaviShape

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
 * ## 相机：框住整条路线，不跟车
 *
 * 相机用 `newLatLngBounds` 把整条路线框进视野，之后不再动。
 * 不做跟车有两个理由：一是这台设备 24 小时常亮，画面一直动等于一直耗电；
 * 二是卡片只有 460×363 dp，跟车时只能看见眼前一小段，"我到哪儿了、还要走多远"
 * 反而看不出来 —— 整条路线的总览在这个尺寸下信息量更大。
 * 车怎么走由那颗**沿路线移动的点**表达，够了。
 *
 * "什么时候框"是这里最绕的地方，见 [render] 里相机那段：判据是**自检**
 * （当前相机还是不是我上次框成的那个），不是"路线变没变"。
 */
internal object NaviMapOverlay {

    /** 当前叠加层挂在哪个 `AMap` 上。MapView 重建后旧引用全部失效，必须靠它认出来。 */
    private var owner: AMap? = null

    private var line: Polyline? = null

    /** 上次画的是哪份几何（**引用比较**：每次 publishShape 都是新实例，够用且零开销）。 */
    private var renderedShape: NaviShape? = null

    /** 上次成功框完之后，相机停在哪。用它来自检"当前相机还是不是我框的那个"。 */
    private var fittedCenterLat: Double? = null
    private var fittedCenterLng: Double? = null
    private var fittedZoom: Float = 0f

    private var carMarker: Marker? = null

    /** 车点图标只造一次（每帧重建 Bitmap 会一直产生垃圾）。 */
    private var carIconCache: BitmapDescriptor? = null
    private var carIconSizePx: Int = 0

    /**
     * 让叠加层与 [shape] / [car] 保持一致。**幂等**，可以每帧调。
     *
     * @param lineWidthPx 折线宽度（屏幕像素）。由调用方按密度算好传进来 ——
     *                    写死的话换台密度的机器上要么细成头发丝、要么粗成色块。
     * @param viewportWidth 地图区域的像素宽。**必须传真实的布局尺寸**，理由见下面相机那段。
     * @param viewportHeight 地图区域的像素高。
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
            line = runCatching {
                aMap.addPolyline(
                    PolylineOptions()
                        .addAll(shape.points.map { LatLng(it.latitude, it.longitude) })
                        .width(lineWidthPx)
                        .color(NAVI_LINE_COLOR)
                        .zIndex(NAVI_LINE_Z),
                )
            }.onFailure { MapLog.w("navi overlay: addPolyline failed ${it.javaClass.simpleName}") }
                .getOrNull()
            renderedShape = shape
            MapLog.i(
                "navi overlay: route drawn points=${shape.points.size} " +
                    "box=${shape.south},${shape.west}..${shape.north},${shape.east}",
            )
        }

        // ---- 相机：框住整条路线 --------------------------------------------
        //
        // **两个坑，各自对应一处设计。**
        //
        // 坑一：用哪个重载。只给 padding 的 `newLatLngBounds` 读的是 MapView
        // **内部的尺寸缓存**，而 MapView 刚从全屏页被搬进首页卡时，那份缓存还是上一个
        // 父容器的值（2755×1440）。所以必须用带显式 `viewportWidth/Height` 的重载，
        // 尺寸由 AMapCanvas 用 `onSizeChanged` 采真实布局值传进来。
        //
        // 坑二：什么时候框。第一版只在"路线变了"时框一次，而那一次恰好落在
        // MapView 尚未完成搬运的那一帧上 —— 路线画对了，镜头还停在上一处视角。
        // 真机截图 .verify/nav/Z1-home-overview.png：980 个点的线伸出卡片外，
        // 视野还停在起点街区（能看见"重庆鸡公煲"这种店铺标注，说明 zoom 根本没降下去）。
        //
        // 所以改成**自检式重框**：不去判断"这一帧准备好了没有"（判断不了，那是
        // View 内部的事），而是去量"当前相机是不是我上次框成的那个"。不是就再框一次。
        // 它同时覆盖了三种情况：首次进入、换路线、MapView 刚搬完还没就位。
        // 代价只是多读一次 cameraPosition，不动地图时这条日志不会重复出现。
        val camera = runCatching { aMap.cameraPosition }.getOrNull()
        val cameraDrifted = camera == null ||
            fittedCenterLat == null ||
            kotlin.math.abs(camera.zoom - fittedZoom) > CAMERA_ZOOM_EPSILON ||
            kotlin.math.abs(camera.target.latitude - (fittedCenterLat ?: 0.0)) > CAMERA_CENTER_EPSILON ||
            kotlin.math.abs(camera.target.longitude - (fittedCenterLng ?: 0.0)) > CAMERA_CENTER_EPSILON

        if (viewportWidth > 0 && viewportHeight > 0 && (shapeChanged || cameraDrifted)) {
            val bounds = runCatching {
                LatLngBounds(
                    LatLng(shape.south, shape.west),
                    LatLng(shape.north, shape.east),
                )
            }.getOrNull()
            if (bounds != null) {
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
                // 只有把 cameraPosition 读回来才分得清。读回来的值同时充当下次的判据，
                // 所以这条日志只会在**真的重框**时出现，不会每帧刷屏。
                val after = runCatching { aMap.cameraPosition }.getOrNull()
                fittedCenterLat = after?.target?.latitude
                fittedCenterLng = after?.target?.longitude
                fittedZoom = after?.zoom ?: 0f
                MapLog.i(
                    "navi overlay: camera fit viewport=${viewportWidth}x$viewportHeight " +
                        "-> zoom=${after?.zoom} target=${after?.target?.latitude},${after?.target?.longitude}",
                )
            }
        }

        renderCar(aMap, car, lineWidthPx)
    }

    /** 主动清空（Activity 销毁 / 探针复位时用）。 */
    fun clear() {
        dropRefs()
    }

    // ---- 内部 --------------------------------------------------------------

    private fun renderCar(aMap: AMap, car: GeoPoint?, lineWidthPx: Float) {
        if (car == null) {
            dropCar()
            return
        }
        val position = LatLng(car.latitude, car.longitude)
        val existing = carMarker
        if (existing == null) {
            val sizePx = (lineWidthPx * CAR_DOT_TO_LINE).toInt().coerceAtLeast(12)
            val icon = carIcon(sizePx)
            carMarker = runCatching {
                aMap.addMarker(
                    MarkerOptions()
                        .position(position)
                        .icon(icon)
                        // anchor 放在正中心，车点才会稳稳压在路线上而不是偏出去半个身位。
                        .anchor(0.5f, 0.5f)
                        .zIndex(NAVI_LINE_Z + 1f),
                )
            }.onFailure { MapLog.w("navi overlay: addMarker failed ${it.javaClass.simpleName}") }
                .getOrNull()
        } else {
            // **移动已有的 marker，而不是删了重加**：每次删加都是一次 View 层重建，
            // 1 Hz 的频率下会看到车点闪。
            existing.position = position
        }
    }

    private fun carIcon(sizePx: Int): BitmapDescriptor {
        carIconCache?.let { if (carIconSizePx == sizePx) return it }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val radius = sizePx / 2f

        // 白描边 + 蓝芯：和导航页上 SDK 自己画的车标是同一套视觉，
        // 用户从首页点进全屏页不会觉得"换了个东西在跟"。
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(radius, radius, radius, paint)
        paint.color = NAVI_LINE_COLOR
        canvas.drawCircle(radius, radius, radius * 0.62f, paint)

        val descriptor = BitmapDescriptorFactory.fromBitmap(bitmap)
        carIconCache = descriptor
        carIconSizePx = sizePx
        return descriptor
    }

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
    }

    private fun dropCar() {
        runCatching { carMarker?.remove() }
        carMarker = null
    }

    private fun dropRefs() {
        dropLine()
        dropCar()
        renderedShape = null
        owner = null
    }

    /** 车点直径 = 折线宽度的多少倍。折线 4 dp 时车点约 14 dp，和导航页的车标一个量级。 */
    private const val CAR_DOT_TO_LINE: Float = 3.5f

    /** 相机"算不算还停在框好的位置上"的容差。zoom 差 0.05、中心差 0.0005°（约 50 米）。 */
    private const val CAMERA_ZOOM_EPSILON: Float = 0.05f
    private const val CAMERA_CENTER_EPSILON: Double = 0.0005

    /** 路线包围盒四周留的边距（屏幕像素）。一点不留的话路线会贴着卡片边缘，看着很挤。 */
    private const val NAVI_OVERVIEW_PADDING_PX: Int = 64
}
