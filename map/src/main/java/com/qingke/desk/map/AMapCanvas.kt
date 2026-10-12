package com.qingke.desk.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.qingke.desk.core.data.GeoPoint
import com.qingke.desk.core.data.ShellSettings
import com.qingke.desk.map.navi.NaviShape

/**
 * 地图样式。三个都是高德 SDK 自带的 mapType，不涉及自定义样式文件
 * （自定义样式要往 assets 里塞 json，且它属于"地图数据"的再加工，
 * 官方服务协议第 3.5 条对脱离 SDK 使用地图数据有限制，能不用就不用）。
 */
enum class AMapStyle(val amapType: Int, val label: String) {
    /** 标准矢量图。 */
    NORMAL(AMap.MAP_TYPE_NORMAL, "标准"),

    /** 夜间矢量图 —— 深色底，和本项目整体暗色界面一致。 */
    NIGHT(AMap.MAP_TYPE_NIGHT, "夜间"),

    /** 卫星影像。 */
    SATELLITE(AMap.MAP_TYPE_SATELLITE, "卫星"),
}

/** 街道级缩放。16 是街区、17 是街道、19 以上就到建筑物了。 */
const val MAP_ZOOM_STREET: Float = 17f

/**
 * 预览卡的渲染帧率。
 *
 * 参考高德车机 SDK 的 `RenderFpsConstants`（官方常量）：NAVI=10 / NORMAL=15 /
 * ANIMATION=30 / GESTURE=40。预览卡没有动画、也不接受手势，15 fps 足够，
 * 而且这台设备是 24 小时常亮的，帧率直接等于功耗。
 */
const val MAP_PREVIEW_FPS: Int = 15

/** 全屏地图页的渲染帧率（用户可能在上面拖动、以后还要做导航）。 */
const val MAP_FULL_FPS: Int = 30

/**
 * 把高德的 `MapView` 包成 Compose 组件。
 *
 * 三件必须做对的事：
 *
 * 1. **生命周期转发**。`MapView` 是传统 View，它靠 `onCreate/onResume/onPause/onDestroy`
 *    管理自己的渲染线程与定位客户端。传了 [host] 时由 [MapViewHost] 统一转发一次；
 *    没传（预览页等独立场景）才在这里自己注册一个观察者。
 *
 * 2. **必须在 [AMapPrivacy] 合规通过之后再构造**。所以本组件只应该从 [MapPane]
 *    里被调起，它保证了"先弹隐私政策、同意后才挂地图"。
 *
 * 3. **不要假设自己有多大**。地图可以铺满一块任意尺寸的区域，
 *    这里只吃外面传进来的 `modifier`，不写死宽高 —— 首页怎么摆放是 :shell 的事。
 *
 * @param host 单实例宿主。传了就复用同一个 `MapView`（首页卡 ↔ 全屏页来回切不重建），
 *             并且 `onDispose` 只解挂、不销毁。不传则退回"每次出现都新建"的旧行为。
 * @param preview 预览模式：关掉全部手势、吞掉 View 层触摸、不显示罗盘。
 *                首页卡片用，避免和横滑翻页抢手势（详见 [com.qingke.desk.shell.HomeScreen]）。
 * @param renderEnabled false 时调 `AMap.setDisableRender(true)` 直接停渲染 ——
 *                      MapView 与 camera 状态都留着，但一帧都不画。
 *                      翻到第二页、切到别的屏时用，是最有效的省电手段。
 * @param renderFps 渲染帧率上限（[MAP_PREVIEW_FPS] / [MAP_FULL_FPS]）。
 * @param initialCenter 初始中心点。null 时先用 [ShellSettings.lastLocation]（上次定位），
 *                      再没有就用 SDK 默认视角 —— 拿到定位之前不该乱猜坐标，
 *                      但"全国视角"对街道级预览卡来说同样不合适。
 */
