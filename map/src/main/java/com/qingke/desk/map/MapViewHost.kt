package com.qingke.desk.map

import android.os.Bundle
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer

/**
 * 跨尺寸重挂后，pause 与 resume 之间要隔多久（毫秒）。取值见 [MapViewHost.resumeAfterAttach]。
 */
private const val RESIZE_RESUME_DELAY_MS = 300L

/**
 * 一个跨页面复用的 `MapView` 宿主。
 *
 * 为什么需要它（2026-10-12）：首页改成两页横滑之后，第一页是一张大地图卡，
 * 全屏地图页是同一块地图的放大版。如果两边各自 `remember { MapView(context) }`，那么
 *   · 翻到第 2 页时第一页被 dispose → `mapView.onDestroy()` → 翻回来重新 `onCreate`，
 *     整块地图重建一遍（瓦片重下、视角回到初始点）；
 *   · 点地图卡进全屏页时，旧的销毁、新的再建一次。
 * 这台设备是 24 小时常亮的，"每次看一眼地图都要重建一次"既慢又费电。
 *
 * 所以把 `MapView` 的持有者从"某个组合内"提升到**Activity 作用域**（由 :shell 的
 * [com.qingke.desk.shell.DeskRoot] 用 remember 持有、在整棵树销毁时 [destroy]）：
 *
 *   · [acquire] 第一次调用时才 `new MapView` 并 `onCreate`，之后只返回同一个实例；
 *   · `AndroidView` 的 `onDispose` 只解除挂载（见 [AMapCanvas]），**不调 onDestroy**；
 *   · `onResume / onPause` 由本类统一转发一次（不再每个使用点各注册一个观察者，
 *     否则同一实例会被 onResume 两次）；
 *   · [destroy] 只在 Activity 真销毁时调一次。
 *
 * 重挂（detach → attach 到另一个尺寸的容器）是这套方案的已知代价：
 * 首页卡 409×313 ↔ 全屏页 666×361 会触发一次重新布局，
 * 但地图引擎状态（camera、图层、已加载瓦片）不丢 —— 这正是"不重建"的定义。
 */
