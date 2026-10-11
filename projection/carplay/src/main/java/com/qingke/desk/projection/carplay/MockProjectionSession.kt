package com.qingke.desk.projection.carplay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Surface
import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionCapabilities
import com.qingke.desk.projection.api.ProjectionConfig
import com.qingke.desk.projection.api.ProjectionEndpoint
import com.qingke.desk.projection.api.ProjectionEvent
import com.qingke.desk.projection.api.ProjectionInputEvent
import com.qingke.desk.projection.api.ProjectionKind
import com.qingke.desk.projection.api.ProjectionRenderMode
import com.qingke.desk.projection.api.ProjectionSession
import com.qingke.desk.projection.api.ProjectionSessionFactory
import com.qingke.desk.projection.api.ProjectionState
import com.qingke.desk.projection.api.ProjectionVideoTarget
import com.qingke.desk.projection.api.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * 模拟投屏工厂。
 *
 * 真连线由 [ExternalDiPlaySessionFactory] 负责；本类只用于**不开 DiPlay 也能自测** ：
 * 单独自矛盾的界面形态、防烧屏、栅格重排在投屏态下的表现，都要靠它复现。
 *
 * 切换 renderMode 常量即可验证两条界面路径：
 *
 *   EMBEDDED_SURFACE   -> 我方 Surface 出模拟画面，逃生手势生效（阶段二的形态）
 *   EXTERNAL_ACTIVITY  -> 画面由外部应用渲染，逃生手势不生效（阶段一的形态，已知限制）
 *
 * TODO(step-2): 接 DiPlay 源码后，由 EmbeddedCarPlaySessionFactory 取代本类。
 */
class MockCarPlaySessionFactory(
    private val renderMode: ProjectionRenderMode = MockCarPlayConfig.RENDER_MODE,
) : ProjectionSessionFactory {

    override val kind: ProjectionKind = ProjectionKind.CARPLAY

    override val capabilities: ProjectionCapabilities = when (renderMode) {
        ProjectionRenderMode.EMBEDDED_SURFACE -> ProjectionCapabilities(
            renderMode = ProjectionRenderMode.EMBEDDED_SURFACE,
            supportsTouchFeedback = true,
            supportsKeyboard = true,
            supportsVoiceButton = true,
            exclusiveAudio = true,
            requiresForegroundService = false,
        )

        ProjectionRenderMode.EXTERNAL_ACTIVITY -> ProjectionCapabilities(
            renderMode = ProjectionRenderMode.EXTERNAL_ACTIVITY,
            supportsTouchFeedback = false,
            supportsKeyboard = false,
            supportsVoiceButton = false,
            exclusiveAudio = true,
            requiresForegroundService = true,
        )
    }

    override fun checkAvailability(): AvailabilityResult = AvailabilityResult.Ready

    override fun create(): ProjectionSession = MockCarPlaySession(
        id = "mock-carplay",
        capabilities = capabilities,
    )
}

/**
 * 调参开关集中在这里，方便一键切换验证路径。
 */
object MockCarPlayConfig {

    /**
     * 默认 EMBEDDED_SURFACE —— Surface 链路 / 逃生手势的自测形态。
     *
     * 要让 shell 真的用上本类（而不是 [ExternalDiPlaySessionFactory]），
     * 把 DeskGraph 里本工厂挪到列表第一位即可：registry.find() 取同 kind 的第一个。
     */
    val RENDER_MODE: ProjectionRenderMode = ProjectionRenderMode.EMBEDDED_SURFACE

    /** 模拟握手与出流的耗时，让状态流转在界面上看得见。 */
    const val PREPARE_DELAY_MS: Long = 400L
    const val CONNECT_DELAY_MS: Long = 900L
}

/**
 * 模拟会话。状态机与真实实现保持一致，只是把 transport 换成了定时器。
 */
