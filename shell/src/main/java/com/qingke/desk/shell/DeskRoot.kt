package com.qingke.desk.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qingke.desk.connection.ConnectionOrchestrator
import com.qingke.desk.core.data.HomeRepository
import com.qingke.desk.core.data.NaviPlace
import com.qingke.desk.core.data.ShellSettings
import com.qingke.desk.core.data.SidebarSide
import com.qingke.desk.core.model.DisplayInfo
import com.qingke.desk.core.platform.BurnInGuard
import com.qingke.desk.core.platform.Daylight
import com.qingke.desk.core.platform.EffectLevel
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.core.platform.RemedyIntents
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskGlass
import com.qingke.desk.core.ui.DeskTheme
import com.qingke.desk.core.ui.deskGlassOverlay
import com.qingke.desk.core.ui.deskGlassSource
import com.qingke.desk.core.ui.glassLayerEnabled
import com.qingke.desk.core.ui.rememberDeskGlassBackdrop
import com.qingke.desk.feature.media.MediaPermission
import com.qingke.desk.feature.media.MediaSessionMonitor
import com.qingke.desk.feature.media.MediaUiState
import com.qingke.desk.feature.media.music.ExoPlayerPlaybackSource
import com.qingke.desk.feature.media.music.MediaSessionPlaybackSource
import com.qingke.desk.feature.media.music.MusicPage
import com.qingke.desk.feature.media.music.RoutingPlaybackSource
import com.qingke.desk.feature.media.music.lyrics.LyricsRepository
import com.qingke.desk.feature.media.music.online.QqLoginController
import com.qingke.desk.hosting.ShizukuGate
import com.qingke.desk.hosting.ShizukuStatus
import com.qingke.desk.hosting.ShizukuStatusMonitor
import com.qingke.desk.hosting.ui.ShizukuBootPromptCard
import com.qingke.desk.hosting.ui.ShizukuGuideScreen
import com.qingke.desk.map.AMapPrivacy
import com.qingke.desk.map.AMapPrivacyCard
import com.qingke.desk.map.MapPane
import com.qingke.desk.map.MapViewHost
import com.qingke.desk.map.navi.NaviCanvas
import com.qingke.desk.map.navi.NaviPhase
import com.qingke.desk.map.navi.NaviSession
import com.qingke.desk.map.navi.NaviViewHost
import com.qingke.desk.map.navi.PlaceSuggestion
import com.qingke.desk.projection.api.AvailabilityAction
import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionKind
import com.qingke.desk.projection.api.ProjectionRenderMode
import com.qingke.desk.projection.api.ProjectionState
import com.qingke.desk.projection.api.StopReason
import kotlinx.coroutines.delay

/** 壳层的生命周期日志 tag。排查"到底算不算从外部回来"时靠它（adb logcat -s DeskShell）。 */
private const val SHELL_TAG = "DeskShell"

/**
 * 导航页那颗「结束」按钮相对内容区中心往左偏的**比例**。
 *
 * SDK 底部那排「退出/暂停」**不在内容区正中** —— 它紧挨着左侧那块全高信息面板，
 * 因此比内容区中心偏左。要把我们的按钮放到它们原来的位置，就得跟着偏。
 *
 * 3200×1440、1 dp = 3.5 px，取自同一张改动前的真机截图（.verify/reg/W1-current.png）：
 * ```
 *   NaviCanvas 内容区   x  445..3200            中心 1822，宽 2755 px = 786 dp
 *   SDK 底栏两颗药丸    x 1129..1512            中心 1320
 *   差值                1822 - 1320 = 502 px ÷ 3.5 = 143.4 dp
 *   比例                143.4 / 786 = 18.2% → 取 0.18
 * ```
 *
 * **必须是比例而不是 dp 常量**（2026-10-14 修）：`(-143).dp` 只在 786 dp 宽的内容区上成立，
 * 换到 666 dp 宽的内容区就偏了 24 dp（正好压住左侧那块信息面板），换到更宽的屏又偏右。
 * 现在按当前内容区 `maxWidth` 的比例算，偏的是**同一个相对位置**，两端都不会跑。
 *
 * 用"相对中心偏移"而不是"左对齐 + 左边距"：偏移对齐的是**中心**，
 * 以后换标签（2 字 ↔ 4 字）位置不会跑。
 */
private const val NAVI_EXIT_CHIP_X_RATIO: Float = 0.18f

enum class DeskScreen {
    HOME,
    PROJECTION,
    /**
     * 地图布局（:map）。方案 C：地图铺满整屏，卡片压成一层浮在侧栏的对侧，可收起。
     *
     * 2026-10-12 之前这一屏叫 HOSTING，走的是 :hosting 的虚拟屏把高德 App 整个
     * 搬过来；现在改用高德 SDK（MapView 直接挂进 Compose），虚拟屏不再参与。
     * :hosting 模块保留不删 —— 虚拟屏能力本身还在，只是这条链路上暂时不用它。
     *
     * **导航画面也长在这一屏里**（2026-10-12 重构）：原来有一个独立的
     * `DeskScreen.NAVI`，结果是"切到首页再回来"时这一支从组合里消失、
     * 导航 View 被销毁，而引擎还在导航 —— 两边分家，导航看起来"消失"了。
     * 现在改为**由会话阶段决定这一屏画什么**：`phase` 是
     * [NaviPhase.STARTING] / [NaviPhase.NAVIGATING] 时画导航，否则画地图 + 搜索层。
     * 于是"点侧栏地图 = 回到导航"这件事自然成立，不需要额外的状态搬运。
     */
    MAP,
    /**
     * 音乐页（路线 D 第一版）。
     *
     * 它的数据全部来自 [PlaybackSource] —— 当前实现是"读系统媒体会话"，
     * 也就是**不自己播放**，只跟着别的音乐 App 显示。所以这一屏没有任何
     * 音频权限、没有前台 Service，也不会与播放器抢音频焦点。
     *
     * 页面本身在 `:feature:media`（[com.qingke.desk.feature.media.music.MusicPage]），
     * 壳层只负责"给它一个内容区"，以及把 monitor 的生命周期管起来。
     */
    MUSIC,
    /** Shizuku 的引导页。从右上角的悬浮状态角标进来。 */
    SHIZUKU_GUIDE,
}

/**
 * 壳层根。四个屏幕：首页 / 全屏投屏 / 地图布局 / Shizuku 引导，以及它们之间的切换。
 *
 * 布局骨架（2026-10-12 定稿，横屏 794 × 361 dp）：
 *
 *   挖孔区(40) | 侧栏(76) |          内容区          [| 卡片浮层 240 + 手柄 24]
 *
 *  · **侧栏**常驻在左或右（用户可长按切换、重启后保持），浮在内容之上，
 *    是液态玻璃采样内容的那一层。**它不碰挖孔区** —— 两块各占各的。
 *  · **挖孔区不放任何东西、也不铺色**（2026-10-12 改）：整条留给外层底色，
 *    那一块的像素值因此与别处完全相同，亮度拉高也看不出色差；以后换壁纸背景
 *    它会自然透出来。它在**哪一侧由系统实时插图决定**（手机倒转 180° 时会翻到
 *    另一边，写死一侧必然错位），与侧栏在哪一侧无关。
 *  · **地图布局**下地图铺满整屏（一路铺到挖孔下面），卡片浮层收起后地图全露。
 *
 * 投屏页与引导页是**全屏**的，不吃这套骨架（侧栏不画）。
 *
 * 2026-10-12 屏保删除：原来空闲 2 分钟会盖一层屏保并把窗口亮度压到 0.08。
 * 现在**屏幕要么常亮、要么跟随系统息屏**（由用户在设置面板里选，默认常亮），
 * 不再有"第三种状态"。防烧屏只保留内容微位移（[BurnInGuard]）——
 * 它只挪固定元素的位置，不改亮度、不挡内容、不需要用户介入。
 *
 * @param onKeepScreenOnChange 常亮开关变化的通知。它是**窗口级**的事（FLAG_KEEP_SCREEN_ON
 *                             挂在 Activity 的 Window 上），:shell 不直接碰窗口，
 *                             所以由 MainActivity 传进来实现；开关一改立刻生效，不用重启。
 */
