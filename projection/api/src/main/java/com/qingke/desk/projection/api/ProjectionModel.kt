package com.qingke.desk.projection.api

import android.view.Surface

/**
 * 投屏种类。后续新增协议在此扩展枚举即可，不需要改接口形状。
 */
enum class ProjectionKind {
    CARPLAY,
    ANDROID_AUTO,
}

/**
 * 画面由谁渲染 —— 本接口的唯一一处"形态分叉"。
 *
 * EMBEDDED_SURFACE
 *   我方持有 Surface，视频流由 :projection:* 解码后写入，
 *   壳层能在 Compose 里自由排版画面（未来的混合形态：投屏 + 侧边条）。
 *
 * EXTERNAL_ACTIVITY
 *   画面由外部 Activity 全屏渲染（第一步：拉起已装的 DiPlay）。
 *   我方只负责触发与结果回收，拿不到视频 Surface，也拿不到输入事件。
 */
enum class ProjectionRenderMode {
    EMBEDDED_SURFACE,
    EXTERNAL_ACTIVITY,
}

/**
 * 会话能力自述。壳层据此决定渲染方式与是否显示"返回本机"按钮，
 * 避免在契约里写一堆 if (kind == ...) 的分支判断。
 */
data class ProjectionCapabilities(
    val renderMode: ProjectionRenderMode,
    val supportsTouchFeedback: Boolean,
    val supportsKeyboard: Boolean,
    val supportsVoiceButton: Boolean,
    val exclusiveAudio: Boolean,
    val requiresForegroundService: Boolean,
)

enum class ProjectionState {
    IDLE,
    PREPARING,
    CONNECTING,
    STREAMING,
    SUSPENDED,
    STOPPING,
    STOPPED,
    FAILED,
}

data class ProjectionError(
    val code: Code,
    val detail: String = "",
    val cause: Throwable? = null,
) {
    enum class Code {
        PEER_NOT_FOUND,
        AUTH_REJECTED,
        TRANSPORT_LOST,
        DECODER_FAILED,
        AUDIO_BUSY,
        PERMISSION_MISSING,
        /** 依赖的外部应用（阶段一的 DiPlay）不存在 / 不可拉起。 */
        EXTERNAL_APP_MISSING,
        /**
         * 承载无线投屏的 Wi-Fi 射频处于关闭状态。
         *
         * 单独一条而不是塞进 TRANSPORT_LOST：这条是**用户开关没有打开**，
         * 链路本身没坏，重试一万次也不会自己好；而 TRANSPORT_LOST 会触发自动重连，
         * 把这两种失败混在一起，界面就会在对牛弹琴地反复重试。
         */
        WIFI_DISABLED,
        UNSUPPORTED,
        UNKNOWN,
    }
}

/**
 * 提示页上的自助动作。
 *
 * 用枚举而不是直接把 Intent 塞进来：契约层（:projection:api）不该碰 Android 跳转，
 * 由 :shell 负责翻译成具体 Intent。也避免"缺 Wi-Fi"和"缺应用"这两种原因在界面上是两种写法。
 */
enum class AvailabilityAction {
    /** 打开系统 Wi-Fi 面板。本应用**绝不代用户打开开关**。 */
    OPEN_WIFI_SETTINGS,

    /** 打开外部接收端的应用详情页，供用户重新启用。 */
    OPEN_APP_DETAILS,
}

/**
 * 工厂可用性检查的结果。
 *
 * 用 sealed 而不是 Boolean：阶段一把画面交给外部应用，外部应用可能没装、
 * 被停用、Wi-Fi 没开或入口 Activity 不对。这些情况界面必须能讲清楚
 * "缺什么、为什么、怎么办"，布尔值只能吐出"不支持"三个字，用户看到只会以为程序坏了。
 */
sealed interface AvailabilityResult {

    /** 可以立即拉起。 */
    data object Ready : AvailabilityResult

