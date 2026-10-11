package com.qingke.desk.hosting.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.qingke.desk.hosting.BlockReason
import com.qingke.desk.hosting.HostLog
import com.qingke.desk.hosting.HostState
import com.qingke.desk.hosting.HostTargets
import com.qingke.desk.hosting.ShizukuGate
import com.qingke.desk.hosting.VdHostController
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/** 彻底失败后停留多久自动回首页。留一点时间让人看清原因。 */
private const val FAILED_AUTO_EXIT_MS = 3500L

/**
 * 托管页：把被托管应用的画面铺在主区域，并把触摸转发过去。
 *
 * 三层结构：
 *   1. SurfaceView —— 虚拟屏的画面出口。虚拟屏由 **Service** 持有（见 VdHostService），
 *      这里只负责在 surface 可用时把 Surface 交给它；Activity 重建时本 View 会重建，
 *      但虚拟屏和目标应用都不受影响。
 *   2. 手势层 —— 把 Compose 收到的点击/拖拽换算成虚拟屏坐标，
 *      交给 Service 通过 `input -d` 注入。
 *   3. 状态层 —— 启动中 / 被挡住 / 失败 三种情况的遮罩。
 */
@Composable
fun HostingScreen(
    controller: VdHostController,
    onExitToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(controller) {
        controller.bind()
        onDispose { controller.unbind() }
    }

    // Shizuku 的授权弹窗由用户在系统弹窗里自己点 —— 这里只接收结果。
    // 拿不到结果就永远不会从"未授权"变成"可用"，所以这个监听是必须的。
    DisposableEffect(controller) {
        val listener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            HostLog.i("shizuku permission result: code=$requestCode granted=$grantResult")
            controller.refresh()
        }
        Shizuku.addRequestPermissionResultListener(listener)
        onDispose { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    // 回到前台就重查一次 Shizuku：用户很可能是切到 Shizuku App 里点了"启动"再切回来的，
    // 不在 onResume 重查的话，界面会一直停在"未运行"直到下次冷启动。
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                HostLog.d("hosting screen resumed -> refresh")
                controller.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 重试耗尽：看完原因自动回首页，不把用户丢在一个没有画面的页面上。
    LaunchedEffect(state) {
        if (state is HostState.Failed) {
            delay(FAILED_AUTO_EXIT_MS)
            onExitToHome()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HostSurface(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
        )

        when (val current = state) {
            HostState.Idle -> Unit

            is HostState.Blocked -> BlockedNotice(
                reason = current.reason,
                onRefresh = { controller.refresh() },
                onRequestPermission = { controller.requestShizukuPermission() },
                onExit = onExitToHome,
            )

            is HostState.Starting -> HostingStatusBar(
                text = if (current.attempt > 1) {
                    "虚拟屏重建中（第 ${current.attempt} 次）…"
                } else {
                    "正在启动${HostTargets.AMAP.label}…"
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )

            is HostState.Running -> HostingControls(
                onBack = { controller.injectKey(KEYCODE_BACK) },
                onExit = {
                    controller.stopHosting()
                    onExitToHome()
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )

            is HostState.Failed -> HostingNotice(
                title = "托管未能启动",
                steps = listOf(current.reason),
                note = "即将返回首页。回到首页后点底栏的「地图」可以再来一次。",
                primaryAction = "立即返回首页",
                onPrimaryAction = onExitToHome,
            )
        }
    }
}

@Composable
private fun HostSurface(
    controller: VdHostController,
    modifier: Modifier = Modifier,
) {
    val tracker = remember(controller) { HostGestureTracker(controller) }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                // 手势**直接在这个 View 上收**，不用 Compose 的 pointerInput。
                //
                // 原因是实测出来的：把 pointerInput 挂在承载它的 Compose 节点上时，
                // 触摸先被这个 View 吃掉（view 在 Compose 指针链路上有优先权），
                // 注入一次都没发生 —— 画面 0.00% 变化，日志里连 inject 记录都没有。
                // 换成 View 自己的 onTouch 之后，坐标还天然是**这个 View 的物理像素**，
                // 而虚拟屏就是按这个 View 的像素尺寸建的，两边一一对应，不用做任何换算。
                setOnTouchListener(tracker::onTouch)
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        val w = width.takeIf { it > 0 } ?: holder.surfaceFrame.width()
                        val h = height.takeIf { it > 0 } ?: holder.surfaceFrame.height()
                        HostLog.d("surfaceCreated ${w}x${h} dpi=${resources.displayMetrics.densityDpi}")
                        controller.attach(
                            holder.surface,
                            w,
                            h,
                            resources.displayMetrics.densityDpi,
                        )
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) {
                        HostLog.d("surfaceChanged ${width}x${height}")
                        controller.resize(width, height, resources.displayMetrics.densityDpi)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        HostLog.d("surfaceDestroyed")
                        controller.detachSurface()
                    }
                })
            }
        },
        modifier = modifier,
        onRelease = { controller.detachSurface() },
    )
}

/**
 * 手势转发。
 *
 * 只做两件事：点击 → `input -d <id> tap`，拖拽 → `input -d <id> swipe`。
 *
 * **坐标不用换算**：`event.x/y` 是 View 的物理像素，而虚拟屏就是按这个 View 的
 * 像素尺寸建的（见 attach 传进去的 width/height），所以直接拿来当显示屏坐标即可。
 *
 * 没有做双指缩放：`input` 命令本身不支持 pinch，要做得多指注入得换成
 * 直接构造 MotionEvent（另一条路，本轮不做）。
 */
