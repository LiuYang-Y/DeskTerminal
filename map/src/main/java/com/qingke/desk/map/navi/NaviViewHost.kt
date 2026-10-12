package com.qingke.desk.map.navi

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.amap.api.navi.AMapNaviView
import com.amap.api.navi.AMapNaviViewListener
import com.amap.api.navi.AMapNaviViewOptions
import com.amap.api.navi.AmapPageType
import com.amap.api.navi.enums.AMapNaviControlMusicVolumeMode
import com.qingke.desk.map.MapLog

/**
 * `AMapNaviView` 的**跨页面保活宿主**。
 *
 * ## 为什么需要它（2026-10-12 真机复现的严重缺陷）
 *
 * 原来 `NaviViewHost` 是一个 `@Composable`，`DisposableEffect.onDispose` 里
 * 老老实实调了 `onPause()` + `onDestroy()`。这在"离开导航页"这个动作上是对的，
 * 但它**同时**被"切屏"复用了一次 —— 用户点侧栏去首页，`DeskScreen.NAVI` 那一支
 * 从组合里消失，于是导航 View 被销毁。日志（.verify/nav/P1…P2）：
 *
 * ```
 * 19:58:56.730 I navi: view disposing     ← 切首页，只销毁了 View，引擎还在导航
 * ```
 *
 * 而引擎那边并没有停：`onStopNavi` 一次都没回调。两边从此分家，
 * 用户回到地图页看到的是"导航没了"，再点开始导航则被 SDK 拒绝（`startNavi ok=false`）。
 *
 * ## 现在的规则（与 [com.qingke.desk.map.MapViewHost] 同一套）
 *
 *   · **切屏 = 解挂，不是销毁**：[release] 只在**引用计数归零**时把 View 从父容器摘下来，
 *     `onDestroy()` 只在整个 Activity 销毁时调一次；
 *   · **回来 = 重挂 + 补一次 onResume**：attach 之后 `post { onResume() }` ——
 *     Activity 的生命周期压根没变，没人会替它重发 ON_RESUME，画面就会停在"不刷新"；
 *   · **refCount 的坑（必读）**：切屏那一帧的顺序是"新使用点 acquire（挂到新容器）→
 *     旧使用点 release"。`release` 若**无条件** `detachFromParent`，摘掉的是
 *     **刚挂好的新父容器**，View 就成了没有父容器的游离对象：不渲染、也收不到触摸
 *     （现象＝整块黑，而 `create` 计数仍是 1，所以不像"重建"）。
 *     所以 `release()` 只在 `refCount == 0` 时摘，`acquire()` 自己会先摘旧父。
 *
 * @param context 用 **applicationContext**：宿主比任何一次组合都活得久。
 */