    data class Unavailable(
        val code: ProjectionError.Code,
        /** 一句话结论，直接作为提示页标题。 */
        val headline: String,
        /** 为什么会这样。可以多行，界面按段落展示。 */
        val detail: String,
        /** 用户可以怎么补救。空串表示没有自助路径。 */
        val remedy: String = "",
        /**
         * 是否提供一个直接跳系统设置的自助按钮。
         * null 表示这一类问题没有可跳转的落点，只给文字说明。
         */
        val action: AvailabilityAction? = null,
        /** 结构化附加信息（如外部应用包名 / 版本号），供界面做深链或复制。 */
        val extras: Map<String, String> = emptyMap(),
    ) : AvailabilityResult
}

/**
 * 一次可用连接的地址。发现过程属于 :connection，
 * session 只接收已解析好的 endpoint，不参与发现。
 *
 * LAN_TCP        —— 终端与 iPhone 连同一个路由器（本项目的既定方案）
 * EXTERNAL_INTENT —— 交于外部实现（第一步的 DiPlay 拉起），host/port 无意义
 */
data class ProjectionEndpoint(
    val kind: ProjectionKind,
    val transport: Transport,
    val host: String = "",
    val port: Int = 0,
    val metadata: Map<String, String> = emptyMap(),
) {
    enum class Transport { LAN_TCP, EXTERNAL_INTENT }
}

/** 视频输出目标。坐标均为设备物理像素。 */
data class ProjectionVideoTarget(
    val surface: Surface,
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
)

/** 创建会话时的静态配置，通常在首次 attach 前一次性给定。 */
data class ProjectionConfig(
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val displayDensityDpi: Int,
    val nightMode: Boolean,
    val localeTags: List<String> = emptyList(),
)

/**
 * 归一化输入事件。坐标一律 0f..1f（相对画面左上），
 * 由 :projection:* 内部换算成真实 HID 报文，壳层不感知协议细节。
 */
sealed interface ProjectionInputEvent {
    val eventTimeMs: Long

    data class PointerDown(
        override val eventTimeMs: Long,
        val pointerId: Int,
        val x: Float,
        val y: Float,
    ) : ProjectionInputEvent

    data class PointerMove(
        override val eventTimeMs: Long,
        val pointerId: Int,
        val x: Float,
        val y: Float,
    ) : ProjectionInputEvent

    data class PointerUp(
        override val eventTimeMs: Long,
        val pointerId: Int,
        val x: Float,
        val y: Float,
    ) : ProjectionInputEvent

    /** 壳层夺回输入焦点时发出，确保对端释放所有触点。 */
    data class PointerCancel(override val eventTimeMs: Long) : ProjectionInputEvent

    data class Scroll(
        override val eventTimeMs: Long,
        val x: Float,
        val y: Float,
        val deltaY: Float,
    ) : ProjectionInputEvent

    data class KeyDown(override val eventTimeMs: Long, val keyCode: Int) : ProjectionInputEvent

    data class KeyUp(override val eventTimeMs: Long, val keyCode: Int) : ProjectionInputEvent
}

/** 会话向外的异步通知。错误与状态都要能驱动 UI，所以分开两条。 */
sealed interface ProjectionEvent {
    data class Error(val error: ProjectionError) : ProjectionEvent

    /** 视频尺寸变化，壳层据此调整 Surface 长宽比。 */
    data class VideoFormatChanged(val widthPx: Int, val heightPx: Int) : ProjectionEvent

    /** 独占音频焦点：true 表示本机音乐必须暂停（B3 既定策略）。 */
    data class AudioExclusiveChanged(val exclusive: Boolean) : ProjectionEvent

    /** 对端主动断开。区别于我方 stop()。 */
    data object DisconnectedByPeer : ProjectionEvent

    /** 用户在对端点Home/CarPlay 主界面已回到顶层。 */
    data object PeerRequestedExit : ProjectionEvent
}

enum class StopReason {
    USER_HOME_GESTURE,
    USER_EXPLICIT_EXIT,
    PEER_DISCONNECTED,
    TRANSPORT_LOST,
    APP_BACKGROUNDED,

    /**
     * 用户从外部应用（阶段一的 DiPlay）回到本应用。
     *
     * 单独一条而不是复用 USER_EXPLICIT_EXIT：这条是壳层"检测到回来了"被动推断的，
     * 不是用户点了退出按钮。后续要区分统计就得靠它，而且它**绝不触发自动重连** ——
     * 否则用户刚回来又被弹回投屏。
     */
    EXTERNAL_APP_RETURNED,
}