class MockCarPlaySession(
    override val id: String,
    override val capabilities: ProjectionCapabilities,
) : ProjectionSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ProjectionState.IDLE)
    override val state: StateFlow<ProjectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProjectionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ProjectionEvent> = _events.asSharedFlow()

    override val kind: ProjectionKind = ProjectionKind.CARPLAY

    private var prepared = false
    private var target: ProjectionVideoTarget? = null
    private var painter: Job? = null
    private var frameIndex = 0

    override suspend fun prepare(config: ProjectionConfig): Result<Unit> {
        _state.value = ProjectionState.PREPARING
        delay(MockCarPlayConfig.PREPARE_DELAY_MS)
        prepared = true
        _state.value = ProjectionState.IDLE
        return Result.success(Unit)
    }

    override suspend fun start(endpoint: ProjectionEndpoint): Result<Unit> {
        if (!prepared) return Result.failure(
            IllegalStateException("start() before prepare()")
        )
        _state.value = ProjectionState.CONNECTING
        delay(MockCarPlayConfig.CONNECT_DELAY_MS)
        _state.value = ProjectionState.STREAMING
        _events.emit(ProjectionEvent.AudioExclusiveChanged(exclusive = true))
        _events.emit(
            ProjectionEvent.VideoFormatChanged(
                widthPx = target?.widthPx ?: 0,
                heightPx = target?.heightPx ?: 0,
            )
        )
        return Result.success(Unit)
    }

    override suspend fun stop(reason: StopReason): Result<Unit> {
        _state.value = ProjectionState.STOPPING
        stopPainter()
        _events.emit(ProjectionEvent.AudioExclusiveChanged(exclusive = false))
        _state.value = ProjectionState.STOPPED
        return Result.success(Unit)
    }

    override suspend fun release() {
        stopPainter()
        scope.cancel()
        _state.value = ProjectionState.STOPPED
    }

    override fun attachVideoSurface(target: ProjectionVideoTarget) {
        requireEmbedded()
        this.target = target
        startPainter(target)
    }

    override fun detachVideoSurface() {
        stopPainter()
        target = null
    }

    override fun sendInput(event: ProjectionInputEvent) {
        requireEmbedded()
        // 模拟实现只把事件落到日志，真实实现在这里编码成 HID 报文回传。
        android.util.Log.d("MockCarPlay", "input -> $event")
    }

    override fun requestPeerHome(): Boolean {
        requireEmbedded()
        return true
    }

    // ---------- 内部 ----------

    private fun requireEmbedded() {
        check(capabilities.renderMode == ProjectionRenderMode.EMBEDDED_SURFACE) {
            "EXTERNAL_ACTIVITY 会话由外部应用渲染画面，无法操作 Surface / 回传输入。" +
                "这是阶段一（拉起已装 DiPlay）的已知限制。"
        }
    }

    private fun startPainter(target: ProjectionVideoTarget) {
        if (painter?.isActive == true) return
        val surface = target.surface
        painter = scope.launch {
            while (isActive) {
                drawFrame(surface, target.widthPx, target.heightPx)
                frameIndex++
                delay(1000L / 30)
            }
        }
    }

    private fun stopPainter() {
        painter?.cancel()
        painter = null
    }

    private fun drawFrame(surface: Surface, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val canvas: Canvas = surface.lockCanvas(null) ?: return
        try {
            canvas.drawColor(Color.rgb(16, 18, 24))

            val centerX = width / 2f
            val centerY = height / 2f
            val radius = (width.coerceAtMost(height) * 0.12f)
            val phase = (frameIndex % 60) / 60f * 2f * PI.toFloat()
            val offsetX = sin(phase.toDouble()).toFloat() * radius * 1.6f

            val block = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(90, 120, 255)
            }
            canvas.drawRoundRect(
                centerX + offsetX - radius,
                centerY - radius * 0.5f,
                centerX + offsetX + radius,
                centerY + radius * 0.5f,
                radius * 0.25f,
                radius * 0.25f,
                block,
            )

            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(226, 232, 245)
                textAlign = Paint.Align.CENTER
                textSize = width.coerceAtMost(height) * 0.035f
            }
            canvas.drawText("模拟投屏 · 未接入 DiPlay", centerX, centerY + radius * 1.8f, text)
            canvas.drawText("第 $frameIndex 帧", centerX, centerY + radius * 2.6f, text)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }
}