class NaviViewHost(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    @Volatile
    private var view: AMapNaviView? = null

    @Volatile
    private var observer: LifecycleEventObserver? = null

    @Volatile
    private var destroyed = false

    /** 创建过几次。真机上它应该恒为 1；大于 1 说明有第二条创建路径漏出来了。 */
    @Volatile
    var createCount: Int = 0
        private set

    /** 被挂载/解除挂载的次数。切屏会动它，但**不该**触发创建。 */
    @Volatile
    var attachCount: Int = 0
        private set

    @Volatile
    var detachCount: Int = 0
        private set

    /**
     * 有几个使用点正持有它。切屏那一帧里新旧使用点会**同时**存在
     * （新的先 acquire、旧的后 release），所以摘除只能在计数归零时做。
     */
    private var refCount = 0

    /** 夜间样式。每次 acquire 都会重新贴上去（用户切屏回来时可能已经入夜）。 */
    @Volatile
    var night: Boolean = false

    /**
     * 用户从导航界面点"返回/退出"。[DeskRoot] 每次重组刷新它 ——
     * 存成 val 的话会一直调用第一帧捕获的那个 lambda。
     */
    @Volatile
    var onExitRequest: () -> Unit = {}

    /** 当前 View；没创建过返回 null。HomeScreen 之类想读一下状态时用。 */
    fun current(): AMapNaviView? = view

    /** 是否曾经创建过（判断"有没有重建"用）。 */
    val isCreated: Boolean get() = view != null

    /**
     * 取 `AMapNaviView`。第一次调用时创建并 `onCreate`，之后只返回同一个实例。
     *
     * @param activity SDK 需要它做权限与生命周期联动；拿不到就走单参数重载，
     *                 宁可少一层联动也不能不初始化。
     */
    @Synchronized
    fun acquire(activity: Activity?): AMapNaviView? {
        if (destroyed) {
            MapLog.w("navi host: acquire after destroy -> refused")
            return null
        }

        val existing = view
        if (existing != null) {
            detachFromParent(existing)
            applyNight(existing)
            attachCount++
            refCount++
            MapLog.i(
                "navi host: reuse existing AMapNaviView " +
                    "(create=$createCount attach=$attachCount detach=$detachCount refs=$refCount)",
            )
            resumeAfterAttach(existing)
            return existing
        }

        val created = runCatching { AMapNaviView(context, buildOptions()) }
            .onFailure { MapLog.e("navi host: AMapNaviView create failed", it) }
            .getOrElse { return null }

        created.setAMapNaviViewListener(listener)
        runCatching {
            if (activity != null) {
                created.onCreate(null, activity, AmapPageType.NAVI)
            } else {
                created.onCreate(null)
            }
        }.onFailure { MapLog.e("navi host: onCreate failed", it) }

        createCount++
        attachCount++
        refCount++
        view = created
        observeLifecycle()
        MapLog.i(
            "navi host: AMapNaviView created (create=$createCount attach=$attachCount refs=$refCount)",
        )
        return created
    }

    /**
     * 解除挂载，**不销毁**。由 [NaviCanvas] 在离开组合时调。
     *
     * 不在这里调 `onPause()`：切屏之后导航还要继续跑，而"暂停"这件事
     * 与 Activity 的生命周期同拍（见 [observeLifecycle]）。切屏只是"这块画面
     * 暂时不在屏幕上"，不是"导航暂停"。
     */
    @Synchronized
    fun release() {
        val target = view ?: return
        refCount = (refCount - 1).coerceAtLeast(0)
        if (refCount == 0) {
            detachFromParent(target)
            detachCount++
        }
        MapLog.i(
            "navi host: released (create=$createCount attach=$attachCount detach=$detachCount refs=$refCount)",
        )
    }

    /**
     * 真正销毁。只在整个 Activity 销毁时调一次；重复调用幂等。
     *
     * 两处 `onDestroy` 会让渲染线程二次释放直接崩 —— 所以加了闸门，
     * 并把计数留在日志里（真机上 `create=1 destroy=1` 才是健康的）。
     */
    @Synchronized
    fun destroy() {
        if (destroyed) return
        destroyed = true
        val target = view
        view = null
        observer?.let { lifecycleOwner.lifecycle.removeObserver(it) }
        observer = null
        if (target == null) {
            MapLog.i("navi host: destroy (never created)")
            return
        }
        detachFromParent(target)
        runCatching { target.onPause() }
        runCatching { target.onDestroy() }
            .onFailure { MapLog.e("navi host: onDestroy failed", it) }
        MapLog.i(
            "navi host: destroy (create=$createCount attach=$attachCount detach=$detachCount)",
        )
    }

    private fun buildOptions(): AMapNaviViewOptions = AMapNaviViewOptions().apply {
        // 夜间模式交给我们（我们自己的昼夜判断，天气 API 的日出日落会喂给 Daylight）——
        // 两套判断同时开着会在同一时刻给出不同结论，画面自己闪一下。
        setAutoNaviViewNightMode(false)
        setNaviNight(night)
        // 路况条与自动缩放都开着：桌面终端是"抬眼看一眼"的场景，
        // 车道级细节不如"还有多远、堵不堵"重要，但这两个开着才有。
        setTrafficBarEnabled(true)
        setAutoChangeZoom(true)
        setLayoutVisible(true)
        // 语音助手：启用 + 播报时压低音乐（通话中不打断）。
        // 音量策略两处都设（这里 + NaviSession），漏一处可能整段沉默。
        voiceAssistSettings = AMapNaviViewOptions.VoiceAssistSettings(
            true,
            false,
            AMapNaviControlMusicVolumeMode.MUSIC_VOLUME_MODE_DEPRESS,
        )
    }

    private fun applyNight(target: AMapNaviView) {
        runCatching { target.viewOptions?.setNaviNight(night) }
    }

    /**
     * 重挂后叫醒画面。`post` 而不是直接调：`acquire()` 返回时 View 还没被
     * `AndroidView` 加到容器上，这时候 `onResume()` 打在一个游离的 View 上，
     * SDK 那边不会真的重启渲染循环。post 的 runnable 会排在 attach 之后执行。
     */
    private fun resumeAfterAttach(target: AMapNaviView) {
        target.post {
            runCatching { target.onResume() }
                .onFailure { MapLog.w("navi host: onResume after attach failed: ${it.javaClass.simpleName}") }
            // 诊断：重挂后到底有没有父容器、容器多大。父容器为 null 就是黑屏的直接证据。
            val parent = target.parent as? View
            val size = if (target.width > 0 && target.height > 0) {
                "${target.width}x${target.height}"
            } else {
                "unsized"
            }
            MapLog.i(
                "navi host: resumed after attach (refs=$refCount size=$size " +
                    "parent=${parent?.javaClass?.simpleName ?: "NONE"})",
            )
        }
    }

    /**
     * 生命周期只转发一次。**注意它绑的是 Activity，不是"哪一屏"** ——
     * 切屏不会触发它，所以导航不会因为切屏被暂停；息屏、切到手机投屏会。
     */
    private fun observeLifecycle() {
        if (observer != null) return
        val created = LifecycleEventObserver { _, event ->
            val target = view ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    runCatching { target.onResume() }
                    MapLog.i("navi host: naviView.onResume (activity)")
                }

                Lifecycle.Event.ON_PAUSE -> {
                    runCatching { target.onPause() }
                    MapLog.i("navi host: naviView.onPause (activity)")
                }

                else -> Unit
            }
        }
        observer = created
        lifecycleOwner.lifecycle.addObserver(created)
    }

    private fun detachFromParent(target: AMapNaviView) {
        (target.parent as? ViewGroup)?.removeView(target)
    }

    private val listener = object : NaviViewListenerAdapter() {
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
    }
}

