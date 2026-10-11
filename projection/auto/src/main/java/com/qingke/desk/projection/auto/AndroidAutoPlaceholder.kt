package com.qingke.desk.projection.auto

import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionCapabilities
import com.qingke.desk.projection.api.ProjectionError
import com.qingke.desk.projection.api.ProjectionKind
import com.qingke.desk.projection.api.ProjectionRenderMode
import com.qingke.desk.projection.api.ProjectionSession
import com.qingke.desk.projection.api.ProjectionSessionFactory

/**
 * Android Auto 接收端占位。
 *
 * 语义（用户确认）：终端当车机，接收 Android 手机投屏，即 head unit 协议，
 * 与 CarPlay 接收端对称，是另一套完整实现，不在本轮范围。
 *
 * 占位策略：checkAvailability() 恒为 Unavailable，壳层因此会显示"尚未支持"的提示页
 * 而不是强行走 unfounded 的路径；create() 直接抛异常，避免有人绕过检查拿到半成品实例。
 *
 * TODO(step-N): 实现 AndroidAutoSession 后，把 capabilities 改成真实自述。
 */
object AndroidAutoSessionFactory : ProjectionSessionFactory {

    override val kind: ProjectionKind = ProjectionKind.ANDROID_AUTO

    override val capabilities: ProjectionCapabilities = ProjectionCapabilities(
        renderMode = ProjectionRenderMode.EMBEDDED_SURFACE,
        supportsTouchFeedback = false,
        supportsKeyboard = false,
        supportsVoiceButton = false,
        exclusiveAudio = true,
        requiresForegroundService = true,
    )

    override fun checkAvailability(): AvailabilityResult = AvailabilityResult.Unavailable(
        code = ProjectionError.Code.UNSUPPORTED,
        headline = "Android Auto 接收端尚未实现",
        detail = "本轮只做 CarPlay 接收端（iPhone 投进来）。Android Auto 的 head unit 协议" +
            "是另一套完整握手 + 编解码实现，排在阶段安排的后段。",
        remedy = "先用 iPhone 验证 CarPlay 链路；Android Auto 会在后续阶段补上。",
    )

    override fun create(): ProjectionSession = throw UnsupportedOperationException(
        "Android Auto 接收端尚未实现。本类只是 ProjectionSession 契约的预留点位。"
    )
}