@Composable
fun DeskRoot(
    orchestrator: ConnectionOrchestrator,
    repository: HomeRepository,
    displayInfo: DisplayInfo = DisplayInfo.Unspecified,
    onMessage: ((String) -> Unit)? = null,
    onKeepScreenOnChange: (Boolean) -> Unit = {},
    /**
     * 效果等级（用户在设置里选的）。默认 AUTO。
     *
     * 这里只**显示与转发**，判断档位的事全在 [com.qingke.desk.core.platform.EffectGovernor]，
     * 壳层不自己算 —— 否则"设备能力"和"运行时温度"两套逻辑会散在两个地方。
     */
    effectLevel: EffectLevel = EffectLevel.AUTO,
    /** 当前**实际生效**的档位，由 Governor 下发。 */
    effectTier: EffectTier = EffectTier.HIGH,
    /** 一行状态说明（"高（设备最高 高 · 掉帧 8% · 温度 正常）"）。 */
    effectStatusLine: String = "",
    onEffectLevelChange: (EffectLevel) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var screen by remember { mutableStateOf(DeskScreen.HOME) }

    val connectionState by orchestrator.uiState.collectAsState()
    val session by orchestrator.activeSession.collectAsState()
    val cards by repository.observeCards().collectAsState(initial = emptyList())

    // ---- 从外部应用回来的判定 --------------------------------------------------
    //
    // 阶段一把画面交给 DiPlay，用户怎么回来有两种：按 DiPlay 里的返回／Home，
    // 或者从最近任务切回本应用。两种情况本进程都收到同一件事：**自己回到前台**。
    // 所以判定条件是"投屏态下真正进过后台，然后回来了"，而不是某个具体按键。
    //
    // 只用 onActivityResult 会有漏洞：从最近任务切回来时外部 Activity 并没有 finish，
    // 结果回调根本不会来，界面就永远卡在"投屏中"。这里用生命周期事件补上。
    //
    // 收紧后的三条判据（原来只看 PAUSE，会被通知栏 / 权限框 / 锁屏误伤）：
    //   1. **必须真的把画面交出去了** —— 会话是 EXTERNAL_ACTIVITY 且没落在 FAILED/STOPPED 上；
    //      光"停在投屏页"不算：Wi‑Fi 没开时我们就停在这一页，但画面还在自己手里；
    //   2. **必须真的进了后台** —— 只认 ON_STOP。下拉通知栏、弹权限框只会造成
    //      ON_PAUSE（本 Activity 依然可见），不该被当成"用户去看别的应用了"；
    //   3. **中途熄过屏就不算** —— ON_STOP 之后若收到 ACTION_SCREEN_OFF，
    //      这一次回前台不判返回（用户在锁屏，不是在用接收端）；但把它继续留在
    //      "待判定"状态，下次真的从外部应用回来仍然认得出来。
    //
    // 数值来源必须**直接读 Flow 的当前值**，不能读 collectAsState 的结果：
    // 后者要等一次重组才更新，而"点卡片 → connect() 建会话 → 拉起 DiPlay →
    // 自己收到 ON_STOP"完全可以发生在同一帧里。第一版就是踩了这个坑 ——
    // 日志里握手成功后 handoff 仍是 false，返回检测整个失效。
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current.applicationContext
    val context = LocalContext.current

    // ---- 地图单实例宿主 ----------------------------------------------------
    //
    // 首页第一页的地图卡与全屏地图页**共用同一个 MapView**。
    // 它在这里创建（Activity 作用域），而不是在使用点 remember ——
    // 使用点 remember 的话，翻页离屏、切屏都会把它 dispose 掉，下次再 onCreate 一次。
    //
    // 它自己是惰性的：真正的 `new MapView` 发生在第一次 acquire()，
    // 而 acquire 只在隐私合规通过之后才会被调到（见 MapPreviewCard / MapPane），
    // 所以"先同意、后构造"这条硬约束没有被绕过。
    val mapViewHost = remember(context, lifecycleOwner) {
        MapViewHost(context.applicationContext, lifecycleOwner)
    }
    // 只在整棵树消失（= Activity 销毁）时销毁一次。翻页/切屏走的是 release（解挂）。
    DisposableEffect(mapViewHost) {
        onDispose { mapViewHost.destroy() }
    }

    // ---- 导航单实例宿主（2026-10-12 加）--------------------------------------
    //
    // 导航画面（AMapNaviView）也必须跨屏保活：用户切去首页看一眼、切去投屏，
    // 回来时导航要还在。它和 MapViewHost 是同一套规则（切屏只解挂、不销毁），
    // 差别只在它没有"多使用点"——但 refCount 的坑同样存在（切屏那一帧
    // 新的先 acquire、旧的后 release），所以规则照搬，理由见 NaviViewHost 的类注释。
    val naviViewHost = remember(context, lifecycleOwner) {
        NaviViewHost(context.applicationContext, lifecycleOwner)
    }
    DisposableEffect(naviViewHost) {
        onDispose { naviViewHost.destroy() }
    }

    // ---- 验证驱动（仅 debug；release 里接收器不注册）--------------------------
    //
    // 这台基准机是 production 构建，adb 注入触摸/按键会被系统拒绝
    // （INJECT_EVENTS permission），所以状态类的验证靠广播驱动。
    // 详见 [ValidationDriver] 的说明。
    DisposableEffect(context) {
        ValidationDriver.register(context)
        onDispose { }
    }

    // ---- Shizuku 状态 -------------------------------------------------------
    //
    // 壳层不直接碰 rikka 的 API（那是 :hosting 的事），这里只订阅一个 StateFlow。
    // 状态一律**现查**，不缓存到磁盘：它可能随时被用户在别处启停。
    // 缓存的只有"本次开机的提示有没有给过"（见 ShizukuBootPrompt）。
    val shizukuMonitor = remember(context) { ShizukuStatusMonitor(context) }
    val shizukuStatus by shizukuMonitor.state.collectAsState()
    var bootPromptStatus by remember { mutableStateOf<ShizukuStatus?>(null) }
    var bootPromptChecked by remember { mutableStateOf(false) }
    DisposableEffect(shizukuMonitor) {
        shizukuMonitor.start()
        onDispose { shizukuMonitor.stop() }
    }

    val screenNow = rememberUpdatedState(screen)
    var awaitingReturn by remember { mutableStateOf(false) }
    var screenOffWhileBackgrounded by remember { mutableStateOf(false) }
    val onReturnFromExternal = rememberUpdatedState {
        orchestrator.disconnect(StopReason.EXTERNAL_APP_RETURNED)
        screen = DeskScreen.HOME
    }

    // ---- 媒体会话 ------------------------------------------------------------
    //
    // 音乐卡的数据源。它依赖"通知使用权"，而那个开关在系统设置里、由用户自己按 ——
    // 应用没法替用户开，也没法用运行时权限弹窗申请。所以除了 start/stop，
    // 还要在**每次回到前台时重新查一次**：用户从设置页勾完回来，
    // 卡片要立刻从"需要授权"切成真实曲目，而不是等下次冷启动。
    //
    // 注意这里**不再**有 `mediaState` 的 collect：卡片与音乐页的状态改由下面的
    // [RoutingPlaybackSource] 统一供给（双源择优后的一份），monitor 只当传感器用。
    val mediaMonitor = remember(context) { MediaSessionMonitor(context) }
    DisposableEffect(mediaMonitor) {
        mediaMonitor.start()
        onDispose { mediaMonitor.stop() }
    }

    // ---- 音乐页的数据源（路线 D，2026-10-14）--------------------------------
    //
    // 与上面那张媒体卡**共用同一个 monitor**。各建一个的话，同一台设备上会挂
    // 两份会话回调、两次 `getActiveSessions` —— 数据一模一样，白白多一层 binder 往返，
    // 而且两份的刷新时机还可能不一致（首页卡更新了、音乐页没更新）。理由写在
    // [MediaSessionPlaybackSource] 的类注释里。
    //
    // 分工：monitor 的 start/stop 归**壳层**（上面那个 DisposableEffect），
    // 播放源只负责"开始/停止观察它"。
    val mediaScope = rememberCoroutineScope()
    val sessionSource = remember(mediaMonitor, mediaScope) {
        MediaSessionPlaybackSource(mediaMonitor, mediaScope)
    }

    // ---- QQ 音乐账号（Phase 1，2026-10-14）----------------------------------
    //
    // 扫码是**跨页面**的：用户在等二维码时可能切回首页看一眼导航。而登录流程持有一条
    // MQTT 长连接，挂在音乐页上就会"一切页就断连" —— 用户回来时码还在屏幕上，
    // 但它已经是死码了（上游是 clean session + unicast 订阅，订阅期内的事件不重放）。
    //
    // 所以按 mediaMonitor 的同一条路走：**壳层持有，页面向下注入**。
    // 页面对应的接口只有 `StateFlow<QqLoginState>` 与三个意图回调，
    // 它不认识 MQTT、也不认识 musicu。
    val loginController = remember(context, mediaScope) {
        QqLoginController(context, mediaScope)
    }
    val loginState by loginController.state.collectAsState()
    // 读盘 + 判过期。只做一次 —— 之后状态由 controller 自己推。
    LaunchedEffect(loginController) { loginController.restore() }

    // ---- 播放源（Phase 1 第 2 步，2026-10-15）-------------------------------
    //
    // 现在有**两个**播放源，而页面只认 [PlaybackSource] 一个抽象。"该显示谁"是一条
    // **跨源**的判断 —— 既要知 QQ 在不在播，也要知别的 App 在不在播 —— 所以由
    // [RoutingPlaybackSource] 收口：QQ 自播源活跃时跟随它，否则回退系统会话源。
    // 音乐页与首页卡拿到的是同一次择优的结果，不存在两张界面各判一次。
    //
    // 两个源都归**壳层**持有、切页不停播：用户从抽屉点了歌切回首页看导航，歌还在放
    // —— 原先"只在音乐页 start"的写法会把用户刚点的歌当场掐掉。
    val exoSource = remember(context, loginController, mediaScope) {
        ExoPlayerPlaybackSource(context, loginController, mediaScope)
    }
    val playbackSource = remember(sessionSource, exoSource, mediaScope) {
        RoutingPlaybackSource(exoSource, sessionSource, mediaScope)
    }
    // 首页音乐卡的状态。与音乐页的 `source.status` 同源（同一次择优的两种契约）。
    val mediaState by playbackSource.mediaUiState.collectAsState()

    // 歌词仓库。它内部只留 `applicationContext`，所以可以放心在组合里活很久；
    // 但**不**把它做成进程级单例 —— 它持有的内存 LRU 装的是解析结果，
    // 挂在组合上，页面一走就跟着走（与本项目的原生对象约束同一条理由）。
    //
    // **注入凭证源**：QQ 歌词搜索走的是 `DoSearchForQQMusicLite`，那条端点要登录态
    // （2026-10-16 修的就是这个 —— 之前方法名写错 + 没带凭证，QQ 一字没有地落回 LRCLIB，
    // 用户看到的是"歌词不同步"）。`credentialOrNull()` 每次读盘判过期，不缓存明文。
    val lyricsRepository = remember(context, loginController) {
        LyricsRepository(context, credentialSource = loginController::credentialOrNull)
    }

    // 播放源的生命周期归**壳层**（2026-10-15 改：原先挂在 `screen == MUSIC` 上，
    // "离开音乐页就停"）。放在壳上的理由与 mediaMonitor / loginController 一样：
    // 放歌与扫码都是**跨页面**的，人切到首页时它们不能停。
    //
    // onDispose 里额外 `exoSource.release()` —— 它是唯一持有原生 ExoPlayer 实例的
    // 东西，壳层销毁（退出应用）时才放。sessionSource 只管观察，无需释放。
    DisposableEffect(playbackSource) {
        playbackSource.start()
        onDispose {
            playbackSource.stop()
            exoSource.release()
        }
    }

    // 会话一旦销毁（用户点了返回首页 / 链路断开），待判定状态一律清零。
    LaunchedEffect(session) {
        if (session == null) {
            awaitingReturn = false
            screenOffWhileBackgrounded = false
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val handoff = screenNow.value == DeskScreen.PROJECTION && isExternallyHandedOff(orchestrator)
            Log.d(
                SHELL_TAG,
                "lifecycle=$event handoff=$handoff awaiting=$awaitingReturn screenOff=$screenOffWhileBackgrounded",
            )
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (handoff) awaitingReturn = true
                }

                Lifecycle.Event.ON_RESUME -> {
                    // 授权状态可能在别处被改（用户去设置页开了通知使用权），
                    // 回到前台一律重查，避免卡片停在过期的"需要授权"上。
                    mediaMonitor.refresh()

                    // Shizuku 同理：用户很可能是切去 Shizuku 点了「启动」再切回来的。
                    // 重启后的那一次提示也挂在这里 —— 只在本次进程第一次回到前台时判定，
                    // 判定过就不再打扰。
                    shizukuMonitor.refresh()
                    if (!bootPromptChecked) {
                        bootPromptChecked = true
                        if (shizukuMonitor.consumeBootPrompt()) {
                            Log.i(
                                SHELL_TAG,
                                "first launch after boot, shizuku=${shizukuMonitor.state.value} -> prompt once",
                            )
                            bootPromptStatus = shizukuMonitor.state.value
                        }
                    }

                    when {
                        awaitingReturn && !screenOffWhileBackgrounded -> {
                            awaitingReturn = false
                            Log.i(SHELL_TAG, "back from external app -> home")
                            onReturnFromExternal.value.invoke()
                        }

                        awaitingReturn -> {
                            // 熄屏导致的回前台：不算返回，但画面还在外部应用手里，
                            // 保持待判定，下次真的回来还能认出来。
                            Log.i(SHELL_TAG, "resume after screen off -> keep waiting")
                        }

                        else -> Unit
                    }
                    screenOffWhileBackgrounded = false
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 熄屏广播：Activity 已经 STOP 时它照样能收到（注册的 Application Context）。
    DisposableEffect(appContext) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_SCREEN_OFF) return
                Log.d(SHELL_TAG, "screen off -> this resume is not a return")
                if (awaitingReturn) screenOffWhileBackgrounded = true
            }
        }
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { appContext.unregisterReceiver(receiver) } }
    }

    // 防烧屏：固定元素（时钟）的漂移量随时间缓慢变化。
    var wallClockMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            wallClockMs = System.currentTimeMillis()
            delay(DeskDimens.BURN_IN_DRIFT_PERIOD_MIN * 60_000L / 20L)
        }
    }
    val drift = BurnInGuard.driftOffsetDp(
        nowMs = wallClockMs,
        rangeDp = DeskDimens.burnInDriftRange.value,
    )

    DeskTheme {
        // 玻璃背景层必须在"内容区"和"侧栏"共同的父层创建：
        // 它录的是内容、画在侧栏上，两头都要用同一个句柄。
        // ENABLED=false 或**运行时档位是 OFF** 时它返回 null，两个 Modifier 自动退化为空操作。
        //
        // 档位这一项是 2026-10-14 补的：以前这里只认 [DeskGlass.ENABLED]，于是用户在设置里
        // 选"关闭"（或者 Governor 因高温降级）之后，背景层照旧每帧被录一遍、侧栏照旧采样 ——
        // 档位改了，画面一分不变。[EffectTier.glassLayerEnabled] 的定义就是"这一档要不要建层"：
        // OFF 不建（建了不画仍然有合成开销），其余三档建。
        val glassBackdrop = rememberDeskGlassBackdrop(
            enabled = DeskGlass.ENABLED && effectTier.glassLayerEnabled,
            background = MaterialTheme.colorScheme.background,
        )

        // ---- 侧栏在哪一侧 ----------------------------------------------------
        //
        // 初值读持久化设置，长按侧栏改它并**立刻落盘**（commit 而不是 apply：
        // 用户切完就熄屏的话，apply 的异步写盘可能还没发生，这次选择就丢了）。
        var sidebarSide by remember { mutableStateOf(ShellSettings.sidebarSide(context)) }

        // ---- 屏幕常亮 -------------------------------------------------------
        //
        // 值存在 ShellSettings 里，但**生效**要靠上层把它写到窗口 flag 上。
        // 用 LaunchedEffect 而不是"改设置时顺手调一次"：这样无论是从设置面板改的、
        // 还是别处改的，走的是同一条路径，不会出现"设置存了、窗口没跟上"。
        var keepScreenOn by remember { mutableStateOf(ShellSettings.keepScreenOn(context)) }
        LaunchedEffect(keepScreenOn) { onKeepScreenOnChange(keepScreenOn) }

        // 设置面板的开合。它是**浮层**，不占壳层骨架的位置，关掉就什么都不剩。
        var settingsOpen by remember { mutableStateOf(false) }

        // ---- 导航：交互状态 + 家/公司两个本地地址 ------------------------------
        //
        // 这里只放**界面**状态（输入框、联想列表）。目的地与"在不在导航"都在
        // [NaviSession] 里 —— 它们是会话的事，不是这一屏的事。上一版把
        // "在不在导航"存在界面枚举上，切一次屏就与引擎分家了（见 DeskScreen.MAP 注释）。
        val navi = remember { NaviUiState() }
        var homePlace by remember { mutableStateOf(ShellSettings.naviHome(context)) }
        var workPlace by remember { mutableStateOf(ShellSettings.naviWork(context)) }

        // 会话阶段：界面唯一可信的"导航还在不在"。
        val naviPhase by NaviSession.phase.collectAsState()
        val naviActive = naviPhase == NaviPhase.STARTING || naviPhase == NaviPhase.NAVIGATING
        val naviDestination by NaviSession.destination.collectAsState()
        // 首页地图卡要的两份数据：整条路线的几何（低频，只在算好/换路线时变）
        // 和实时引导（约 1 Hz）。都在会话里，这一层只订阅、不改写。
        val naviShape by NaviSession.shape.collectAsState()
        val naviGuidance by NaviSession.guidance.collectAsState()

        // 退出确认对话框是否在屏。**退出导航是唯一的结束入口，所以它必须被确认一次**——
        // 这颗按钮就压在导航画面右上角，误触一次就是"导航没了"。
        var exitConfirm by remember { mutableStateOf(false) }

        /**
         * 拿着一个**已带坐标**的目的地 → 算路 → 切到地图屏看预览。
         *
         * 起点用 [ShellSettings.lastLocation]：这台设备固定在桌上，它就是当前位置。
         * 一个定位都没有时**明确拒绝并说清楚** —— 拿一个默认坐标算路只会把人导去别处。
         */
        fun planRoute(destination: PlaceSuggestion) {
            val start = ShellSettings.lastLocation(context)
            if (start == null) {
                navi.notice = "还没有定位，稍后再试或先打开地图定位"
                return
            }
            navi.expanded = false
            navi.notice = null
            screen = DeskScreen.MAP
            // 导航中换目的地由会话自己处理（先停旧的、等回调、再算新的并自动开始），
            // 界面不需要知道这条链 —— 这正是"界面只订阅"的意思。
            NaviSession.calculate(context, start, destination)
        }

        /** 请求退出：先弹确认，不直接停。 */
        fun askExitNavi() {
            if (naviPhase == NaviPhase.IDLE) return
            exitConfirm = true
        }

        /**
         * 确认退出。**只有走到这里才会真正结束导航**（[NaviSession.requestExit]）。
         * 会话内部按 stopNavi → 等 `onStopNavi` → `destroy()` 的顺序收尾，
         * 所以退出后立刻重新开始导航也不会再撞上 `startNavi ok=false`。
         */
        fun confirmExitNavi() {
            exitConfirm = false
            NaviSession.requestExit()
            navi.reset()
        }

        // 高德隐私的同意状态。与 :map 的 MapPane 读的是同一条流，
        // 所以在这里撤回，地图屏那边会立刻换成隐私说明卡。
        val amapAgreed by AMapPrivacy.agreed.collectAsState()
        // 换屏（比如从设置面板点进 Shizuku 引导页）时自动收起，
        // 否则回来会看到一个浮在引导页上的设置面板。
        LaunchedEffect(screen) { settingsOpen = false }

        // ---- 验证驱动接进来（仅 debug 有事件）----------------------------------
        //
        // 处理动作与真实点击**走同一批回调**（screen 赋值、Pager 滚页、
        // AMapPrivacy.revoke/agree、ShellSettings.setSidebarSide），
        // 所以它验证的是真实状态机，不是一条只在验证时才存在的旁路。
        var pageRequest by remember { mutableStateOf<PageRequest?>(null) }
        LaunchedEffect(Unit) {
            ValidationDriver.commands.collect { cmd ->
                when {
                    cmd.startsWith("page:") -> {
                        val page = cmd.removePrefix("page:").toIntOrNull()
                        if (page == null) {
                            Log.w(SHELL_TAG, "bad page cmd=$cmd")
                        } else {
                            pageRequest = PageRequest(page, (pageRequest?.seq ?: 0) + 1)
                        }
                    }

                    cmd == "screen:home" -> screen = DeskScreen.HOME
                    cmd == "screen:map" -> screen = DeskScreen.MAP
                    cmd == "agree" -> AMapPrivacy.agree(context)
                    cmd == "revoke" -> AMapPrivacy.revoke(context)
                    cmd == "settings:open" -> settingsOpen = true
                    cmd == "settings:close" -> settingsOpen = false

                    // 昼夜样式的**探针注入**（2026-10-12 加）。
                    //
                    // 为什么要造这个命令：夜间地图样式原来是"等天黑"才能验的，
                    // 而唯一的办法是把系统时间改到晚上 —— 那会同时把地图 SDK 的
                    // TLS 校时搞坏（证书校验依赖正确的时间），地图直接连不上瓦片，
                    // 于是"验证夜间样式"变成了"验证地图白屏"。
                    //
                    // 现在改成注入**日出日落这四个数字**，不碰系统时钟：
                    // [Daylight.publish] 正是天气 API 到货时走的那条路，
                    // 所以这条探针验的是真实代码路径，不是另一条只在验证时才存在的旁路。
                    //   daylight:night —— 日出 23:58 / 日落 23:59，当前时刻必然在区间外
                    //   daylight:day   —— 日出 00:00 / 日落 23:59，当前时刻必然在区间内
                    //   daylight:auto  —— 清掉注入值，回到本地天文算法（也是天气失败时的状态）
                    cmd.startsWith("daylight:") -> {
                        when (cmd.removePrefix("daylight:")) {
                            "night" -> Daylight.publish(sunriseClock = "23:58", sunsetClock = "23:59")
                            "day" -> Daylight.publish(sunriseClock = "00:00", sunsetClock = "23:59")
                            "auto" -> Daylight.clearExternal()
                            else -> Log.w(SHELL_TAG, "bad daylight cmd=$cmd")
                        }
                        Log.i(SHELL_TAG, "daylight probe $cmd -> ${Daylight.isDaylight()}")
                    }
                    cmd.startsWith("side:") -> {
                        val side = if (cmd.endsWith("right")) {
                            SidebarSide.RIGHT
                        } else {
                            SidebarSide.LEFT
                        }
                        sidebarSide = ShellSettings.setSidebarSide(context, side)
                    }

                    // 模拟导航开关（2026-10-12 加，仅 debug 有实现）。
                    //
                    // 设备在桌上不动，GPS 导航**一帧引导数据都不会来**（没有位移就没有
                    // 引导事件），于是"切到首页后引擎还在不在导航""语音有没有断"
                    // "首页卡有没有实时引导"这些全都没法在屋里验。切到 EMULATOR 之后
                    // SDK 沿着算好的路线自己开，整条链路就能在桌上跑完。
                    //   navi:emulator —— 之后启动的导航走模拟
                    //   navi:gps      —— 回到真实 GPS
                    cmd == "navi:emulator" -> ValidationDriver.setEmulatorOverride(true)
                    cmd == "navi:gps" -> ValidationDriver.setEmulatorOverride(false)

                    else -> Log.w(SHELL_TAG, "unknown validation cmd=$cmd")
                }
            }
        }

        // ---- 挖孔在哪一侧：一律问系统，不猜 ----------------------------------
        //
        // 手机倒转 180° 时挖孔会跑到另一边，写死"在左边"的那天就是错位那天。
        // 这里每次重组都读实时插图，倒转之后布局自己跟着翻，代码一行不改。
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val cutout = WindowInsets.displayCutout
        val cutoutLeft = with(density) { cutout.getLeft(density, layoutDirection).toDp() }
        val cutoutRight = with(density) { cutout.getRight(density, layoutDirection).toDp() }
        val cutoutTop = with(density) { cutout.getTop(density).toDp() }
        val cutoutBottom = with(density) { cutout.getBottom(density).toDp() }

        // 挖孔区与侧栏**各占各的**：侧栏的玻璃块不延伸到挖孔区里去。
        // （先试过让侧栏延伸过去吃掉挖孔那一条，真机上看像贴歪了 —— 见 SideBar 的类注释。）
        // 挖孔区本身也不再铺色，交给外层底色透出来（删 CutoutBand 的理由见其说明）。

        // 哪几屏带"壳"（侧栏 + 齿轮浮层）。投屏页全屏、引导页自带返回，两者都不带。
        //
        // **导航中也把齿轮收起来**（2026-10-12，与重构前一致）：齿轮和"退出导航"
        // 都在右上角，同时出现会叠在一起；而导航时真正要紧的那颗按钮是退出。
        // 侧栏本身照旧常驻（用户口径），所以入口并没有被拿走。
        //
        // **音乐页带壳**（2026-10-14）：齿轮是唯一能改效果档位的地方，
        // 而"在音乐页上验证四档差别"正是这一版的验收项之一 —— 把齿轮藏起来
        // 就得退出去改档再进来，验证过程本身会变成一圈来回切屏。
        // 音乐页也已经让开这一条：歌词来源角标放在**顶部居中**而不是左上/右上
        // （齿轮会跟着侧栏镜像到任一侧，放两侧总有一边撞上）。
        val chromeScreen = screen == DeskScreen.HOME ||
            screen == DeskScreen.MUSIC ||
            (screen == DeskScreen.MAP && !naviActive)

        // 侧栏**常驻不隐藏**（用户口径，2026-10-12）：导航时也保留。
        // 我一开始按"导航要整屏看路"把它在导航页关掉了，用户看过后要求恢复 ——
        // 理由成立：侧栏是这台终端的**唯一稳定入口**，导航中途想切去音乐/投屏
        // 不该被拿走；而导航画面自己会让出这一条（内容区本来就是收缩过的）。

        // 外层 Box 负责"铺满全屏并上色"：窗口已经延伸到挖孔下面
        // （见 MainActivity 的 shortEdges 设置），底色把挖孔区一起画掉，避免黑带。
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            // ---- 全屏沉浸层（2026-10-15）：音乐页 + 地图漫游态铺满物理窗口 ----
            //
            // 这两屏的背景（音乐的光场/声波/水印、地图瓦片）一路铺到挖孔区和侧栏底下，
            // 消灭"侧栏那一整条黑边"。侧栏在骨架层里浮在它们之上：
            //   · 音乐页套 [deskGlassSource]，侧栏真玻璃直接采样折射背后流动的声波；
            //   · 地图是独立 SurfaceView 图层、采不到，侧栏改用深色底衬（见 SideBarSlot）。
            //
            // 前景（HUD / 搜索条 / 预览卡）吃 [contentSafePadding]（挖孔 + 侧栏宽 + 间距），
            // 保证任何按钮与文字都不被侧栏或挖孔遮挡。
            val sidebarInset = DeskDimens.sideBarWidth + DeskDimens.sidebarSpacing
            val contentSafePadding = PaddingValues(
                start = cutoutLeft + if (sidebarSide == SidebarSide.LEFT) sidebarInset else 0.dp,
                end = cutoutRight + if (sidebarSide == SidebarSide.RIGHT) sidebarInset else 0.dp,
                top = cutoutTop,
                bottom = cutoutBottom,
            )
            // 地图漫游态 = 地图屏 + 不在导航 + 已同意隐私。导航中/隐私卡仍走内容区避让布局。
            val mapRoamFullscreen = screen == DeskScreen.MAP && !naviActive && amapAgreed

            when {
                screen == DeskScreen.MUSIC -> MusicPage(
                    source = playbackSource,
                    library = playbackSource,
                    spectrum = playbackSource,
                    lyricsRepository = lyricsRepository,
                    loginState = loginState,
                    onStartLogin = { loginController.startLogin() },
                    onCancelLogin = { loginController.cancelLogin() },
                    onLogout = { loginController.logout() },
                    onMessage = onMessage,
                    // 玻璃采样源套在音乐页上：背景层（光场/声波/水印）录进 glassBackdrop，
                    // 侧栏真玻璃采样它折射背后的彩色声波。前景 HUD 吃 contentSafePadding。
                    modifier = Modifier
                        .fillMaxSize()
                        .deskGlassSource(glassBackdrop),
                    contentSafePadding = contentSafePadding,
                )

                mapRoamFullscreen -> {
                    MapPane(
                        modifier = Modifier.fillMaxSize(),
                        host = mapViewHost,
                        renderEnabled = true,
                        // 高德 Logo 画在地图左下角 = 窗口左下角，侧栏在左时被压住，
                        // 把它推出侧栏。侧栏在右时留在默认位置（0）。
                        logoLeftMargin = if (sidebarSide == SidebarSide.LEFT) sidebarInset else 0.dp,
                    )
                    NaviSearchLayer(
                        state = navi,
                        destination = naviDestination,
                        city = NAVI_CITY,
                        homePlace = homePlace,
                        workPlace = workPlace,
                        onPicked = { picked -> planRoute(picked) },
                        onSaveHome = { picked ->
                            val point = picked.point
                            if (point == null) {
                                navi.notice = "这个地点没有坐标，不能保存"
                            } else {
                                val place = NaviPlace(
                                    title = picked.name,
                                    point = point,
                                    address = picked.subtitle,
                                )
                                ShellSettings.setNaviHome(context, place)
                                homePlace = place
                                onMessage?.invoke("已设为家的地址：${place.title}")
                            }
                        },
                        onSaveWork = { picked ->
                            val point = picked.point
                            if (point == null) {
                                navi.notice = "这个地点没有坐标，不能保存"
                            } else {
                                val place = NaviPlace(
                                    title = picked.name,
                                    point = point,
                                    address = picked.subtitle,
                                )
                                ShellSettings.setNaviWork(context, place)
                                workPlace = place
                                onMessage?.invoke("已设为公司的地址：${place.title}")
                            }
                        },
                        onStartNavi = {
                            NaviSession.requestStart(context, ValidationDriver.emulatorOverride)
                        },
                        onClearDestination = { NaviSession.clearRoute() },
                        onRetry = { NaviSession.retryCalculate(context) },
                        contentSafePadding = contentSafePadding,
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // A. 地图**不再铺满整屏**（2026-10-12 改）。
                //
                //    原先地图在挖孔区底下也铺过去（"整屏地图"）。用户定的口径是：
                //    地图铺到**内容区** 666×361 为止，不进挖孔、也不进侧栏底下 ——
                //    高德的 logo 画在地图左下角，压在内容区里才一定完整可见。
                //    现在地图由下面的内容区分支渲染（screen == MAP 那一支）。
                //
                //    玻璃在这里依然是关掉的：MapView 走独立图层，Compose 的背景层
                //    采不到它的内容（会录成一片黑）。

                // B. 挖孔区：**什么都不画**。
                //
                // 2026-10-12 改：这里原本铺一层 surfaceVariant@55%，
                // 真机上把屏幕亮度拉高后，挖孔那一条与旁边的底色看得出色差
                // （半透明色压上去必然偏一档，亮度越高越明显）。
                // 现在整条留给底色 —— 外层的 Box 已经把 colorScheme.background
                // 铺满整个窗口（含挖孔区），那一块于是和别处**完全同一个像素值**，
                // 没有接缝。以后换成壁纸背景时它也会自然透出来，
                // 不需要再回来改这里：**别在这里加任何上色**。
                //
                // 代价：地图布局下地图会一路铺到挖孔底下（不再被压一条色带）。
                // 这正是我们要的 —— 那一条的颜色跟地图一致，同样没有接缝。

                // C. 骨架：侧栏 + 内容区。**这一层才避让挖孔** ——
                //    上面两块本来就是要盖住挖孔区的，不能跟着一起避让。
                //    挖孔区整段让给外层底色，侧栏从它右边（或左边）开始。
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            start = cutoutLeft,
                            end = cutoutRight,
                            top = cutoutTop,
                            bottom = cutoutBottom,
                        ),
                ) {
                    if (sidebarSide == SidebarSide.LEFT) {
                        SideBarSlot(
                            sidebarSide = sidebarSide,
                            screen = screen,
                            glassBackdrop = glassBackdrop,
                            context = context,
                            orchestrator = orchestrator,
                            onMessage = onMessage,
                            onScreenChange = { screen = it },
                            darkUnderlay = mapRoamFullscreen,
                        )
                    }

                    // 内容区。侧栏占掉的那一条由 Row 剩下的 weight 给出，
                    // 内容**不**伸到侧栏底下 —— 上一版底栏让内容探进去 24dp
                    // 给玻璃当采样素材，真机结果是第二排卡片被压住，被投诉过。
                    // 代价是侧栏玻璃背后只剩纯底色，"模糊背后的内容"这一项失去
                    // 意义；但 lens 的边缘折射与 highlight 仍是按形状算的，
                    // 玻璃的轮廓和高光照样在。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(
                                start = if (sidebarSide == SidebarSide.LEFT) {
                                    DeskDimens.sidebarSpacing
                                } else {
                                    0.dp
                                },
                                end = if (sidebarSide == SidebarSide.RIGHT) {
                                    DeskDimens.sidebarSpacing
                                } else {
                                    0.dp
                                },
                            ),
                    ) {
                        when (screen) {
                            DeskScreen.HOME -> HomeScreen(
                                cards = cards,
                                driftX = drift.first.dp,
                                driftY = drift.second.dp,
                                onOpenMap = { screen = DeskScreen.MAP },
                                mapHost = mapViewHost,
                                // 导航中这两份非 null：地图卡变成"整条路线 + 车点"，
                                // 并叠上下一转向 / 距离 / 路名 / 剩余里程与时间。
                                // 点卡片走的还是 onOpenMap —— 导航中地图屏画的就是导航画面。
                                naviShape = naviShape,
                                naviGuidance = naviGuidance,
                                pageRequest = pageRequest,
                                mediaState = mediaState,
                                // 三键控制发给**当前胜者**（RoutingPlaybackSource
                                // 内部按 qq/monitor 择优分发），不固定发某一个源。
                                onMediaCommand = playbackSource::command,
                                onMediaRequestPermission = {
                                    MediaPermission.openSettings(context, onFailure = { reason ->
                                        onMessage?.invoke(reason)
                                    })
                                },
                                glassBackdrop = glassBackdrop,
                                // 「回家 / 去公司」：只传名字，点击动作在这里收口。
                                homeTitle = homePlace?.title,
                                workTitle = workPlace?.title,
                                onHomeClick = {
                                    val place = homePlace
                                    if (place == null) {
                                        onMessage?.invoke("还没有设置家的地址")
                                    } else {
                                        planRoute(
                                            PlaceSuggestion(
                                                name = place.title,
                                                address = place.address,
                                                point = place.point,
                                            ),
                                        )
                                    }
                                },
                                onWorkClick = {
                                    val place = workPlace
                                    if (place == null) {
                                        onMessage?.invoke("还没有设置公司的地址")
                                    } else {
                                        planRoute(
                                            PlaceSuggestion(
                                                name = place.title,
                                                address = place.address,
                                                point = place.point,
                                            ),
                                        )
                                    }
                                },
                            )

                            // 全屏地图页（2026-10-12 改）：**地图铺满内容区**，没有
                            // 浮在上面的卡片面板，也没有展开/收起手柄 —— 那套浮层
                            // 是"一屏既要地图又要卡片"的妥协，现在卡片在首页第一页，
                            // 这一屏就专心做地图，搜索目的地和导航都从这里进。
                            //
                            // **导航画面也长在这一支里**（同日重构）：`naviActive` 为真时
                            // 画导航，否则画地图 + 搜索层。上一版把导航单列成一个
                            // `DeskScreen.NAVI`，切去首页时那一支从组合里消失、导航 View
                            // 被销毁而引擎还在导航，于是"导航没了、再点开始导航被打断"。
                            //
                            // 未同意隐私时这里渲染完整的隐私说明卡（首页那张是精简占位，
                            // 点它正是跳到这里）。放在内容区而不是铺满整屏，
                            // 是为了自动避开侧栏 —— 侧栏是半透明玻璃，压在文字上
                            // 两边都读不清（真机截图 .verify/settings/23-map-blocked.png）。
                            DeskScreen.MAP -> Box(
                                // testTag 供 androidTest 断言"已经进到全屏地图页"。
                                modifier = Modifier
                                    .fillMaxSize()
                                    .testTag(FULLSCREEN_MAP_TAG),
                            ) {
                                // 顶部一律给悬浮齿轮让出一条带：齿轮常驻在左上或右上
                                // （跟着侧栏镜像），隐私卡的标题是横向铺满的，
                                // 不让位就会被压住（真机截图 v11-revoke-fullscreen.png）。
                                val overlayInset = DeskDimens.overlayChipBandHeight
                                if (naviActive) {
                                    // ---- 导航中 ----
                                    //
                                    // 上滑逃生：与投屏页同一个理由 —— HOME 应用一旦卡在
                                    // 别人的画面里，除了 adb 卸载就没有别的出口。
                                    // 这里的画面在我们的进程里，所以这个手势**收得到触摸**。
                                    // 它走**同一颗确认框**：退出导航是唯一会结束导航的动作，
                                    // 值得多问一句。
                                    BoxWithConstraints(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .edgeSwipeUp(enabled = true, onSwipeUp = ::askExitNavi),
                                    ) {
                                        // 「结束」按钮的水平偏移**按当前内容宽算**，不是常量。
                                        //
                                        // 它对齐的是 SDK 底栏那两颗药丸原来的中心：那一排紧挨着
                                        // 左侧那块全高信息面板，所以比内容区中心偏左 ——
                                        // 比例 18% 的来历（786dp 内容宽、偏 143.4dp）见
                                        // [NAVI_EXIT_CHIP_X_RATIO] 的注释。
                                        //
                                        // 这里量的是**本分支自己的容器**（= 内容区），
                                        // 所以换机型时比例不变、落点一致；写死 dp 会随内容宽漂移。
                                        val exitChipXOffset = -maxWidth * NAVI_EXIT_CHIP_X_RATIO
                                        NaviCanvas(
                                            host = naviViewHost,
                                            // 夜间样式用我们自己的昼夜判断（天气 API 的
                                            // 日出日落已经喂给 Daylight），不用 SDK 的自动模式。
                                            night = !Daylight.isDaylight(),
                                            onExitRequest = ::askExitNavi,
                                            // 画面建好之后才 startNavi（顺序由 NaviCanvas 保证）。
                                            // 起不来就明确说一句 —— 不留一个"看着像在导航、
                                            // 其实车不会动"的画面。会话自己会退回预览态，
                                            // 于是这一支下一帧就切回搜索层，不需要我们手动切屏。
                                            onReady = {
                                                val emulator = ValidationDriver.emulatorOverride
                                                if (!NaviSession.onViewReady(context)) {
                                                    onMessage?.invoke("导航启动失败（引擎未开始），请重试")
                                                } else {
                                                    // 起得来就补一句：验证时能一眼看出走的是哪条通道。
                                                    if (emulator) Log.i(SHELL_TAG, "navi started in EMULATOR mode")
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                        // 退出按钮。**这是导航页唯一的出口**：SDK 自带底栏
                                        // 那两颗（「退出」「暂停」）已被 [NaviViewHost] 按 id
                                        // 藏掉（2026-10-13 用户口径），画面里只剩这一颗。
                                        //
                                        // ---- 位置：对齐 SDK 底栏原来的中心 ----
                                        //
                                        // 用户 2026-10-13 的原话：「图片中下方有三个按钮删除退出，
                                        // 暂停，保留退出导航并且挪到退出暂停的位置」——
                                        // 要的是**放到「退出/暂停」原来的地方**。
                                        //
                                        // 那就得先量出"原来的地方"在哪。3200×1440、1 dp = 3.5 px，
                                        // 同一张截图（.verify/reg/W1-current.png，改动前、底栏可见）：
                                        //
                                        //   NaviCanvas 内容区     x  445..3200   → 宽 2755 px = 786 dp
                                        //   SDK「退出」药丸       x 1129..1302
                                        //   SDK「暂停」药丸       x 1339..1512
                                        //   两颗成对              x 1129..1512   → 中心 1320，y 1174..1348
                                        //
                                        // 内容区中心是 1822，SDK 底栏中心是 1320，**差 502 px = 143 dp**，
                                        // 即内容宽的 **18%**（786 dp × 0.18 = 141.5 ≈ 143）。
                                        // 所以这里不是 `BottomCenter`，而是 `BottomCenter` 再往左偏
                                        // [NAVI_EXIT_CHIP_X_RATIO] × 当前内容宽（见上面 [exitChipXOffset]）。
                                        //
                                        // 纵向是量出来对上的，不是凑的：药丸底边 1348、屏底 1440，
                                        // 留白 92 px ÷ 3.5 = 26 dp（下面那个 `padding(bottom = 26.dp)`）。
                                        // 实测我们的按钮 y 1181..1349，与药丸的 1174..1348 落在同一条带里。
                                        //
                                        // 为什么用"偏移"而不是"左对齐 + 左边距"：偏移对齐的是**中心**，
                                        // 标签宽度变了位置也不会跑；用左边距得先知道按钮多宽。
                                        //
                                        // 落位的历史（改回去之前先读这段）：
                                        //   · 2026-10-12 从右上挪到左下，理由是"右上压鹰眼图、
                                        //     左上被转向卡占、右下是全览，左下是空的"；
                                        //   · 但**左下角落在那块全高信息面板里**，面板底部正好是「中速」，
                                        //     4 字标签时按钮右边缘到 x=480 与「中速」相交，4 倍放大能看清
                                        //     「中」字左下被切掉一块（.verify/reg/crop-zhongsu.png）；
                                        //   · 当时用"把标签压成 2 字、右边缘收到 x≈382"绕过，是**在不动
                                        //     位置的前提下**能想到的最好办法；
                                        //   · 现在位置换到了底部中间偏左，面板最右到 x=1075 但那一带是
                                        //     地面色，遮挡问题从根上没有了。标签保持 2 字「结束」，
                                        //     理由换成了另一条：它与二次确认框的「结束导航？」同一套说法。
                                        //
                                        // 用 [deskGlassOverlay] 而不是侧栏那块真玻璃：导航画面是
                                        // 独立 GL 图层，Compose 的背景层采不到它，真玻璃在这里
                                        // 只会糊出一块自家底色（原因写在 core:ui 的注释里）。
                                        val exitShape = RoundedCornerShape(18.dp)
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.BottomCenter)
                                                .offset(x = exitChipXOffset)
                                                .padding(bottom = 26.dp)
                                                .shadow(6.dp, exitShape)
                                                .deskGlassOverlay(exitShape)
                                                .clickable(onClick = ::askExitNavi)
                                                .padding(horizontal = 20.dp, vertical = 12.dp),
                                        ) {
                                            // **颜色必须显式给**（2026-10-12 真机裁图发现的 bug）：
                                            // 不写 color 时 Text 取的是 `LocalContentColor`，而它的默认值
                                            // 是**黑色**（只有 Surface/Card/Button 这些容器才会把它设成
                                            // onSurface）。早先玻璃蒙版是白色时黑字还看得见，改成深色蒙版
                                            // 之后就成了"黑字压黑玻璃"，整颗按钮的文字彻底消失 ——
                                            // 而这颗按钮是导航页唯一的官方出口。
                                            // 证据：`.verify/nav/G7-exit-zoom.png`。
                                            Text(
                                                text = "结束",
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                } else if (!amapAgreed) {
                                    // 漫游态（已同意 + 不在导航）已在外层全屏层渲染
                                    // （mapRoamFullscreen），内容区这里只兜"未同意隐私"这一种情况：
                                    // 政策卡保留安全区避让布局，侧栏不压文字。
                                    AMapPrivacyCard(
                                        modifier = Modifier.fillMaxSize(),
                                        topInset = overlayInset,
                                        onAgree = {
                                            AMapPrivacy.agree(context)
                                            onMessage?.invoke("已同意，地图将开始显示")
                                        },
                                        onDecline = {
                                            onMessage?.invoke("地图保持关闭。可随时在设置里重新同意")
                                        },
                                    )
                                }
                            }

                            // 音乐页已在外层全屏层渲染（screen == DeskScreen.MUSIC 那一支），
                            // 内容区这里不再画它 —— 背景铺满物理窗口、前景吃安全内边距，
                            // 见 DeskRoot 外层"全屏沉浸层"的注释。留一个空分支保住 when 穷尽。
                            DeskScreen.MUSIC -> Unit

                            DeskScreen.SHIZUKU_GUIDE -> ShizukuGuideScreen(
                                status = shizukuStatus,
                                onRefresh = { shizukuMonitor.refresh() },
                                onRequestPermission = { ShizukuGate.requestPermission() },
                                onOpenShizuku = {
                                    val intent = ShizukuGate.launchIntent(context)
                                    if (intent != null) {
                                        runCatching { context.startActivity(intent) }
                                            .onFailure { onMessage?.invoke("没能打开 Shizuku，请手动打开它") }
                                    } else {
                                        onMessage?.invoke("设备上没有安装 Shizuku")
                                    }
                                },
                                onExit = { screen = DeskScreen.HOME },
                            )

                            DeskScreen.PROJECTION -> ProjectionScreen(
                                uiState = connectionState,
                                session = session,
                                displayInfo = displayInfo,
                                onExit = {
                                    orchestrator.disconnect(StopReason.USER_HOME_GESTURE)
                                    screen = DeskScreen.HOME
                                },
                                onRetry = orchestrator::retryNow,
                                onRemedy = { unavailable -> runRemedy(unavailable, context, onMessage) },
                            )
                        }

                    }

                    if (sidebarSide == SidebarSide.RIGHT) {
                        SideBarSlot(
                            sidebarSide = sidebarSide,
                            screen = screen,
                            glassBackdrop = glassBackdrop,
                            context = context,
                            orchestrator = orchestrator,
                            onMessage = onMessage,
                            onScreenChange = { screen = it },
                            darkUnderlay = mapRoamFullscreen,
                        )
                    }
                }

                // 重启后的一次性提示。盖在首页之上，关掉就不再出现（同一个开机周期内）。
                val pendingPrompt = bootPromptStatus
                if (pendingPrompt != null && screen == DeskScreen.HOME) {
                    ShizukuBootPromptCard(
                        status = pendingPrompt,
                        onOpenGuide = {
                            bootPromptStatus = null
                            screen = DeskScreen.SHIZUKU_GUIDE
                        },
                        onDismiss = { bootPromptStatus = null },
                    )
                }

                // ---- 退出导航的二次确认 --------------------------------------
                //
                // 为什么非要问一句：「结束」是**唯一**会结束导航的动作，而它就在导航画面
                // 左下角，挨着 SDK 自己的信息面板（两者相距不到 10 dp），误触一次 =
                // 正在跑的导航没了，而且（重构前）还会连带把下一次导航也搞坏。
                // 另一颗入口（边缘上滑手势）走的是同一颗确认框：它是"逃生"而不是"快捷退出"，
                // 多一次点击不影响它能不能用 —— 卡住的时候对话框照样出得来。
                if (exitConfirm) {
                    AlertDialog(
                        onDismissRequest = { exitConfirm = false },
                        title = {
                            Text(
                                text = "结束导航？",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                        text = {
                            val name = naviDestination?.name
                            Text(
                                text = if (name != null) {
                                    "将结束前往「$name」的导航，之后可以重新选目的地。"
                                } else {
                                    "将结束当前导航，之后可以重新选目的地。"
                                },
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = ::confirmExitNavi) {
                                Text("结束导航", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { exitConfirm = false }) {
                                Text("继续导航", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                }

                // ---- 设置浮层 ------------------------------------------------
                //
                // 两个元素，声明顺序就是 z 序，不能颠倒：
                //   1. 遮罩层：只在浮层展开时存在，点它 → 关闭浮层；
                //   2. 右上角悬浮区（齿轮 + 浮层本体）：在遮罩**之后**声明，
                //      所以浮层展开时齿轮依然浮在遮罩之上、还能再点一次收起。
                // 反过来写的话，遮罩会把齿轮盖住，用户只能靠"完成"按钮退出。
                if (chromeScreen && settingsOpen) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .pointerInput(Unit) {
                                detectTapGestures { settingsOpen = false }
                            },
                    )
                }

                if (chromeScreen) {
                    // 悬浮区跟着侧栏镜像：侧栏在左则贴右上，侧栏在右则贴左上
                    // （右上角那时被侧栏占着）。
                    val overlayCorner = sidebarSide == SidebarSide.LEFT
                    val alignTo = if (overlayCorner) Alignment.TopEnd else Alignment.TopStart

                    // **齿轮与浮层是并列的两块，不是上下叠的一列**（2026-10-12 改）。
                    //
                    // 之前浮层排在齿轮下面（同一个 Column），白白扔掉"顶部留白 +
                    // 齿轮 40 + 间距 8"共 60 dp —— 横屏只有 411 dp 高，60 dp 换来的
                    // 后果是四项设置排不下，最后一项（Shizuku）被挤出屏幕
                    // （真机截图 .verify/pager/w3-settings.png）。改成并列之后，
                    // 浮层的可用高度从约 312 dp 变成约 387 dp，四项一次全见。
                    //
                    // 并列的代价是浮层要**让开齿轮那一列**：齿轮占顶部 40 dp 宽的一条，
                    // 所以浮层在齿轮那一侧多留 52 dp（40 + 8 间距）。
                    // 悬浮区**只有齿轮一个常驻元素**：原来还挂着一颗 Shizuku 状态角标，
                    // 它压在卡片角上会跟天气卡、全屏隐私卡、「回家/去公司」快捷卡叠字 ——
                    // 三处叠字同一个原因。状态现在在设置面板里（[SettingsPane] 的
                    // 「Shizuku」一行）。
                    Box(
                        modifier = Modifier
                            .align(alignTo)
                            .padding(
                                top = DeskDimens.statusChipTopPadding,
                                start = DeskDimens.statusChipSidePadding,
                                end = DeskDimens.statusChipSidePadding,
                            ),
                    ) {
                        SettingsToggleChip(
                            open = settingsOpen,
                            onClick = { settingsOpen = !settingsOpen },
                        )
                    }

                    if (settingsOpen) {
                        // 面板贴齿轮的外侧：齿轮在右上时面板靠右、让开右边那一条。
                        val gearLane =
                            DeskDimens.settingsChipSize + DeskDimens.statusChipGap
                        SettingsPane(
                            modifier = Modifier
                                .align(alignTo)
                                .padding(
                                    top = DeskDimens.statusChipTopPadding,
                                    start = DeskDimens.statusChipSidePadding +
                                        if (overlayCorner) 0.dp else gearLane,
                                    end = DeskDimens.statusChipSidePadding +
                                        if (overlayCorner) gearLane else 0.dp,
                                ),
                            sidebarSide = sidebarSide,
                            keepScreenOn = keepScreenOn,
                            amapAgreed = amapAgreed,
                            shizukuStatus = shizukuStatus,
                            effectLevel = effectLevel,
                            effectTier = effectTier,
                            effectStatusLine = effectStatusLine,
                            onEffectLevelChange = onEffectLevelChange,
                            onSidebarSideChange = { side ->
                                sidebarSide = ShellSettings.setSidebarSide(context, side)
                            },
                            onKeepScreenOnChange = { enabled ->
                                keepScreenOn = ShellSettings.setKeepScreenOn(context, enabled)
                            },
                            onRevokeAmapConsent = {
                                AMapPrivacy.revoke(context)
                                onMessage?.invoke("已撤回地图隐私同意，地图将不再显示")
                            },
                            // 切屏时设置面板会自动收起（见上面的 LaunchedEffect(screen)），
                            // 所以这里只管切屏，不用自己关浮层。
                            onOpenShizukuGuide = { screen = DeskScreen.SHIZUKU_GUIDE },
                            onDismiss = { settingsOpen = false },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 是否"真的把画面交给外部应用了"。判定必须读**实时值**，理由见调用点注释。
 *
 * 排除 FAILED/STOPPING/STOPPED：前两者意味着这次拉起根本没成功（例如 DiPlay 刚被卸掉），
 * 后者是已经在收尾。剩下的 PREPARING/CONNECTING/IDLE/STREAMING 都属于
 * "我们要么已经把画面交出去了、要么正在交出去"，此时退到后台再回来就算返回。
 */
private fun isExternallyHandedOff(orchestrator: ConnectionOrchestrator): Boolean {
    val session = orchestrator.activeSession.value ?: return false
    if (session.capabilities.renderMode != ProjectionRenderMode.EXTERNAL_ACTIVITY) return false
    return when (session.state.value) {
        ProjectionState.FAILED, ProjectionState.STOPPING, ProjectionState.STOPPED -> false
        else -> true
    }
}

/**
 * 提示页上的自助跳转。
 *
 * **只负责把系统设置界面推到前面，绝不代用户改任何开关** —— Wi‑Fi 开不开、应用启不启用，
 * 由用户在系统界面里按。这也是 [RemedyIntents.wifiSettings] 返回列表而不是单个 Intent 的原因：
 * 优先给浮层面板，被 ROM 拒绝时再退到完整设置页；两条都走不通就老实告诉用户。
 */
private fun runRemedy(
    unavailable: AvailabilityResult.Unavailable,
    context: Context,
    onMessage: ((String) -> Unit)?,
) {
    val candidates: List<Intent> = when (unavailable.action) {
        AvailabilityAction.OPEN_WIFI_SETTINGS -> RemedyIntents.wifiSettings()
        AvailabilityAction.OPEN_APP_DETAILS ->
            unavailable.extras["package"]?.let(RemedyIntents::appDetails).orEmpty()

        null -> emptyList()
    }

    if (candidates.isEmpty()) {
        Log.w(SHELL_TAG, "no remedy intent for ${unavailable.code}")
        onMessage?.invoke("这一类问题没有可跳转的界面，请按提示手动处理")
        return
    }

    var launched = false
    for (intent in candidates) {
        launched = runCatching {
            context.startActivity(intent)
            true
        }.onFailure { throwable ->
            Log.w(SHELL_TAG, "remedy intent failed: ${throwable.javaClass.simpleName}")
        }.getOrDefault(false)
        if (launched) break
    }

    if (!launched) {
        Log.w(SHELL_TAG, "all remedy intents failed for ${unavailable.code}")
        onMessage?.invoke("没能打开系统设置页，请在系统设置里手动处理后再回来")
    }
}

/** 侧栏点选。返回切换后的目标屏幕。 */
private fun handleShellSelect(
    item: ShellItem,
    orchestrator: ConnectionOrchestrator,
    onMessage: ((String) -> Unit)?,
    current: DeskScreen,
): DeskScreen = when (item.id) {
    // 从投屏态回首页必须顺手把会话关掉：否则下点，Session 还活着，
    // 状态机停在 STREAMING，再点进来就是"连一个已经存在的会话"这种脏状态。
    ShellIds.HOME -> {
        if (current == DeskScreen.PROJECTION) {
            orchestrator.disconnect(StopReason.USER_EXPLICIT_EXIT)
        }
        DeskScreen.HOME
    }

    // 地图布局：:map 的 MapView 由 MapPane 自己管理生命周期，
    // 这里只是切屏，不做任何启动动作。
    ShellIds.MAP -> DeskScreen.MAP

    // 音乐页。它自己也可能是"从投屏态切过来"的，所以和 HOME 一样顺手收掉会话 ——
    // 否则 Session 还活着、状态机停在 STREAMING，下次再点投屏就是连一个已存在的会话。
    ShellIds.MUSIC -> {
        if (current == DeskScreen.PROJECTION) {
            orchestrator.disconnect(StopReason.USER_EXPLICIT_EXIT)
        }
        DeskScreen.MUSIC
    }

    ShellIds.PROJECTION -> {
        orchestrator.connect(ProjectionKind.CARPLAY)
        DeskScreen.PROJECTION
    }

    // 「智能家居」从默认条目里去掉了，但分支留着 ——
    // 以后加回侧栏（或换成别的方式唤起）时不用再补这段。
    ShellIds.SMART_HOME -> {
        onMessage?.invoke("智能家居尚未实现")
        current
    }

    else -> {
        if (current == DeskScreen.HOME) {
            onMessage?.invoke("${item.label} 尚未实现")
        }
        current
    }
}

/** 当前屏幕对应侧栏里的哪一项（决定玻璃指示块停在哪儿）。 */
private fun selectedShellId(screen: DeskScreen): String = when (screen) {
    DeskScreen.HOME, DeskScreen.SHIZUKU_GUIDE -> ShellIds.HOME
    // 导航画面也长在地图屏里（见 DeskScreen.MAP 的注释），所以这里天然就是它。
    DeskScreen.MAP -> ShellIds.MAP
    DeskScreen.MUSIC -> ShellIds.MUSIC
    DeskScreen.PROJECTION -> ShellIds.PROJECTION
}

/**
 * 侧栏槽位。左/右两次调用共用这一份实现，差别只在 [extendOnStart]。
 *
 * 抽出来是为了让"切换后卡片区、收起手柄、状态指示都要跟着镜像"这件事
 * 只有一处代码：两边各写一遍，改一处漏一处就是错位。
 */
@Composable
private fun RowScope.SideBarSlot(
    sidebarSide: SidebarSide,
    screen: DeskScreen,
    glassBackdrop: LayerBackdrop?,
    context: Context,
    orchestrator: ConnectionOrchestrator,
    onMessage: ((String) -> Unit)?,
    onScreenChange: (DeskScreen) -> Unit,
    /** 地图漫游态侧栏用深色底衬（地图是独立图层、玻璃采不到，见 SideBar 参数注释）。 */
    darkUnderlay: Boolean = false,
) {
    // **侧栏的可用高度必须量出来，不能拿档案算**（2026-10-13）：
    //
    // 档案里的 heightDp 是"窗口范围"（上界），而沉浸式下系统仍会留一圈不给应用
    // （基准机上是左侧挖孔 39dp + 底部手势条 16dp）。按上界均分会把最后一项排到
    // 画不到的地方 —— `wm size` 模拟 800×360 dp 时第 5 项「手机投屏」实测被屏幕底边切掉。
    // 这里量的是外层 Row 真正给它的高度，是唯一不会错的那个数。
    BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
        // maxHeight 已经是 Dp（BoxWithConstraints 给的约束），直接用。
        val railHeightDp = maxHeight.value
        SideBar(
            items = defaultShellItems(),
            selectedId = selectedShellId(screen),
            onSelect = { item ->
                onScreenChange(handleShellSelect(item, orchestrator, onMessage, screen))
            },
            glassBackdrop = glassBackdrop,
            // 这里从 RowScope 的 `align(CenterVertically)` 换成 Box 的 `align(CenterStart)`：
            // 多包了一层 BoxWithConstraints 之后，隐式接收者已经变成 BoxScope。
            // 两者对"竖直居中"是同一件事，横向本来就是这个盒子自己撑开的宽度。
            modifier = Modifier.align(Alignment.CenterStart),
            availableHeightDp = railHeightDp,
            darkUnderlay = darkUnderlay,
        )
    }
}

/**
 * 挖孔区不再单独铺色（2026-10-12 删）。
 *
 * 原来的 [CutoutBand] 铺 `surfaceVariant @55%`，用意是"让挖孔那一条看起来像边框"。
 * 真机上把亮度拉高后它和旁边的底色有色差 —— 半透明色叠在底色上必然偏一档，
 * 屏幕越亮越明显。现在整条交给外层底色（将来是壁纸）自己透出来，
 * 像素值与别处完全一致，没有接缝。
 *
 * 这里留一段说明而不是留空函数：**别再往挖孔区加任何上色**，
 * 换壁纸那天一加上去就又成了色差。
 */
