package com.qingke.desk.hosting

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 虚拟屏的持有者。**虚拟屏必须活在这里，不能活在任何 Activity 里。**
 *
 * 这个决定是被真机逼出来的：Q1 探针阶段，一个没锁横屏、没声明 configChanges 的
 * Activity 在拉起高德后发生重建，它建的四块虚拟屏 id 全部改变
 * （32-35 → 36-39），而外部仍拿着旧 id 发 `am start --display`，于是画面丢在哪儿都不知道。
 * 放进 Service 之后：
 *   · Activity 重建 / 旋转 / 切后台再回来 —— 屏还是那一块，id 不变；
 *   · Activity 只是暂时离开（用户切到投屏页）时，[detachSurface] 把 Surface 摘掉，
 *     屏和目标应用都留着，切回来直接重挂，**不用重新拉起**。
 *
 * 另一个职责是"屏没了要自己补回来"：息屏、目标应用自己作了什么、系统回收，
 * 都会让虚拟屏消失（实测过）。这里监听 [DisplayManager.DisplayListener] 的
 * onDisplayRemoved，自动重建；重试 [MAX_ATTEMPTS] 次仍失败就转 [HostState.Failed]，
 * 由界面把用户送回首页，绝不留下一个黑屏没人管的界面。
 *
 * 权限边界（Q1 真机结论，别再凭文档猜）：
 *   · **建屏不需要 Shizuku** —— 普通应用调用 createVirtualDisplay 就能成功，
 *     只是拿到的屏一律被降级为 PRIVATE|OWN_CONTENT_ONLY、touch=NONE、canHostTasks=false；
 *   · **把别人的 App 放上去、往屏里注入触摸需要 shell 权限** —— 应用自己调
 *     setLaunchDisplayId 会被系统拒（Permission Denial），只有 `am start --display`
 *     和 `input -d` 这种 shell 命令能成。
 * 所以"建屏"在这里做，"拉起 / 注入"一律走 [ShellPort] 交给 Shizuku。
 */
class VdHostService : Service() {

