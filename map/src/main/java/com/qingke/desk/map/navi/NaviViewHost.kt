package com.qingke.desk.map.navi

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.enums.AMapNaviControlMusicVolumeMode
import com.qingke.desk.map.MapLog

/**
 * 把导航界面（`AMapNaviView`）**内嵌**进我们的 Compose 屏。
 *
 * 为什么不用 SDK 自带的 `AmapRouteActivity`（那个会自己起一个全屏 Activity）：
 *   壳层的一切 —— 侧栏、上滑逃生手势、常亮、防烧屏、沉浸式与挖孔避让 ——
 *   都活在 `MainActivity` 的窗口配置与 Compose 树里。换成别的 Activity，这些**全部失效**，
 *   要为它重做一遍；而内嵌只是把同一块画面放进我们自己的布局。
 *
 * 生命周期必须照 SDK 的节拍走（javap 核实，这四个方法都是 `final`，不能被覆盖，只能被调用）：
 *   `onCreate(Bundle, Activity, AmapPageType)` → `onResume()` → `onPause()` → `onDestroy()`
 * 顺序错了的典型现象是黑屏或导航 View 不刷新。所以在 [DisposableEffect] 里成对出现。
 *
 * @param night 夜间样式。**用我们自己的昼夜判断**（[com.qingke.desk.core.platform.Daylight]，
 *              天气 API 的日出日落会注入进来），因此把 SDK 的自动夜间模式关掉 ——
 *              否则同一时刻两套判断可能给出不同结论，画面会自己闪一下。
 * @param onExitRequest 用户从导航界面点"返回/退出"时回调。**由调用方决定怎么收尾**
 *              （切屏 + [NaviEngine.stopNavi]），本文件不擅自 finish 任何东西。
 */
@Composable
fun NaviViewHost(
    modifier: Modifier = Modifier,
    night: Boolean = false,
    onExitRequest: () -> Unit = {},
    /**
     * 导航 View 已经建好、可以开始导航了。**只回调一次**。
     *
     * 为什么需要它：`startNavi()` 必须在 `AMapNaviView.onCreate()` 之后调用，
     * 否则 SDK 拿不到 View 就把导航状态丢在地上（现象是画面停在路线预览）。
     * 靠"延迟几百毫秒再 start"来对齐是撞运气；这里直接用**顺序**保证。
     */
    onViewReady: () -> Unit = {},
) {
    val context = LocalContextCompat()
    // 普通引用而不是 Compose state：factory 在**组合期间**执行，
    // 在那个时机写 state 会触发"组合中改状态"这类问题。
    // 顺序上 factory 一定先于 effect，所以下面用 LaunchedEffect(Unit) 读它就够了。
    val ref = remember { NaviViewRef() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val options = AMapNaviViewOptions().apply {
                // 夜间模式交给我们（见 @param night 的说明）。
                setAutoNaviViewNightMode(false)
                setNaviNight(night)
                // 路况条与自动缩放都开着：桌面终端是"抬眼看一眼"的场景，
                // 车道级细节不如"还有多远、堵不堵"重要，但这两个开着才有。
                setTrafficBarEnabled(true)
                setAutoChangeZoom(true)
                setLayoutVisible(true)
                // 语音助手：启用 + 播报时压低音乐（通话中不打断）。
                // 音量策略两处都设（这里 + NaviEngine），漏一处可能整段沉默。
                voiceAssistSettings = AMapNaviViewOptions.VoiceAssistSettings(
                    true,
                    false,
                    AMapNaviControlMusicVolumeMode.MUSIC_VOLUME_MODE_DEPRESS,
                )
            }

            AMapNaviView(ctx, options).apply {
                setAMapNaviViewListener(object : NaviViewListenerAdapter() {
                    override fun onNaviCancel() {
                        MapLog.i("navi: view onNaviCancel -> exit")
                        onExitRequest()
                    }

                    override fun onNaviBackClick(): Boolean {
                        MapLog.i("navi: view onNaviBackClick -> exit")
                        onExitRequest()
                        // 返回 true = "我已经处理了，SDK 不要再自己收尾"，
                        // 否则 SDK 可能同时走它自己的退出流程，出现双重收尾。
                        return true
                    }

                    override fun onAMapNaviViewExit() {
                        MapLog.i("navi: view onAMapNaviViewExit")
                        onExitRequest()
                    }

                    override fun onNaviViewLoaded() {
                        MapLog.i("navi: view loaded")
                    }
                })

                // 有 Activity 就走带 Activity 的重载（SDK 需要它做权限与生命周期联动），
                // 拿不到就退回单参数版本，宁可少一层联动也不能不初始化。
                if (context != null) {
                    onCreate(null, context, AmapPageType.NAVI)
                } else {
                    onCreate(null)
                }
                ref.view = this
            }
        },
    )

    // 顺序：组合（factory 跑完）→ 本 effect。所以这里两个调用都在 View 建好之后。
    LaunchedEffect(Unit) {
        val v = ref.view ?: return@LaunchedEffect
        runCatching { v.onResume() }
        MapLog.i("navi: view ready")
        onViewReady()
    }

    DisposableEffect(Unit) {
        onDispose {
            val v = ref.view ?: return@onDispose
            MapLog.i("navi: view disposing")
            runCatching { v.onPause() }
            runCatching { v.onDestroy() }
            ref.view = null
        }
    }
}

