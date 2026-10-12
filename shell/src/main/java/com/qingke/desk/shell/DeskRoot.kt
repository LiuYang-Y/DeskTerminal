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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qingke.desk.connection.ConnectionOrchestrator
import com.qingke.desk.core.data.HomeRepository
import com.qingke.desk.core.data.ShellSettings
import com.qingke.desk.core.data.SidebarSide
import com.qingke.desk.core.model.DisplayInfo
import com.qingke.desk.core.platform.BurnInGuard
import com.qingke.desk.core.platform.RemedyIntents
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskTheme
import com.qingke.desk.core.ui.rememberDeskGlassBackdrop
import com.qingke.desk.feature.media.MediaPermission
import com.qingke.desk.feature.media.MediaSessionMonitor
import com.qingke.desk.feature.media.MediaUiState
import com.qingke.desk.hosting.ShizukuGate
import com.qingke.desk.hosting.ShizukuStatus
import com.qingke.desk.hosting.ShizukuStatusMonitor
import com.qingke.desk.hosting.ui.ShizukuBootPromptCard
import com.qingke.desk.hosting.ui.ShizukuGuideScreen
import com.qingke.desk.map.AMapPrivacy
import com.qingke.desk.map.AMapPrivacyCard
import com.qingke.desk.map.MapPane
import com.qingke.desk.map.MapViewHost
import com.qingke.desk.projection.api.AvailabilityAction
import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionKind
import com.qingke.desk.projection.api.ProjectionRenderMode
import com.qingke.desk.projection.api.ProjectionState
import com.qingke.desk.projection.api.StopReason
import kotlinx.coroutines.delay

/** 壳层的生命周期日志 tag。排查"到底算不算从外部回来"时靠它（adb logcat -s DeskShell）。 */
private const val SHELL_TAG = "DeskShell"

enum class DeskScreen {
    HOME,
    PROJECTION,
    /**
     * 地图布局（:map）。方案 C：地图铺满整屏，卡片压成一层浮在侧栏的对侧，可收起。
     *
     * 2026-10-12 之前这一屏叫 HOSTING，走的是 :hosting 的虚拟屏把高德 App 整个
     * 搬过来；现在改用高德 SDK（MapView 直接挂进 Compose），虚拟屏不再参与。
     * :hosting 模块保留不删 —— 虚拟屏能力本身还在，只是这条链路上暂时不用它。
     */
    MAP,
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
    val mediaMonitor = remember(context) { MediaSessionMonitor(context) }
    val mediaState by mediaMonitor.state.collectAsState()
    DisposableEffect(mediaMonitor) {
        mediaMonitor.start()
        onDispose { mediaMonitor.stop() }
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
        // ENABLED=false 时它返回 null，两个 Modifier 自动退化为空操作。
        val glassBackdrop = rememberDeskGlassBackdrop(
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
                    cmd.startsWith("side:") -> {
                        val side = if (cmd.endsWith("right")) {
                            SidebarSide.RIGHT
                        } else {
                            SidebarSide.LEFT
                        }
                        sidebarSide = ShellSettings.setSidebarSide(context, side)
                    }

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

        // 哪几屏带"壳"（侧栏）。投屏页全屏、引导页自带返回，两者都不带。
        val chromeScreen = screen == DeskScreen.HOME || screen == DeskScreen.MAP

        // 外层 Box 负责"铺满全屏并上色"：窗口已经延伸到挖孔下面
        // （见 MainActivity 的 shortEdges 设置），底色把挖孔区一起画掉，避免黑带。
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
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
                                pageRequest = pageRequest,
                                mediaState = mediaState,
                                onMediaCommand = mediaMonitor::command,
                                onMediaRequestPermission = {
                                    MediaPermission.openSettings(context, onFailure = { reason ->
                                        onMessage?.invoke(reason)
                                    })
                                },
                                glassBackdrop = glassBackdrop,
                            )

                            // 全屏地图页（2026-10-12 改）：**地图铺满内容区**，没有
                            // 浮在上面的卡片面板，也没有展开/收起手柄 —— 那套浮层
                            // 是"一屏既要地图又要卡片"的妥协，现在卡片在首页第一页，
                            // 这一屏就专心做地图，以后搜索目的地和导航也从这里进。
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
                                if (amapAgreed) {
                                    MapPane(
                                        modifier = Modifier.fillMaxSize(),
                                        host = mapViewHost,
                                        renderEnabled = true,
                                        topInset = overlayInset,
                                    )
                                } else {
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
    DeskScreen.MAP -> ShellIds.MAP
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
) {
    SideBar(
        items = defaultShellItems(),
        selectedId = selectedShellId(screen),
        onSelect = { item ->
            onScreenChange(handleShellSelect(item, orchestrator, onMessage, screen))
        },
        glassBackdrop = glassBackdrop,
        modifier = Modifier.align(Alignment.CenterVertically),
    )
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