    inner class LocalBinder : Binder() {
        val service: VdHostService get() = this@VdHostService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * 注入触摸用的单线程调度器。
     * 每条 `input -d` 都要过一次 binder 到 shell 进程再 fork 一次 app_process，
     * 并行发会出现后发先到、坐标乱序；串行虽然慢一点，但顺序是对的。
     */
    private val inputDispatcher = Dispatchers.IO.limitedParallelism(1)

    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private var shellPort: ShellPort? = null

    private var virtualDisplay: VirtualDisplay? = null
    private var displayId: Int = Display.INVALID_DISPLAY
    private var surface: Surface? = null
    private var width: Int = 0
    private var height: Int = 0
    private var densityDpi: Int = 0
    private var target: HostTarget = HostTargets.AMAP

    private var attempt: Int = 0
    private var releasing: Boolean = false
    private var stopped: Boolean = false

    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    /** 当前屏 id。用于日志和排查，界面不要依赖它 —— 它会变。 */
    val currentDisplayId: Int get() = displayId

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        HostLog.init(this)
        HostLog.i("VdHostService created")
        promoteToForeground()
        displayManager.registerDisplayListener(displayListener, mainHandler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 进程被杀后不要自动重建：Surface 早没了，重建出来也是一块没人看的黑屏。
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        HostLog.i("VdHostService destroy")
        stopped = true
        scope.cancel()
        runCatching { displayManager.unregisterDisplayListener(displayListener) }
        releaseDisplay()
        shellPort?.close()
        shellPort = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 外部调用

    /**
     * 把一块 Surface 挂上来。首次调用会触发建屏 + 拉起目标应用；
     * 之后（例如用户从投屏页切回来）只是重挂，不会重新拉起。
     */
    fun attach(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        if (stopped) return
        HostLog.i("attach surface ${width}x${height}@${densityDpi}")
        this.surface = surface
        this.width = width
        this.height = height
        this.densityDpi = densityDpi

        virtualDisplay?.let { vd ->
            if (vd.display.displayId == displayId) {
                runCatching {
                    vd.resize(width, height, densityDpi)
                    vd.setSurface(surface)
                }.onFailure { HostLog.w("re-attach surface failed: ${it.javaClass.simpleName}") }
                HostLog.i("re-attached to existing display $displayId")
                return
            }
        }

        if (displayId == Display.INVALID_DISPLAY || virtualDisplay == null) {
            attempt = 0
            startOrRebuild("首次建屏")
        }
    }

    /**
     * SurfaceView 尺寸变了。改虚拟屏尺寸而不是重建 —— 重建会换 id，
     * 已经跑在上面的应用要整个重新拉起，代价大得多。
     */
    fun resize(width: Int, height: Int, densityDpi: Int) {
        if (width <= 0 || height <= 0) return
        if (width == this.width && height == this.height && densityDpi == this.densityDpi) return
        HostLog.i("resize ${this.width}x${this.height} -> ${width}x${height}@${densityDpi}")
        this.width = width
        this.height = height
        this.densityDpi = densityDpi
        virtualDisplay?.let { vd ->
            runCatching { vd.resize(width, height, densityDpi) }
                .onFailure { HostLog.w("resize failed: ${it.javaClass.simpleName}") }
        }
    }

    /**
     * Surface 被销毁（Activity 重建 / 用户切到别的页）。
     * **屏和目标应用都保留**，只是不再有画面输出，切回来直接重挂。
     */
    fun detachSurface() {
        HostLog.i("detach surface")
        surface = null
        virtualDisplay?.let { vd ->
            runCatching { vd.setSurface(null) }
                .onFailure { HostLog.w("setSurface(null) failed: ${it.javaClass.simpleName}") }
        }
    }

    /** 用户或界面要求重新来一次。 */
    fun retryNow() {
        attempt = 0
        startOrRebuild("手动重试")
    }

    /**
     * 重新查一次 Shizuku 状态；从"被挡住"变成"可用"时自动往下走。
     * 用户在别处启动了服务、或刚在弹窗里点完允许，都会走到这里。
     */
    fun refreshShizuku() {
        if (stopped) return
        val status = ShizukuGate.status(this)
        HostLog.i("refresh shizuku -> $status")
        if (status != ShizukuStatus.READY) {
            _state.value = HostState.Blocked(reasonFor(status))
            return
        }
        when (_state.value) {
            is HostState.Blocked, is HostState.Failed -> {
                attempt = 0
                startOrRebuild("Shizuku 恢复可用")
            }

            else -> Unit
        }
    }

    /** 彻底收工：释放屏，服务自己也停掉。 */
    fun stopHosting() {
        HostLog.i("stop hosting")
        stopped = true
        releaseDisplay()
        _state.value = HostState.Idle
        shellPort?.close()
        shellPort = null
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    // ---------------------------------------------------------------- 触摸注入

    fun injectTap(x: Int, y: Int) {
        inject("/system/bin/input -d $displayId tap $x $y")
    }

    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) {
        val duration = durationMs.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS)
        inject("/system/bin/input -d $displayId swipe $x1 $y1 $x2 $y2 $duration")
    }

    fun injectKey(code: Int) {
        inject("/system/bin/input -d $displayId keyevent $code")
    }

    private fun inject(command: String) {
        val id = displayId
        if (id == Display.INVALID_DISPLAY) {
            HostLog.w("inject dropped: no display ($command)")
            return
        }
        scope.launch(inputDispatcher) {
            // 成功也记一笔：真机上排查"点了没反应"时，先要能分清
            // 是事件没到注入这一层，还是注入了但对面没响应。
            val result = ensureShell().exec(command)
            if (result.isSuccess) {
                HostLog.d("inject ok: $command")
            } else {
                HostLog.w("inject failed: ${result.summary(200)}")
            }
        }
    }

    // ---------------------------------------------------------------- 建屏与拉起

    private fun startOrRebuild(reason: String) {
        if (stopped) return
        val next = attempt + 1
        if (next > MAX_ATTEMPTS) {
            HostLog.e("give up: $reason (already $MAX_ATTEMPTS attempts)")
            _state.value = HostState.Failed("虚拟屏反复消失，已重试 $MAX_ATTEMPTS 次仍未恢复（$reason）")
            return
        }
        attempt = next
        scope.launch {
            HostLog.i("start/rebuild #$next: $reason")
            if (next > 1) delay(REBUILD_DELAY_MS)
            buildAndLaunch()
        }
    }

    private suspend fun buildAndLaunch() {
        val status = ShizukuGate.status(this)
        if (status != ShizukuStatus.READY) {
            HostLog.w("blocked: $status")
            _state.value = HostState.Blocked(reasonFor(status))
            return
        }
        if (!target.isInstalled(this)) {
            HostLog.w("target missing: ${target.packageName}")
            _state.value = HostState.Blocked(
                BlockReason.TargetMissing(target.label, target.packageName),
            )
            return
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            // 还没有可用的 Surface（例如 Activity 正在重建）。
            // 停在 Starting，等 attach() 把尺寸带过来。
            HostLog.w("no surface size yet, stay in Starting")
            _state.value = HostState.Starting(displayId, attempt)
            return
        }

        releaseDisplay()

        val vd = try {
            displayManager.createVirtualDisplay(
                VD_NAME,
                width,
                height,
                densityDpi,
                surface,
                VD_FLAGS,
                null,
                mainHandler,
            )
        } catch (t: Throwable) {
            HostLog.e("createVirtualDisplay failed", t)
            // 异常文本里通常直接写着"缺哪张权限 / 该用哪个 flag"，比只报类名有用得多。
            val detail = t.message?.take(180) ?: t.javaClass.simpleName
            _state.value = HostState.Failed("创建虚拟屏失败：$detail")
            return
        }

        virtualDisplay = vd
        displayId = vd.display.displayId
        HostLog.i("virtual display created: id=$displayId ${width}x${height}@${densityDpi}")
        _state.value = HostState.Starting(displayId, attempt)

        val command = "/system/bin/am start --display $displayId -n ${target.component}"
        val result = ensureShell().exec(command)
        HostLog.i("am start -> ${result.summary()}")
        if (!result.isSuccess) {
            _state.value = HostState.Failed("拉起${target.label}失败：${result.summary(160)}")
            return
        }

        // am start 返回成功不代表画面已经出来了（进程还要起来、首帧还要合成），
        // 这里给它一点时间，免得界面刚亮就切到 Running 露出一块黑。
        delay(FIRST_FRAME_GRACE_MS)
        if (_state.value is HostState.Starting) {
            _state.value = HostState.Running(displayId, attempt)
            HostLog.i("running on display $displayId")
        }
    }

    private fun releaseDisplay() {
        releasing = true
        runCatching { virtualDisplay?.release() }
            .onFailure { HostLog.w("release failed: ${it.javaClass.simpleName}") }
        virtualDisplay = null
        displayId = Display.INVALID_DISPLAY
        releasing = false
    }

    private fun ensureShell(): ShellPort {
        return shellPort ?: ShellPort(applicationContext).also { shellPort = it }
    }

    private fun reasonFor(status: ShizukuStatus): BlockReason = when (status) {
        ShizukuStatus.NOT_INSTALLED -> BlockReason.ShizukuNotInstalled
        ShizukuStatus.NOT_RUNNING -> BlockReason.ShizukuNotRunning
        ShizukuStatus.NOT_AUTHORIZED -> BlockReason.ShizukuNotAuthorized
        ShizukuStatus.READY -> BlockReason.ShizukuNotRunning
    }

    // ---------------------------------------------------------------- 屏消失检测

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(removedId: Int) {
            // 自己释放时也会走到这里，别把它当成"屏被系统干掉了"。
            if (releasing || stopped) return
            if (removedId != displayId) return
            HostLog.w("display $removedId removed -> rebuild")
            virtualDisplay = null
            displayId = Display.INVALID_DISPLAY
            startOrRebuild("虚拟屏 $removedId 消失")
        }

        override fun onDisplayChanged(displayId: Int) = Unit
    }

