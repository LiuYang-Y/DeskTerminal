package com.qingke.desk.connection

import com.qingke.desk.core.model.DisplayInfo
import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionConfig
import com.qingke.desk.projection.api.ProjectionEndpoint
import com.qingke.desk.projection.api.ProjectionError
import com.qingke.desk.projection.api.ProjectionEvent
import com.qingke.desk.projection.api.ProjectionKind
import com.qingke.desk.projection.api.ProjectionSession
import com.qingke.desk.projection.api.ProjectionSessionFactory
import com.qingke.desk.projection.api.ProjectionSessionRegistry
import com.qingke.desk.projection.api.ProjectionState
import com.qingke.desk.projection.api.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ConnectionUiState(
    val activeKind: ProjectionKind? = null,
    val phase: ConnectionPhase = ConnectionPhase.DISCONNECTED,
    val sessionState: ProjectionState = ProjectionState.IDLE,
    val retryAttempt: Int = 0,
    val lastError: ProjectionError? = null,
    /**
     * 非 Ready 表示"这次拉起被挡住了"（外部应用没装 / 未支持）。
     * 界面据此显示提示页而不是白屏转圈；它不会被自动重试覆盖。
     */
    val availability: AvailabilityResult = AvailabilityResult.Ready,

    /** 投屏是否要求独占音频；为 true 时本机音乐必须暂停（B3 既定策略）。 */
    val audioExclusive: Boolean = false,
)

enum class ConnectionPhase {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

object ReconnectPolicy {
    const val MAX_ATTEMPTS: Int = 6
    const val BASE_DELAY_MS: Long = 1_000L
    const val MAX_DELAY_MS: Long = 30_000L

