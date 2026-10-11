package com.qingke.desk.hosting

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.view.Surface
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 界面侧到 [VdHostService] 的把手。
 *
 * 存在的意义是把"绑定 + 状态转发 + 未绑定时的调用缓冲"收在一处：
 * Compose 里只需要一个 [state] 和几个方法，不用关心 ServiceConnection 的时序。
 *
 * 关键时序问题：**SurfaceView 的 surfaceCreated 可能早于 Service 绑定完成**
 * （Activity 启动那一帧两者是并发的）。所以 attach 会被暂存成 [pendingAttach]，
 * 绑定完成后补做；否则第一次进地图页会永远停在"正在启动"。
 */
class VdHostController(
    private val context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // 日志落盘要趁早：Service 起来之前（绑定、授权这些环节）的日志同样值得看，
        // 而这台设备的 logcat 抓不到应用日志，只能靠文件。
        HostLog.init(appContext)
    }

    private var service: VdHostService? = null
    private var connection: ServiceConnection? = null
    private var forwardJob: Job? = null

    private data class AttachRequest(
        val surface: Surface,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
    )

    private var pendingAttach: AttachRequest? = null

    private val _state = MutableStateFlow<HostState>(HostState.Idle)
    val state: StateFlow<HostState> = _state.asStateFlow()

    /** 界面进入托管页时调用。Service 已经活着就只做绑定。 */
    fun bind() {
        if (connection != null) return
        val intent = Intent(appContext, VdHostService::class.java)
        runCatching {
            ContextCompat.startForegroundService(appContext, intent)
        }.onFailure {
            HostLog.e("startForegroundService failed", it)
        }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val svc = (binder as? VdHostService.LocalBinder)?.service ?: return
                service = svc
                HostLog.i("VdHostService connected")
                forwardJob?.cancel()
                forwardJob = scope.launch {
                    svc.state.collect { _state.value = it }
                }
                pendingAttach?.let {
                    pendingAttach = null
                    svc.attach(it.surface, it.width, it.height, it.densityDpi)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                HostLog.w("VdHostService disconnected")
                service = null
                forwardJob?.cancel()
            }
        }
        connection = conn
        appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    /** 界面离开托管页：只解绑，不杀服务 —— 切到投屏再切回来还要接着用。 */
    fun unbind() {
        val conn = connection ?: return
        connection = null
        forwardJob?.cancel()
        forwardJob = null
        service = null
        runCatching { appContext.unbindService(conn) }
            .onFailure { HostLog.w("unbind failed: ${it.javaClass.simpleName}") }
    }

    fun attach(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        val svc = service
        if (svc == null) {
            pendingAttach = AttachRequest(surface, width, height, densityDpi)
            HostLog.i("attach buffered until service binds")
            return
        }
        svc.attach(surface, width, height, densityDpi)
    }

    fun detachSurface() {
        pendingAttach = null
        service?.detachSurface()
    }

    fun resize(width: Int, height: Int, densityDpi: Int) {
        service?.resize(width, height, densityDpi)
    }

    fun injectTap(x: Int, y: Int) = service?.injectTap(x, y)
    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int) =
        service?.injectSwipe(x1, y1, x2, y2, durationMs)
    fun injectKey(code: Int) = service?.injectKey(code)

    /**
     * 重新查 Shizuku。由"回到前台"和"授权结果回来"两个时点驱动。
     */
    fun refresh() {
        service?.refreshShizuku()
    }

    fun retryNow() {
        service?.retryNow()
    }

    /** 收工：关掉虚拟屏并停掉服务。下次进地图页会重新建。 */
    fun stopHosting() {
        service?.stopHosting() ?: run {
            val intent = Intent(appContext, VdHostService::class.java)
            appContext.stopService(intent)
        }
        _state.value = HostState.Idle
    }

    /**
     * 发起 Shizuku 授权。**只把弹窗推出来**，弹窗里的按钮由用户自己点 ——
     * 代码不做、也不能做任何"代点"。结果通过 [ShizukuGate] 的结果监听器回来。
     */
    fun requestShizukuPermission() {
        ShizukuGate.requestPermission()
    }
}