private class HostGestureTracker(private val controller: VdHostController) {

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var startMs = 0L
    private var moved = false
    private var active = false

    /**
     * 做成对象、而不是文件级的几个变量，是被真机教出来的：
     * SurfaceView 会被重建（Activity 重建、从投屏页切回来），新老两个 View
     * 会有一小段时间同时存在。共用一份全局状态时，老 View 的 DOWN 会污染
     * 新 View 的 UP，拼出一个坐标完全不对的"拖拽"并注入进去 ——
     * 日志里抓到过一条这种凭空出现的注入记录。
     * 每个 View 一份状态之后，这种串扰在结构上就不可能发生。
     */
    fun onTouch(view: android.view.View, event: android.view.MotionEvent): Boolean {
        // 只跟第一根手指：多指在这里没有意义（`input` 本来也不支持双指）。
        val pointerIndex = event.actionIndex.coerceIn(0, (event.pointerCount - 1).coerceAtLeast(0))
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                startX = event.getX(pointerIndex)
                startY = event.getY(pointerIndex)
                lastX = startX
                lastY = startY
                startMs = System.currentTimeMillis()
                moved = false
                active = true
            }

            android.view.MotionEvent.ACTION_MOVE -> {
                if (!active) return true
                lastX = event.getX(pointerIndex)
                lastY = event.getY(pointerIndex)
                if (distance() > view.touchSlop()) moved = true
            }

            android.view.MotionEvent.ACTION_UP -> {
                // 没有配对的 DOWN 就不认这次 UP，避免注入一个来路不明的点。
                if (!active) return true
                active = false
                if (moved || distance() > view.touchSlop()) {
                    controller.injectSwipe(
                        startX.toInt(),
                        startY.toInt(),
                        lastX.toInt(),
                        lastY.toInt(),
                        (System.currentTimeMillis() - startMs).toInt(),
                    )
                } else {
                    controller.injectTap(startX.toInt(), startY.toInt())
                }
            }

            android.view.MotionEvent.ACTION_CANCEL -> active = false
        }
        return true
    }

    private fun distance(): Double =
        Math.hypot((lastX - startX).toDouble(), (lastY - startY).toDouble())
}

private fun android.view.View.touchSlop(): Float =
    android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()

/**
 * 四种"被挡住"的提示页。
 *
 * 做成**不依赖 controller** 的公开组件，是为了让 debug 变体里的
 * 提示页预览（见 src/debug 下的 NoticePreviewActivity）能复用同一份渲染代码 ——
 * 预览用的必须是真代码，否则截出来的图没有说服力。
 */
@Composable
fun BlockedNotice(
    reason: BlockReason,
    onRefresh: () -> Unit = {},
    onRequestPermission: () -> Unit = {},
    onExit: () -> Unit = {},
) {
    when (reason) {
        BlockReason.ShizukuNotInstalled -> HostingNotice(
            title = "需要 Shizuku 才能托管其他应用",
            steps = listOf(
                "虚拟屏本身我们自己就能建，但把别的应用放到屏上、把触摸传进去需要系统级权限，普通应用拿不到。",
                "在设备上安装 Shizuku（官方渠道或 F-Droid 均可）。",
                "按 Shizuku 的引导用「无线调试 + 配对码」把服务启动起来。",
            ),
            note = "重启手机后 Shizuku 会停止运行，需要重新打开它点一次「启动」。",
            secondaryAction = "返回首页",
            onSecondaryAction = onExit,
        )

        BlockReason.ShizukuNotRunning -> HostingNotice(
            title = "Shizuku 未运行",
            steps = listOf(
                "打开设备上的 Shizuku 应用。",
                "点「启动」（首次使用需要先在 开发者选项 → 无线调试 里用配对码配对一次）。",
                "回到本应用，点下面的「重新检测」。",
            ),
            note = "重启手机后 Shizuku 会停止运行，需要重新打开它点一次「启动」。",
            primaryAction = "重新检测",
            onPrimaryAction = onRefresh,
            secondaryAction = "返回首页",
            onSecondaryAction = onExit,
        )

        BlockReason.ShizukuNotAuthorized -> HostingNotice(
            title = "Shizuku 已运行，但本应用还没被授权",
            steps = listOf(
                "点「请求授权」后会弹出 Shizuku 的授权窗口。",
                "请在弹窗里自己点「允许」—— 这一步只能由你确认，本应用不会、也无法代替你点击。",
                "授权后本页会自动继续；没看到弹窗就再点一次。",
            ),
            note = "当前 Shizuku 身份：${ShizukuGate.describeIdentity()}。重启手机后需重新点一次「启动」（已授予的授权通常会保留）。",
            primaryAction = "请求授权",
            onPrimaryAction = onRequestPermission,
            secondaryAction = "返回首页",
            onSecondaryAction = onExit,
        )

        is BlockReason.TargetMissing -> HostingNotice(
            title = "未安装${reason.label}",
            steps = listOf(
                "托管目标 ${reason.packageName} 不在设备上。",
                "安装后再回到本页即可，不需要重启本应用。",
            ),
            secondaryAction = "返回首页",
            onSecondaryAction = onExit,
        )
    }
}

private const val KEYCODE_BACK = 4