/** 一个只用来跨回调传递 View 引用的普通容器（不是 Compose state，理由见上）。 */
private class NaviViewRef {
    var view: AMapNaviView? = null
}

/** 从 Compose 的 Context 里挖出宿主 Activity；拿不到返回 null（不抛）。 */
private fun Context.findActivityOrNull(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@Composable
private fun LocalContextCompat(): Activity? {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) { context.findActivityOrNull() }
}

/**
 * `AMapNaviViewListener` 有 18 个抽象方法（javap 核实），合包里**没有**对应的 Simple 适配类
 * （`SimpleNaviListener` 只覆盖导航数据回调，不覆盖 View 回调）。
 * 所以这里手写一个空实现的适配器，只让真正关心的三个钩子有行为。
 *
 * 关键字面量在真机上是有用的证据（"用户到底点了哪个返回"），所以每个都落一行日志，
 * 而不是干脆留空 —— 排查"点了返回没反应"时，这行日志决定是 SDK 没回调还是我们没处理。
 */
private open class NaviViewListenerAdapter : AMapNaviViewListener {

    override fun onNaviSetting() = log("onNaviSetting")

    override fun onNaviCancel() = log("onNaviCancel")

    override fun onNaviBackClick(): Boolean {
        log("onNaviBackClick")
        return false
    }

    override fun onNaviMapMode(mode: Int) = log("onNaviMapMode $mode")

    override fun onNaviTurnClick() = log("onNaviTurnClick")

    override fun onNextRoadClick() = log("onNextRoadClick")

    override fun onScanViewButtonClick() = log("onScanViewButtonClick")

    override fun onLockMap(locked: Boolean) = log("onLockMap $locked")

    override fun onNaviViewLoaded() = log("onNaviViewLoaded")

    override fun onMapTypeChanged(type: Int) = log("onMapTypeChanged $type")

    override fun onNaviViewShowMode(mode: Int) = log("onNaviViewShowMode $mode")

    override fun onStopSpeaking() = log("onStopSpeaking")

    override fun onViewTypeChanged(type: AmapPageType?) = log("onViewTypeChanged $type")

    override fun onAMapNaviViewExit() = log("onAMapNaviViewExit")

    override fun onStrategyChanged(strategy: Int) = log("onStrategyChanged $strategy")

    override fun onBroadcastModeChanged(mode: Int) = log("onBroadcastModeChanged $mode")

    override fun onDayAndNightModeChanged(mode: Int) = log("onDayAndNightModeChanged $mode")

    override fun onScaleAutoChanged(auto: Boolean) = log("onScaleAutoChanged $auto")

    override fun onListenToVoiceDuringCallChanged(enabled: Boolean) =
        log("onListenToVoiceDuringCallChanged $enabled")

    override fun onControlMusicVolumeModeChanged(mode: Int) = log("onControlMusicVolumeModeChanged $mode")

    override fun onEagleChanged(on: Boolean) = log("onEagleChanged $on")

    override fun onNaviRouteHighlightChange(routeId: Long, type: Int) =
        log("onNaviRouteHighlightChange $routeId/$type")

    private fun log(what: String) {
        MapLog.i("navi: view $what")
    }
}
