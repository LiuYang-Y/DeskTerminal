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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qingke.desk.connection.ConnectionOrchestrator
import com.qingke.desk.core.data.HomeRepository
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
import com.qingke.desk.hosting.VdHostController
import com.qingke.desk.hosting.ui.HostingScreen
import com.qingke.desk.hosting.ui.ShizukuBootPromptCard
import com.qingke.desk.hosting.ui.ShizukuGuideScreen
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
    /** 应用托管（:hosting）：把第三方应用画进主区域。 */
    HOSTING,
    /** Shizuku 的引导页。从首页状态栏的常驻指示进来。 */
    SHIZUKU_GUIDE,
}

/**
 * 壳层根。三个屏幕：首页 / 全屏投屏 / 应用托管，以及它们之间的切换。
 *
 * 投屏页是**全屏**的（连底栏一起盖掉），托管页**不是** —— 它把画面铺在主区域，
 * 底栏留在原地，否则把第三方应用铺满之后就没有出口了。
 *
 * 混合形态（投屏 + 侧边图标条）不在本轮范围，等 EMBEDDED_SURFACE 链路
 * 跑通真机后再加 —— 那时只需要改这里的 when 分支，契约不动。
 */
@Composable
fun DeskRoot(
    orchestrator: ConnectionOrchestrator,
    repository: HomeRepository,
    displayInfo: DisplayInfo = DisplayInfo.Unspecified,
    onMessage: ((String) -> Unit)? = null,
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

    // ---- 应用托管（:hosting）------------------------------------------------
    //
    // 控制器只是"到 VdHostService 的把手"，真正的虚拟屏活在那边的 Service 里。
    // 这里**刻意不在离开托管页时把它关掉**：从地图切到投屏再切回来要能立刻恢复，
    // 每切一次就重建虚拟屏 + 重新拉起高德是不可接受的（实测一次拉起要 1~2 秒）。
    // 想彻底收掉用托管页右上角的「退出」。
    val hostingController = remember(context) { VdHostController(context) }

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
        // 玻璃背景层必须在"内容区"和"底栏"共同的父层创建：
        // 它录的是内容、画在底栏上，两头都要用同一个句柄。
        // ENABLED=false 时它返回 null，两个 Modifier 自动退化为空操作。
        val glassBackdrop = rememberDeskGlassBackdrop(
            background = MaterialTheme.colorScheme.background,
        )

        // 外层 Box 负责"铺满全屏并上色"：基准机横屏时左侧有 137px 挖孔，
        // 窗口已经延伸到挖孔下面（见 MainActivity 的 shortEdges 设置），
        // 这里让底色把挖孔区一起画掉，避免出现一条黑带；
        // 而真正的内容用 displayCutout 插图挡开，不会被摄像头孔压住。
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            IdleGuard(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.displayCutout),
                screensaver = {
                    Screensaver(
                        timeText = cards.firstOrNull { it.kind == HomeCardKind.CLOCK }?.title.orEmpty(),
                        driftX = drift.first.dp,
                        driftY = drift.second.dp,
                    )
                },
            ) {
                // 底栏与页面内容放在**同一个 Box**里，而整个 Box 又在 IdleGuard 之内：
                // 这样屏保能把它一起盖住（原来底栏在 HomeScreen 内部，就是这个效果）。
                // 反过来说，底栏不能提到 IdleGuard 外面 —— 那样屏保亮着的时候
                // 底栏还浮在上面可点，用户会在"睡着了"的屏幕上误切页面。
                Box(modifier = Modifier.fillMaxSize()) {
                    when (screen) {
                        DeskScreen.HOME -> HomeScreen(
                            cards = cards,
                            driftX = drift.first.dp,
                            driftY = drift.second.dp,
                            onDockSelect = { item ->
                                screen = handleDockSelect(item, orchestrator, onMessage, screen)
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
                            shizukuStatus = shizukuStatus,
                            onShizukuIndicatorClick = { screen = DeskScreen.SHIZUKU_GUIDE },
                        )

                        DeskScreen.SHIZUKU_GUIDE -> ShizukuGuideScreen(
                            status = shizukuStatus,
                            onRefresh = {
                                shizukuMonitor.refresh()
                                hostingController.refresh()
                            },
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

                        DeskScreen.HOSTING -> HostingScreen(
                            controller = hostingController,
                            onExitToHome = { screen = DeskScreen.HOME },
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

                    // 底栏浮在页面内容之上，首页与托管页共用；
                    // 投屏页全屏、引导页自带「返回」，两者都不带底栏。
                    if (screen == DeskScreen.HOME || screen == DeskScreen.HOSTING) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(WindowInsets.displayCutout),
                        ) {
                            DockBar(
                                items = defaultDockItems(),
                                selectedId = when (screen) {
                                    DeskScreen.HOME, DeskScreen.SHIZUKU_GUIDE -> DockIds.HOME
                                    DeskScreen.HOSTING -> DockIds.HOSTING
                                    DeskScreen.PROJECTION -> DockIds.PROJECTION
                                },
                                onSelect = { item ->
                                    screen = handleDockSelect(item, orchestrator, onMessage, screen)
                                },
                                // 托管页背后是 SurfaceView —— 它走独立图层，Compose 的
                                // 背景层采不到它的内容（会录成一片黑）。这时候给底栏玻璃
                                // 只会得到一条发黑的条子，所以托管页直接关掉玻璃。
                                glassBackdrop =
                                    if (screen == DeskScreen.HOME) glassBackdrop else null,
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

/** 底栏点选。返回切换后的目标屏幕。 */
private fun handleDockSelect(
    item: DockItem,
    orchestrator: ConnectionOrchestrator,
    onMessage: ((String) -> Unit)?,
    current: DeskScreen,
): DeskScreen = when (item.id) {
    // 从投屏态回首页必须顺手把会话关掉：否则下点，Session 还活着，
    // 状态机停在 STREAMING，再点进来就是"连一个已经存在的会话"这种脏状态。
    DockIds.HOME -> {
        if (current == DeskScreen.PROJECTION) {
            orchestrator.disconnect(StopReason.USER_EXPLICIT_EXIT)
        }
        DeskScreen.HOME
    }

    // 虚拟屏由 Service 持有，所以"切到托管页"只是把画面重新挂上来，
    // 不需要在这里做任何启动动作 —— 挂 Surface 的动作在 HostingScreen 里。
    // 反过来，从托管页切走也**不关掉**托管：切到投屏再切回来要能立刻恢复。
    DockIds.HOSTING -> DeskScreen.HOSTING

    DockIds.PROJECTION -> {
        orchestrator.connect(ProjectionKind.CARPLAY)
        DeskScreen.PROJECTION
    }

    else -> {
        if (current == DeskScreen.HOME) {
            onMessage?.invoke("${item.label} 尚未实现")
        }
        current
    }
}

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
