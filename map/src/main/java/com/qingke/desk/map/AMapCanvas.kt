package com.qingke.desk.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalContext
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

/**
 * 把高德的 `MapView` 包成 Compose 组件。
 *
 * 三件必须做对的事：
 *
 * 1. **生命周期转发**。`MapView` 是传统 View，它靠 `onCreate/onResume/onPause/onDestroy`
 *    管理自己的渲染线程与定位客户端。Compose 里没人替你调，必须用
 *    `LifecycleEventObserver` 手动转发 —— 不转发的话现象是"息屏再亮回来一片黑"
 *    或者定位在后台一直不释放。
 *    `onDestroy` 只放在 `onDispose` 一处：Activity 真正销毁时组合也会被释放，
 *    两处都调会重复销毁（渲染线程二次释放直接崩）。
 *
 * 2. **必须在 [AMapPrivacy] 合规通过之后再构造**。所以本组件只应该从 [MapPane]
 *    里被调起，它保证了"先弹隐私政策、同意后才挂地图"。
 *
 * 3. **不要假设自己有多大**。地图可以铺满一块任意尺寸的区域，
 *    这里只吃外面传进来的 `modifier`，不写死宽高 —— 首页怎么摆放是 :shell 的事。
 *
 * @param initialCenter 初始中心点。传 null 就用 SDK 默认视角（整国），
 *                      拿到定位结果之前不应该乱猜一个坐标。
 */
@Composable
fun AMapCanvas(
    modifier: Modifier = Modifier,
    style: AMapStyle = AMapStyle.NORMAL,
    showMyLocation: Boolean = true,
    initialCenter: LatLng? = null,
    initialZoom: Float = 16f,
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

    // onCreate 必须在 getMap() 之前调；放 remember 里只跑一次。
    // 传空 Bundle 等于"没有需要恢复的状态"——本组件的每次出现都重新回到初始视角，
    // 这对常驻桌面终端是想要的行为（不做跨进程状态恢复）。
    val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    MapLog.i("mapView.onResume")
                }

                Lifecycle.Event.ON_PAUSE -> {
                    mapView.onPause()
                    MapLog.i("mapView.onPause")
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            MapLog.i("dispose -> mapView.onDestroy")
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = {
            MapLog.i("MapView attached, style=${style.name}")
            mapView.map.setOnMapLoadedListener { MapLog.i("map loaded (tiles on screen)") }
            mapView
        },
        modifier = modifier,
        update = { view ->
            view.map.apply {
                // 手势缩放/拖动/旋转全部打开 —— 这块区域在手臂距离外操作，
                // 双指缩放是这台设备上最自然的交互。
                uiSettings.isZoomGesturesEnabled = true
                uiSettings.isScrollGesturesEnabled = true
                uiSettings.isRotateGesturesEnabled = true
                uiSettings.isTiltGesturesEnabled = true
                // 屏幕上的控件：缩放在窄面板里会挡住本来就不多的地图面积，
                // 默认关掉，靠手势；罗盘保留（它很小且只在旋转后出现）。
                uiSettings.isZoomControlsEnabled = false
                uiSettings.isScaleControlsEnabled = false
                uiSettings.isCompassEnabled = true
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

    LaunchedEffect(aMap, initialCenter, initialZoom) {
        initialCenter?.let {
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(it, initialZoom))
        }
    }
}

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
}