@Composable
fun AMapCanvas(
    modifier: Modifier = Modifier,
    host: MapViewHost? = null,
    style: AMapStyle = AMapStyle.NORMAL,
    showMyLocation: Boolean = true,
    initialCenter: LatLng? = null,
    initialZoom: Float = MAP_ZOOM_STREET,
    preview: Boolean = false,
    renderEnabled: Boolean = true,
    renderFps: Int = MAP_FULL_FPS,
    /**
     * 导航总览：整条路线 + 车点（首页地图卡在导航中显示的就是它）。
     *
     * 传 null 会**清掉**已有叠加层 —— 这一点很关键，退出导航之后必须有谁调一次
     * 带 null 的渲染，否则那条不作数的路线会一直挂在地图上（见 [NaviMapOverlay]）。
     *
     * @param naviShape 整条路线的几何。含包围盒，镜头按它框一次。
     * @param naviCar 车当前的位置，沿路线移动的那颗点。
     */
    naviShape: NaviShape? = null,
    naviCar: GeoPoint? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var locationGranted by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        locationGranted = result.values.any { it }
        MapLog.i("location permission -> granted=$locationGranted")
    }

    LaunchedEffect(showMyLocation, locationGranted) {
        if (showMyLocation && !locationGranted) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    // ---- MapView 从哪来 ----------------------------------------------------
    //
    // 有 host 就走"单实例复用"：acquire() 只在第一次真正 new，之后返回同一个实例
    // （acquire 内部会先把它从旧父容器摘下来，否则 attach 时抛 already has a parent）。
    // 没有 host 时保持旧行为：remember 一次、离开组合时销毁 —— 预览页和探针页走这条。
    val ownedView = if (host == null) {
        remember {
            MapView(context).apply {
                onCreate(Bundle())
                MapLog.i("MapView created (standalone)")
            }
        }
    } else {
        null
    }
    // **只在首次组合（或 host 换人）时向宿主取实例。**
    //
    // 不能直接写成 `host?.acquire()`：它会跟着每一次重组执行，而 acquire 内部会先把
    // view 从旧父容器摘下来（否则 attach 时抛 already has a parent）—— 于是每次重组
    // 都"摘一次、再挂回去"，日志里表现为 attach 一路涨到 2、4、8…（真机实测过），
    // 画面就是闪。放进 remember 之后，acquire 与 release 才成对。
    val mapView: MapView? = remember(host) { host?.acquire() } ?: ownedView
    if (mapView == null) {
        AMapPlaceholder(modifier = modifier, text = "地图不可用")
        return
    }

    if (ownedView != null) {
        DisposableEffect(lifecycleOwner, ownedView) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> ownedView.onResume()
                    Lifecycle.Event.ON_PAUSE -> ownedView.onPause()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                MapLog.i("dispose -> standalone mapView.onDestroy")
                ownedView.onDestroy()
            }
        }
    } else {
        // 单实例：离开组合**只解挂**，销毁交给 MapViewHost.destroy()（Activity 级）。
        DisposableEffect(host) {
            onDispose { host?.release() }
        }
    }

    // 地图区域**真实的布局尺寸**。导航总览框镜头要用它，而不是 MapView 内部的尺寸缓存
    // —— 那个缓存刚从别的父容器搬过来时还是旧值（见 NaviMapOverlay.render 的相机那段）。
    var mapViewport by remember { mutableStateOf(IntSize.Zero) }

    AndroidView(
        factory = {
            MapLog.i(
                "MapView attached, style=${style.name} preview=$preview " +
                    "(host=${host != null})",
            )
            mapView.map.setOnMapLoadedListener { MapLog.i("map loaded (tiles on screen)") }
            // 预览卡把 MapView 塞进一层"拦下来但不消费"的容器里，见 [TouchTransparentLayout]。
            // 只关 uiSettings 是不够的：MapView 就算不解释手势，它的 onTouchEvent 仍会返回
            // true（消费），而 Compose 的 Main pass 是"子先父后"—— 子消费掉之后
            // Pager 就再也收不到这一串事件，横滑翻页在地图卡上直接失效。
            if (preview) {
                TouchTransparentLayout(context).apply {
                    addView(
                        mapView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        ),
                    )
                }
            } else {
                mapView
            }
        },
        modifier = modifier.onSizeChanged { mapViewport = it },
        update = {
            // 用捕获的 mapView，不用 update 回调给的 view ——
            // 预览模式下那一层是包装容器（FrameLayout），没有 .map。
            mapView.map.apply {
                uiSettings.isZoomGesturesEnabled = !preview
                uiSettings.isScrollGesturesEnabled = !preview
                uiSettings.isRotateGesturesEnabled = !preview
                uiSettings.isTiltGesturesEnabled = !preview
                // 屏幕控件一律不显示：预览卡上它们挡地方，全屏页上靠手势。
                // 罗盘也关 —— 旋转手势关掉之后它根本不会出现，留着只是多一个可点 view。
                uiSettings.isZoomControlsEnabled = false
                uiSettings.isScaleControlsEnabled = false
                uiSettings.isCompassEnabled = false
                uiSettings.isMyLocationButtonEnabled = false
                mapType = style.amapType
            }
        },
    )

    val aMap: AMap = mapView.map

    LaunchedEffect(aMap, style) {
        aMap.mapType = style.amapType
    }

    LaunchedEffect(aMap, showMyLocation, locationGranted) {
        if (showMyLocation && locationGranted) {
            // 顺序不能反：先给 style 再开开关，否则 SDK 会用一套默认样式画一次。
            aMap.myLocationStyle = MyLocationStyle()
                // SHOW（0）= 只画蓝点、不移动地图。视角由我们自己搬（见下面首次定位），
                // 不用 LOCATE/FOLLOW：那是"持续跟随"，常驻终端不需要，还费电。
                .myLocationType(MyLocationStyle.LOCATION_TYPE_SHOW)
                .interval(2_000L)
                .showMyLocation(true)
                .strokeColor(Color.argb(60, 0, 122, 255))
                .radiusFillColor(Color.argb(28, 0, 122, 255))
            aMap.isMyLocationEnabled = true
            MapLog.i("my location enabled")
        } else {
            aMap.isMyLocationEnabled = false
            MapLog.i("my location disabled (show=$showMyLocation granted=$locationGranted)")
        }
    }

    // ---- 初始视角 ---------------------------------------------------------
    //
    // 优先用调用方给的坐标；没有就用**上次定位到的位置**（存在 ShellSettings 里）；
    // 再没有就什么都不做（SDK 默认视角）。街道级的预览卡上先闪一下全国图很突兀。
    //
    // 2026-10-12：整套"摆镜头"的动作**每个 MapView 只做一次**（状态记在 host 上，
    // 见 MapViewHost.isCameraSeeded）。否则从首页点进全屏页会再种一次视角，
    // 用户在全屏页拖过的位置被拉回去 —— 那等于"重挂没丢、进屏就覆盖"。
    val fallbackCenter = remember(context) {
        ShellSettings.lastLocation(context)?.let { LatLng(it.latitude, it.longitude) }
    }
    LaunchedEffect(aMap, host, initialCenter, fallbackCenter, initialZoom) {
        if (host?.isCameraSeeded == true) {
            MapLog.i("initial camera skipped (host already seeded)")
            return@LaunchedEffect
        }
        val center = initialCenter ?: fallbackCenter
        if (center != null) {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(center, initialZoom))
            host?.markCameraSeeded()
            MapLog.i("initial camera -> ${center.latitude},${center.longitude} z=$initialZoom")
        } else {
            MapLog.i("initial camera -> sdk default (no known location yet)")
        }
    }

    // ---- 首次定位后居中一次 ------------------------------------------------
    //
    // 只搬一次：设备固定在桌上，之后持续跟随没有意义（还费电）。
    // 拿到第一个点就顺便存下来，下次冷启动有初始视角可用。
    // "搬过一次"同样记在 host 上：进全屏页不该把用户拖动的视角拉回来。
    LaunchedEffect(aMap, host, showMyLocation, locationGranted) {
        if (!showMyLocation || !locationGranted) return@LaunchedEffect
        var centered = host?.isCameraSeeded == true
        aMap.setOnMyLocationChangeListener { location ->
            if (location == null) return@setOnMyLocationChangeListener
            if (!centered) {
                centered = true
                host?.markCameraSeeded()
                val target = LatLng(location.latitude, location.longitude)
                aMap.animateCamera(CameraUpdateFactory.newLatLngZoom(target, initialZoom))
                MapLog.i("first fix -> centered ${target.latitude},${target.longitude} z=$initialZoom")
            }
            ShellSettings.setLastLocation(
                context.applicationContext,
                GeoPoint(location.latitude, location.longitude),
            )
        }
    }

    // ---- 渲染开关与帧率 ----------------------------------------------------
    //
    // setRenderFps / setDisableRender 都是 AMap 上的公开方法（javap 核实过 11.3.100 合包），
    // 但它们的失败方式是崩溃还是静默没验证过，所以一律 runCatching 兜住：
    // 就算这个版本的 so 不支持，也只是"省电策略没生效"，不能把地图整没了。
    LaunchedEffect(aMap, renderEnabled, renderFps) {
        runCatching { aMap.setRenderFps(renderFps) }
            .onFailure { MapLog.w("setRenderFps($renderFps) failed: ${it.javaClass.simpleName}") }
        runCatching { aMap.setDisableRender(!renderEnabled) }
            .onFailure {
                MapLog.w("setDisableRender(${!renderEnabled}) failed: ${it.javaClass.simpleName}")
            }
        MapLog.i("render enabled=$renderEnabled fps=$renderFps")
    }

    // ---- 导航总览叠加层 ----------------------------------------------------
    //
    // 首页地图卡在导航中要显示"整条路线 + 沿路线移动的车点"。
    //
    // 顺序上这是本组件做的最后一件事：它要盖过上面那次初始视角（初始视角每个
    // MapView 只种一次，导航中早就种过了，所以两者不会互相拉拽）。
    //
    // 折线宽度按密度换算：SDK 的 `PolylineOptions.width()` 是**屏幕像素**，
    // 写死像素值换台机器要么细成头发丝、要么粗成色块。
    val overviewLineWidthPx = NAVI_OVERVIEW_LINE_WIDTH_DP * LocalDensity.current.density
    LaunchedEffect(aMap, naviShape, naviCar, renderEnabled, overviewLineWidthPx, mapViewport) {
        // 停渲染时不铺（翻到第 2 页）：一帧都不画的页面上没必要维护覆盖物。
        if (!renderEnabled) return@LaunchedEffect
        NaviMapOverlay.render(
            aMap = aMap,
            shape = naviShape,
            car = naviCar,
            lineWidthPx = overviewLineWidthPx,
            viewportWidth = mapViewport.width,
            viewportHeight = mapViewport.height,
            // 退出导航后视角回到"我"这里。拿不到上次定位就传 null，
            // 那时叠加层会**不动相机** —— 猜个坐标把人扔到别处比停在原地更糟。
            selfPoint = fallbackCenter?.let { GeoPoint(it.latitude, it.longitude) },
        )
    }
}

