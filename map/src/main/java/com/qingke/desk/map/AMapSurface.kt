package com.qingke.desk.map

import android.content.Context
import android.os.Bundle
import android.widget.FrameLayout
import com.amap.api.maps.AMap
import com.amap.api.maps.MapView
import com.amap.api.maps.TextureMapView

/**
 * 承载地图的那块 View，连同它需要的几个生命周期调用。
 *
 * ## 为什么要有这一层
 *
 * 高德给了两个同功能的类：`MapView` 与 `TextureMapView`。它们都是
 * `extends FrameLayout implements BaseMapView`，**公开 API 同名同形**
 * （`getMap / onCreate / onResume / onPause / onDestroy` 签名完全一致，
 * javap 核对过 11.3.100 的导航合包），但 Java 里**没有共同的父类型**可以拿来当变量类型 ——
 * `BaseMapView` 接口只声明了一个 `loadWorldVectorMap(boolean)`。
 * 所以用这个小包装把差异吃掉：上层只认 [AMapSurface]，不必知道背后是哪一种。
 *
 * ## 为什么默认用纹理版（这是本文件存在的真正原因）
 *
 * `MapView` 内部是 **GL SurfaceView**（独立图层，`dumpsys SurfaceFlinger --list` 里能看到
 * `SurfaceView - com.qingke.desk/...MainActivity#1`）。它的渲染视口**按建 surface 那一次的
 * 尺寸分配**，之后 View 变大不会自己跟着变。
 *
 * 而"首页地图卡与全屏地图页共用同一个实例"正是 [MapViewHost] 的全部意义，
 * 于是从首页点进全屏页时会出现**右侧纯黑**（2026-10-13 真机实测，2026-10-14 在
 * 一加 PKR110 上复现）：
 *
 * ```
 * View 的 layout 尺寸 = 2332×1264（布局没问题）
 * 画面只画到          x = 1883px
 * 右侧纯黑宽度        =  897px
 * 1883 − 450(侧栏)    = 1433px = 首页卡的宽度
 * ```
 *
 * 即**视口停在了首页卡的宽度上**。反方向（全屏 → 首页）不黑，因为旧视口比新容器
 * 宽，多出来的部分被 View 边界裁掉了，看不出来 —— 所以这个 bug 是单向下黑。
 *
 * `TextureMapView` 走 `TextureView`：内容画在普通 View 的纹理上，View 尺寸一变，
 * 系统必然要求重绘 —— **从机制上绕开"视口不更新"**，而不是去赌 pause/resume 的时序
 * （那条路 `wip/mapview-resize` 试过，6 次只有 1 次生效，见
 * `docs/known-issues/mapview-resize.md`）。
 *
 * 代价是失去 SurfaceView 的独立图层。本项目为零：地图浮层要做真背景模糊**本来就不行**
 * （见 `core/ui` 的 `DeskGlass.kt`），没有可失去的东西。
 */
class AMapSurface private constructor(
    /** 真正的 View。挂到容器、量尺寸、`post` 都走它。 */
    val view: FrameLayout,

    /** SDK 的地图对象。**进来的那一刻就已经 `onCreate` 过了**，所以这里必定取得到。 */
    val map: AMap,

    /** 用的是哪一种，只给日志用（出事时第一眼要能看出是纹理版还是普通版）。 */
    val kind: String,

    private val resumeFn: () -> Unit,
    private val pauseFn: () -> Unit,
    private val destroyFn: () -> Unit,
) {

    fun onResume() = resumeFn()

    fun onPause() = pauseFn()

    fun onDestroy() = destroyFn()

    companion object {

        /**
         * 建一块地图 View，并把 `onCreate` 一并做完（调用方不必再管它）。
         *
         * [savedInstanceState] 直接转给 `onCreate`；不传就给空的 —— 这块地图是跨页面**保活**
         * 的，从来不走 Android 的实例状态恢复（理由见 [MapViewHost] 顶部注释）。
         */
        fun create(context: Context, savedInstanceState: Bundle? = null): AMapSurface {
            val state = savedInstanceState ?: Bundle()
            return if (USE_TEXTURE_MAP_VIEW) {
                val v = TextureMapView(context)
                v.onCreate(state)
                AMapSurface(v, v.map, KIND_TEXTURE, v::onResume, v::onPause, v::onDestroy)
            } else {
                val v = MapView(context)
                v.onCreate(state)
                AMapSurface(v, v.map, KIND_SURFACE, v::onResume, v::onPause, v::onDestroy)
            }
        }

        /**
         * 用纹理版还是普通版。
         *
         * **普通版有已知的"跨尺寸重挂后右侧纯黑"**（见本类顶部注释），保留它**仅**为了能在
         * 真机上改这一个常量做 A/B 对照，不是默认。若哪天纹理版出现新问题需要回退，
         * 改这里 + 重跑 `docs/known-issues/mapview-resize.md` 里那份判据即可。
         */
        private const val USE_TEXTURE_MAP_VIEW = true

        const val KIND_TEXTURE: String = "texture"
        const val KIND_SURFACE: String = "surface"
    }
}