    /** 指数退避：1s, 2s, 4s, 8s, 16s, 30s(封顶)。 */
    fun delayFor(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtLeast(0)
        val raw = BASE_DELAY_MS shl shift
        return raw.coerceAtMost(MAX_DELAY_MS)
    }
}

/**
 * 连接管理。核心职责是"同一时刻只允许存在一个会话"，以及链路断裂后的自动重连。
 *
 * 它不参与设备发现（发现策略待定：同 LAN 的 mDNS 广播或手动配对），
 * 目前直接向 registry 取首选工厂。
 *
 * 注意：对端主动断开（DisconnectedByPeer）不重连 —— 那是用户在对端点的退出，
 * 重连只会把界面弹回去。只有 TRANSPORT_LOST 才重连。
 */
class ConnectionOrchestrator(
    private val registry: ProjectionSessionRegistry,
    /**
     * 屏幕信息用提供者而不是一次性传值：分辨率在 Activity attach 之后才拿得到，
     * 而 orchestrator 通常在 Application 期就建好了。
     */
    private val displayInfoProvider: () -> DisplayInfo,
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    private val _activeSession = MutableStateFlow<ProjectionSession?>(null)

    /** 当前会话。UI 用它读 capabilities，不直接调生命周期方法。 */
    val activeSession: StateFlow<ProjectionSession?> = _activeSession.asStateFlow()

    private var observer: Job? = null
    private var reconnect: Job? = null

    fun connect(kind: ProjectionKind) {
        val factory = registry.find(kind)
        if (factory == null) {
            fail(kind, ProjectionError.Code.UNSUPPORTED, "未注册 $kind 的实现")
            return
        }

        // 拉起之前先判决能不能拉。被挡住时不进任何重连逻辑 —— 错不在链路，
        // 重连一万次也只是让提示页闪得好看一点。
        val availability = factory.checkAvailability()
        if (availability !is AvailabilityResult.Ready) {
            stopReconnect()
            teardownSession()
            fail(
                kind = kind,
                code = (availability as AvailabilityResult.Unavailable).code,
                detail = availability.headline,
                availability = availability,
            )
            return
        }

        stopReconnect()
        teardownSession()

        val created: ProjectionSession = factory.create()
        _activeSession.value = created

        observer = scope.launch {
            launch {
                created.state.collect { value ->
                    _uiState.update { it.copy(sessionState = value) }
                }
            }
            launch {
                created.events.collect { event -> handleEvent(kind, event) }
            }
        }

        scope.launch {
            _uiState.update {
                it.copy(
                    activeKind = kind,
                    phase = ConnectionPhase.CONNECTING,
                    retryAttempt = 0,
                    lastError = null,
                    availability = AvailabilityResult.Ready,
                )
            }
            val prepared = created.prepare(buildConfig())
            if (prepared.isFailure) {
                // prepare 失败（比如外部应用在这一瞬间被卸掉）就不该往下 start ——
                // 否则会在一个已经 FAILED 的会话上再打一次 Intent。
                val throwable = prepared.exceptionOrNull() ?: IllegalStateException("prepare 失败")
                _uiState.update {
                    it.copy(
                        phase = ConnectionPhase.DISCONNECTED,
                        lastError = ProjectionError(
                            code = ProjectionError.Code.EXTERNAL_APP_MISSING,
                            detail = throwable.message.orEmpty(),
                            cause = throwable,
                        ),
                    )
                }
                return@launch
            }

            created.start(
                ProjectionEndpoint(
                    kind = kind,
                    transport = ProjectionEndpoint.Transport.LAN_TCP,
                )
            ).onSuccess {
                _uiState.update { it.copy(phase = ConnectionPhase.CONNECTED) }
            }.onFailure { throwable ->
                val code = if (throwable is UnsupportedOperationException) {
                    ProjectionError.Code.UNSUPPORTED
                } else {
                    ProjectionError.Code.TRANSPORT_LOST
                }
                _uiState.update {
                    it.copy(
                        phase = ConnectionPhase.DISCONNECTED,
                        lastError = ProjectionError(
                            code = code,
                            detail = throwable.message.orEmpty(),
                            cause = throwable,
                        ),
                    )
                }
                // 外部应用拉不起来这类"环境错误"，退避重试也没有意义 ——
                // 用户得先去把应用装上 / 启用。只有链路类失败才值得自己重试。
                if (code == ProjectionError.Code.TRANSPORT_LOST) {
                    scheduleReconnect(kind)
                }
            }
        }
    }

    fun disconnect(reason: StopReason = StopReason.USER_EXPLICIT_EXIT) {
        stopReconnect()
        val current = _activeSession.value ?: return
        scope.launch {
            current.stop(reason)
            current.release()
            _activeSession.value = null
            _uiState.value = ConnectionUiState(
                phase = ConnectionPhase.DISCONNECTED,
                sessionState = ProjectionState.STOPPED,
            )
        }
    }

    fun retryNow() {
        uiState.value.activeKind?.let(::connect)
    }

    private fun handleEvent(kind: ProjectionKind, event: ProjectionEvent) {
        when (event) {
            is ProjectionEvent.Error -> {
                _uiState.update { it.copy(lastError = event.error) }
                if (event.error.code == ProjectionError.Code.TRANSPORT_LOST) {
                    scheduleReconnect(kind)
                }
            }

            ProjectionEvent.DisconnectedByPeer -> Unit

            ProjectionEvent.PeerRequestedExit -> Unit

            is ProjectionEvent.AudioExclusiveChanged -> {
                _uiState.update { it.copy(audioExclusive = event.exclusive) }
            }

            is ProjectionEvent.VideoFormatChanged -> Unit
        }
    }

    private fun fail(
        kind: ProjectionKind,
        code: ProjectionError.Code,
        detail: String,
        availability: AvailabilityResult = AvailabilityResult.Ready,
    ) {
        _uiState.update {
            it.copy(
                activeKind = kind,
                phase = ConnectionPhase.DISCONNECTED,
                lastError = ProjectionError(code, detail),
                availability = availability,
            )
        }
    }

    private fun scheduleReconnect(kind: ProjectionKind) {
        if (reconnect?.isActive == true) return
        reconnect = scope.launch {
            var attempt = _uiState.value.retryAttempt
            while (attempt < ReconnectPolicy.MAX_ATTEMPTS) {
                attempt += 1
                _uiState.update {
                    it.copy(phase = ConnectionPhase.RECONNECTING, retryAttempt = attempt)
                }
                delay(ReconnectPolicy.delayFor(attempt))
                connect(kind)
                if (_uiState.value.phase == ConnectionPhase.CONNECTED) return@launch
            }
            _uiState.update { it.copy(phase = ConnectionPhase.DISCONNECTED) }
        }
    }

    private fun stopReconnect() {
        reconnect?.cancel()
        reconnect = null
    }

    private fun teardownSession() {
        observer?.cancel()
        observer = null
        _activeSession.value = null
    }

    private fun buildConfig(): ProjectionConfig {
        val info = displayInfoProvider()
        return ProjectionConfig(
            displayWidthPx = info.widthPx,
            displayHeightPx = info.heightPx,
            displayDensityDpi = info.densityDpi,
            nightMode = info.nightMode,
        )
    }
}