/**
 * 把触摸**拦下来、并且不消费**的一层容器。只包在预览模式的地图上。
 *
 * 为什么需要（2026-10-12，方向是推出来的、结论是真机验证的）：
 * Compose 的指针事件在 Main pass 里是"**子节点先于父节点**"处理的。
 * 首页地图卡是 Pager 的子节点，MapView 又是地图卡这个 Compose 节点的子 View ——
 * 只要 MapView 在自己的 `onTouchEvent` 里返回 true（它一定会，FrameLayout + 内部渲染 View），
 * 事件就被子节点消费掉，**Pager 再也收不到这一串事件**，横滑翻页在地图卡上直接失效。
 *
 * 所以"关掉 uiSettings 手势"解决不了问题：那只是让地图不做动作，
 * 它照样把事件吃掉。必须让 View 侧明确**不消费**：
 *
 *  · `onInterceptTouchEvent` 返回 true —— 事件不再下发给地图内部的渲染 View；
 *  · `onTouchEvent` 返回 false —— 自己也不消费。
 *
 * 结果：`dispatchTouchEvent` 返回 false → Compose 的 AndroidViewHolder 不消费
 * → 事件继续在 Compose 树里往上走到 Pager，横滑翻页正常；
 * 而"点整卡进全屏页"由地图卡外层那个 Compose `clickable` 处理（它在更上层，
 * 先拿到事件），两条路互不干扰。
 */
private class TouchTransparentLayout(context: Context) : android.widget.FrameLayout(context) {

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean = true

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = false
}

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}