/**
 * 把保活的 `AMapNaviView` 挂进 Compose 屏。
 *
 * **它自己不负责创建/销毁**，只做两件事：进组合时 [NaviViewHost.acquire]（重挂）、
 * 出组合时 [NaviViewHost.release]（解挂）。所以切屏不会毁掉导航画面。
 *
 * @param onReady View 已挂好。**只回调一次**（每次进组合一次）。`startNavi()` 必须在
 *                `onCreate()` 之后调，靠"延迟几百毫秒再 start"是撞运气，这里用顺序保证。
 */
@Composable
fun NaviCanvas(
    host: NaviViewHost,
    night: Boolean,
    onExitRequest: () -> Unit,
    onReady: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalActivityOrNull()
    // 用 rememberUpdatedState 兜住回调：宿主活得比任何一帧都长，
    // 直接存 lambda 会把第一帧捕获的那份（连同它引用的旧 state）一直用下去。
    val exitState = rememberUpdatedState(onExitRequest)
    val readyState = rememberUpdatedState(onReady)

    host.night = night
    host.onExitRequest = { exitState.value() }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // 拿不到 View（创建失败）时退回一个空 View，而不是让 AndroidView 抛异常 ——
            // 导航页黑着但可控，比进程崩掉强。
            host.acquire(activity) ?: View(ctx)
        },
        onRelease = { host.release() },
    )

    // 顺序：组合（factory 跑完）→ 本 effect。所以这里调用时 View 一定已经建好。
    LaunchedEffect(Unit) {
        readyState.value()
    }
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
private fun LocalActivityOrNull(): Activity? {
    val context = LocalContext.current
    return remember(context) { context.findActivityOrNull() }
}

/**
 * `AMapNaviViewListener` 有 23 个抽象方法（javap 核实），合包里**没有**对应的 Simple 适配类
 * （`SimpleNaviListener` 只覆盖导航数据回调，不覆盖 View 回调）。
 * 所以这里手写一个空实现的适配器，只让真正关心的几个钩子有行为。
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