    // ---------------------------------------------------------------- 前台服务

    private fun promoteToForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "应用托管",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "把第三方应用画进本应用界面时的常驻通知" },
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("正在托管 ${target.label}")
            .setContentText("虚拟屏画面输出到本应用主界面")
            // 框架自带的通用图标：进程内没有自己的 launcher icon 资源，
            // 用 android.R 里的公开 drawable，避免引用私密资源编不过。
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        }.onFailure {
            // 起不来也不能崩：没有前台资格时虚拟屏照样能用，只是更容易被回收。
            HostLog.e("startForeground failed", it)
        }
    }

    private companion object {
        const val VD_NAME = "DeskTerminal:hosting"

        /**
         * 必须是 OWN_CONTENT_ONLY，**不能**用 PUBLIC。
         *
         * 这一条是被真机打回来的：带上 PUBLIC(=1) 时 createVirtualDisplay 直接抛
         *   SecurityException: Requires ADD_MIRROR_DISPLAY, CAPTURE_VIDEO_OUTPUT or
         *   CAPTURE_SECURE_VIDEO_OUTPUT permission ... please use the flag
         *   VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY.
         * 也就是说：普通应用建的屏，只有在**明确声明"我不做屏幕镜像"**时才被允许，
         * 否则系统会把它当成投屏来要 MediaProjection 级别的权限。
         *
         * OWN_CONTENT_ONLY 的语义正好是我们要的：屏上只显示被显式放上去的内容
         * （也就是后面用 `am start --display` 拉上去的那个应用），不去镜像主屏。
         *
         * 补充事实：**应用建的屏一律会被系统降级**（PRIVATE|OWN_CONTENT_ONLY、
         * touch=NONE、canHostTasks=false，连 TRUSTED 都不会给），所以
         * "把别人的 Activity 放上去"这条路依然只有 shell（Shizuku）走得通。
         */
        const val VD_FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        const val MAX_ATTEMPTS = 3
        const val REBUILD_DELAY_MS = 600L
        const val FIRST_FRAME_GRACE_MS = 700L

        const val MIN_SWIPE_MS = 50
        const val MAX_SWIPE_MS = 1200

        const val CHANNEL_ID = "desk_hosting"
        const val NOTIFICATION_ID = 9101
    }
}
