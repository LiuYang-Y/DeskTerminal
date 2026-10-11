package com.qingke.desk.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
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
import com.qingke.desk.core.model.HomeCardKind
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
import com.qingke.desk.hosting.ui.ShizukuStatusChip
import com.qingke.desk.map.MapPane
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
 * @param onScreensaverChange 进出屏保的通知，用来只在屏保时压暗窗口亮度。
 */
@Composable
fun DeskRoot(
    orchestrator: ConnectionOrchestrator,
    repository: HomeRepository,
    displayInfo: DisplayInfo = DisplayInfo.Unspecified,
    onMessage: ((String) -> Unit)? = null,
    onScreensaverChange: (Boolean) -> Unit = {},
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
        var mapPaneCollapsed by remember { mutableStateOf(false) }

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
            // 屏保要能盖住**全部**内容（含铺满的地图），所以地图也得在 IdleGuard 之内；
            // 而"避让挖孔"是更内层骨架的事 —— 地图本来就是要盖住挖孔区的，
            // 不能跟着一起避让。
            IdleGuard(
                modifier = Modifier.fillMaxSize(),
                // 屏保进出要通知上层改窗口亮度：只有屏保压暗，平时绝不碰用户自己的亮度。
                onIdleChange = onScreensaverChange,
                screensaver = {
                    Screensaver(
                        timeText = cards.firstOrNull { it.kind == HomeCardKind.CLOCK }?.title.orEmpty(),
                        driftX = drift.first.dp,
                        driftY = drift.second.dp,
                    )
                },
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // A. 地图：铺满**整屏**，含挖孔区。它是这一屏的底。
                    //    玻璃在这里是关掉的：MapView 走独立图层，Compose 的背景层
                    //    采不到它的内容（会录成一片黑）。
                    if (screen == DeskScreen.MAP) {
                        MapPane(modifier = Modifier.fillMaxSize())
                    }

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
                                onSideChange = { sidebarSide = it },
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
                                    onShellSelect = { item ->
                                        screen = handleShellSelect(item, orchestrator, onMessage, screen)
                                    },
                                    onCardSelect = { card ->
                                        if (card.kind == HomeCardKind.PROJECTION) {
                                            orchestrator.connect(ProjectionKind.CARPLAY)
                                            screen = DeskScreen.PROJECTION
                                        } else {
                                            onMessage?.invoke("${card.title} 尚未支持点击")
                                        }
                                    },
                                    mediaState = mediaState,
                                    onMediaCommand = mediaMonitor::command,
                                    onMediaRequestPermission = {
                                        MediaPermission.openSettings(context, onFailure = { reason ->
                                            onMessage?.invoke(reason)
                                        })
                                    },
                                    glassBackdrop = glassBackdrop,
                                )

                                // 地图布局：地图在底层铺满了，这里只放卡片浮层，
                                // 它待在**侧栏的对侧**，可收成一条手柄让地图全露。
                                DeskScreen.MAP -> MapCardPane(
                                    cards = cards,
                                    sidebarSide = sidebarSide,
                                    collapsed = mapPaneCollapsed,
                                    onToggleCollapsed = { mapPaneCollapsed = !mapPaneCollapsed },
                                    driftX = drift.first.dp,
                                    driftY = drift.second.dp,
                                    mediaState = mediaState,
                                    onMediaCommand = mediaMonitor::command,
                                    onMediaRequestPermission = {
                                        MediaPermission.openSettings(context, onFailure = { reason ->
                                            onMessage?.invoke(reason)
                                        })
                                    },
                                    modifier = Modifier.align(
                                        if (sidebarSide == SidebarSide.LEFT) {
                                            Alignment.CenterEnd
                                        } else {
                                            Alignment.CenterStart
                                        },
                                    ),
                                )

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

                            // Shizuku 悬浮角标：**只在异常时出现**（未装 / 未运行 / 未授权），
                            // 一切正常时屏幕上没有它 —— 常亮设备不该为一个正常状态
                            // 长期占着一块地方。位置跟着侧栏镜像：侧栏在左则贴右上，
                            // 侧栏在右则贴左上（右上角那时被侧栏占着）。
                            if (chromeScreen && shizukuStatus != ShizukuStatus.READY) {
                                Box(
                                    modifier = Modifier
                                        .align(
                                            if (sidebarSide == SidebarSide.LEFT) {
                                                Alignment.TopEnd
                                            } else {
                                                Alignment.TopStart
                                            },
                                        )
                                        .padding(
                                            top = DeskDimens.statusChipTopPadding,
                                            start = DeskDimens.statusChipSidePadding,
                                            end = DeskDimens.statusChipSidePadding,
                                        ),
                                ) {
                                    ShizukuStatusChip(
                                        status = shizukuStatus,
                                        onClick = { screen = DeskScreen.SHIZUKU_GUIDE },
                                    )
                                }
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
                                onSideChange = { sidebarSide = it },
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
    onSideChange: (SidebarSide) -> Unit,
) {
    SideBar(
        items = defaultShellItems(),
        selectedId = selectedShellId(screen),
        onSelect = { item ->
            onScreenChange(handleShellSelect(item, orchestrator, onMessage, screen))
        },
        glassBackdrop = glassBackdrop,
        onToggleSide = {
            val next = ShellSettings.setSidebarSide(context, sidebarSide.flipped())
            onSideChange(next)
            onMessage?.invoke(
                "侧栏已切到${if (next == SidebarSide.LEFT) "左侧" else "右侧"}",
            )
        },
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

/**
 * 屏保。内容刻意简单：只有时间，且持续漂移。
 *
 * **漂移必须用 offset，不能用 padding。** 漂移量是 sin() 出来的有符号值
 * （-range..+range dp），而 Modifier.padding 不允许负数 —— 传负值会抛
 * IllegalArgumentException("Padding must be non-negative") 直接崩掉进程。
 * 这个坑踩过一次，现象是"应用开着大约两分钟必闪退"（空闲满 2 分钟才渲染屏保，
 * 屏保首次取到负漂移即崩）。语义上本来也该用 offset：我们要的是把内容整体**挪**一下，
 * 不是给它加内边距。
 *
 * TODO(adb) [待校正]：屏保亮度 [DeskDimens.SCREENSAVER_BRIGHTNESS] 需要在
 *           真机 OLED 上确认是否低到足够避免残影、又不至于看不见。
 */
@Composable
fun Screensaver(
    timeText: String,
    driftX: Dp,
    driftY: Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.offset(x = driftX, y = driftY),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = timeText.ifBlank { "--:--" },
                fontSize = DeskDimens.clockTime * 1.5f,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "轻触返回",
                fontSize = DeskDimens.cardCaption,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            )
        }
    }
}