class MapViewHost(
    private val context: android.content.Context,
    private val lifecycleOwner: LifecycleOwner,
) {

    @Volatile
    private var view: MapView? = null

    @Volatile
    private var observer: LifecycleEventObserver? = null

    @Volatile
    private var destroyed = false

    /** 创建过几次 MapView。真机上它应该恒为 1；大于 1 说明有第二条创建路径漏出来了。 */
    @Volatile
    var createCount: Int = 0
        private set

    /** 被挂载/解除挂载的次数。翻页不该动它，切屏会动它。 */
    @Volatile
    var attachCount: Int = 0
        private set

    @Volatile
    var detachCount: Int = 0
        private set

    /**
     * 有几个使用点正持有它。首页卡与全屏页在切换的那一帧里会**同时**存在
     * （新的先 acquire、旧的后 release），所以 onPause 只能在计数归零时调，
     * 否则刚挂上就被前一个使用点顺手暂停了 —— 画面就是黑的。
     */
    private var refCount = 0

    /**
     * 取 MapView 实例。**必须在隐私合规通过之后调用**（合规不通过地图会白屏）。
     *
     * @param onAttachCount 每次真正挂上（包括重挂）时回调，用于观察"有没有重建"。
     */
    @Synchronized
    fun acquire(): MapView? {
        if (destroyed) {
            MapLog.w("host: acquire after destroy -> refused")
            return null
        }
        val existing = view
        if (existing != null) {
            // **摘之前先记下旧尺寸**：重挂后要不要 pause 就看它（见 [resumeAfterAttach]）。
            // View 的 measured size 在 detach 之后仍然保留到下次 layout，所以这里取得到。
            val beforeW = existing.width
            val beforeH = existing.height
            detachFromParent(existing)
            attachCount++
            refCount++
            MapLog.i(
                "host: reuse existing MapView (attach=$attachCount detach=$detachCount refs=$refCount)",
            )
            // **重挂之后必须补一次 onResume**（2026-10-12 真机踩到的根因）：
            // MapView 被 detach 时高德内部把渲染挂起，attach 回来时又没有任何东西
            // 再叫醒它 —— Activity 的生命周期压根没变，观察者自然不会重发 ON_RESUME。
            // 现象就是"从首页点进全屏页，地图整块黑"，而且拖动也不刷新（渲染根本没跑）。
            // 用 post：attach 发生在本帧之后，直接同步调 onResume 会打在还没挂上的 view 上。
            resumeAfterAttach(existing, beforeW, beforeH)
            return existing
        }
        // 重挂后黑屏的候选解法（2026-10-12）：
        // 高德给了几个开关来改变"被 detach 时内部纹理被销毁"的行为，先打开试试，
        // 看能不能让"重挂"之后自己恢复渲染。
        //
        // 更正一条旧说法：这里原先写着"高德 MapView 内部是 TextureView" —— 那是**推断**，
        // 后来实测不成立：`dumpsys SurfaceFlinger --list` 里有独立层
        // `SurfaceView - com.qingke.desk/...MainActivity#1`，`dumpsys activity` 的
        // View Hierarchy 也在 `com.amap.api.maps.MapView{0,0-2755,1440}` 这一级终止。
        // 本机走的是 **GL SurfaceView（打洞独立图层）**，这几个 `setTexture*` 开关
        // 是给 SDK 内部的纹理路径准备的，对当前路径未必生效（保留无害，重挂恢复确实做到了）。
        // 这个事实的另一个后果：地图浮层做不了真背景模糊，见 core/ui 的 DeskGlass.kt。
        runCatching { MapsInitializer.setTextureViewDestorySync(true) }
            .onFailure { MapLog.w("setTextureViewDestorySync failed: ${it.javaClass.simpleName}") }
        runCatching { MapsInitializer.setTextureDestroyedRender(true) }
            .onFailure { MapLog.w("setTextureDestroyedRender failed: ${it.javaClass.simpleName}") }

        val created = runCatching {
            MapView(context).apply { onCreate(Bundle()) }
        }.onFailure {
            MapLog.e("host: MapView create failed", it)
        }.getOrElse { return null }

        createCount++
        attachCount++
        refCount++
        view = created
        observeLifecycle()
        MapLog.i("host: MapView created (create=$createCount attach=$attachCount refs=$refCount)")
        return created
    }

    /** 解除挂载（View 从旧父容器摘下来），**不销毁**。由 AMapCanvas 的 onDispose 调。 */
    @Synchronized
    fun release() {
        val target = view ?: return
        refCount = (refCount - 1).coerceAtLeast(0)
        // **只有真的没人用了才摘**（2026-10-12 真机根因）：
        // 切屏那一帧的顺序是"新使用点 acquire（挂到新容器）→ 旧使用点 release"。
        // 如果这里无条件 detachFromParent，摘掉的会是**刚挂好的新父容器** ——
        // MapView 变成没有父容器的游离 View，表现就是全屏页整块黑、拖动也不刷新。
        // acquire() 自己会先摘旧父，所以"别人还持有"时这里什么都不用做。
        if (refCount == 0) {
            detachFromParent(target)
            detachCount++
            runCatching { target.onPause() }
        }
        MapLog.i("host: released (attach=$attachCount detach=$detachCount refs=$refCount)")
    }

    /**
     * 重挂后叫醒渲染。
     *
     * post 而不是直接调：`acquire()` 返回时 view 还没被 AndroidView 加到容器上，
     * 这时候 `onResume()` 打在一个游离的 view 上，高德那边不会真的重启渲染循环。
     * post 的 runnable 会排在 attach 之后执行。
     *
     * **跨尺寸重挂必须先 pause 再 resume**（2026-10-13 真机根因，换设备才暴露）：
     * 地图的 GL surface 是**按建 surface 那次的尺寸**分配渲染视口的，之后 View 变大
     * 它不会自己跟着变。首页卡 409×313 dp 与全屏页 666×361 dp 共用同一个 MapView
     * （本类的全部意义），于是从首页点进全屏页时：
     *   · View 的 layout 尺寸确实变成了 2332×1264（uiautomator 实测，布局没问题）；
     *   · 但画面只画满左边 409dp，**右边 257dp 是纯黑** —— 不是页面底，是什么都没画。
     * 而这条路径上 refs 从 2 降到 1（新使用点先 acquire、旧的后 release），**压根没走
     * onPause**，只有 onResume —— 视口就这么留在了旧尺寸上。
     *
     * 判据：手动走一次 onPause→onResume（息屏再唤醒）之后，渲染宽度立刻从
     * 1432px(=409dp) 变成 2331px(=666dp)。所以修法是：重挂前后尺寸不同就补一次 pause。
     *
     * @param prevW/prevH 摘下来之前的尺寸；传 0 表示"没有旧尺寸"（例如刚创建），此时不 pause。
     */
    private fun resumeAfterAttach(target: MapView, prevW: Int, prevH: Int) {
        target.post {
            if (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                MapLog.i("host: skip resume (lifecycle not resumed)")
                return@post
            }
            val w = target.width
            val h = target.height
            // 尺寸变了：光 resume 不够 —— GL 视口是按建 surface 那次的尺寸分配的，
            // 必须先 pause 把它放掉，紧接着的 resume 才会按新尺寸重建（依据见本方法顶部注释）。
            // 少了这一次 pause，画面就停在旧视口：从首页进全屏页时右边一大块纯黑。
            val resized = w > 0 && h > 0 && prevW > 0 && prevH > 0 && (w != prevW || h != prevH)
            if (resized) {
                runCatching { target.onPause() }
                    .onFailure {
                        MapLog.w("host: onPause before resize failed: ${it.javaClass.simpleName}")
                    }
                // **不能紧接着 resume**：高德那边的 pause 是异步的（GL 线程要停下来、
                // surface 要重新按新尺寸分配），同帧甚至隔一帧 resume 都还是旧的视口
                // （实测：同帧 409dp、隔一帧 409dp，都不动）。
                // 取 300ms：比一帧宽裕得多，又短到用户看不出这一下。
                target.postDelayed({
                    runCatching { target.onResume() }
                        .onFailure {
                            MapLog.w("host: onResume after resize failed: ${it.javaClass.simpleName}")
                        }
                    MapLog.i("host: resumed after resize (${target.width}x${target.height})")
                }, RESIZE_RESUME_DELAY_MS)
            } else {
                runCatching { target.onResume() }
                    .onFailure { MapLog.w("host: onResume after attach failed: ${it.javaClass.simpleName}") }
            }
            val parent = target.parent as? android.view.View
            val size = if (w > 0 && h > 0) "${w}x${h}" else "unsized"
            MapLog.i(
                "host: resumed after attach (refs=$refCount size=$size " +
                    "parent=${parent?.javaClass?.simpleName ?: "NONE"})",
            )
        }
    }

    /** 地图对象；还没创建过返回 null。 */
    val aMap: AMap?
        get() = view?.map

    /** 是否曾经创建过（用于判断"翻页有没有重建"）。 */
    val isCreated: Boolean get() = view != null

    /**
     * 视角是否已经"种"过一次（2026-10-12 加）。
     *
     * 冷启动时我们要主动把镜头搬到上次已知位置、拿到首个定位后再搬一次 —— 这都是
     * "第一次显示地图"该做的事。但如果把它写成组合实例的局部状态，从首页点进全屏页
     * 就会**再种一次**：用户在全屏页拖到的位置会被拉回原点，所谓"地图状态不丢"
     * 就成了空话（重挂不丢、但每次进屏都被覆盖，效果一样）。
     *
     * 所以种一次就记在宿主上 —— 宿主与 MapView 同寿命，语义正好。
     */
    @Volatile
    private var cameraSeeded = false

    val isCameraSeeded: Boolean get() = cameraSeeded

    fun markCameraSeeded() {
        cameraSeeded = true
    }

    /**
     * 真正销毁。只在 Activity 销毁时调一次；重复调用幂等。
     *
     * 两处 `onDestroy` 会让渲染线程二次释放直接崩 —— 所以这里加了 destroyed 闸门，
     * 并把日志留下来（真机上 `create=1 destroy=1` 才是健康的）。
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
            MapLog.i("host: destroy (never created)")
            return
        }
        detachFromParent(target)
        runCatching { target.onDestroy() }.onFailure { MapLog.e("host: onDestroy failed", it) }
        MapLog.i("host: destroy (create=$createCount attach=$attachCount detach=$detachCount)")
    }

    private fun detachFromParent(target: MapView) {
        (target.parent as? ViewGroup)?.removeView(target)
    }

    /**
     * 生命周期只转发一次。
     *
     * 原先每个 `AMapCanvas` 各注册一个观察者；单实例之后两个使用点会同时转发，
     * 表现为 "onResume/onPause 成对出现两遍"，定位客户端被开了又关。
     */
    private fun observeLifecycle() {
        if (observer != null) return
        val created = LifecycleEventObserver { _, event ->
            val target = view ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    target.onResume()
                    MapLog.i("host: mapView.onResume")
                }

                Lifecycle.Event.ON_PAUSE -> {
                    target.onPause()
                    MapLog.i("host: mapView.onPause")
                }

                else -> Unit
            }
        }
        observer = created
        lifecycleOwner.lifecycle.addObserver(created)
    }
}
