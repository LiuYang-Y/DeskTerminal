package com.qingke.desk.projection.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 投屏会话统一契约。
 *
 * 设计约束：
 *  1. 不参与设备发现。发现 / 鉴权 / 仲裁在 :connection，session 只消费已解析好的 endpoint。
 *  2. 不绑定 UI 框架。视频出口只有 Surface，Compose 侧由 :shell 用 AndroidView 承接。
 *  3. 允许存在"能力退化"的实现（EXTERNAL_ACTIVITY）。不支持的方法显式抛
 *     UnsupportedOperationException，绝不静默吞掉调用。
 *
 * 生命周期：prepare -> start -> (attachVideoSurface) -> stop/release
 */
interface ProjectionSession {

    /** 人类可读的实例标识，用于日志与状态面板。 */
    val id: String

    val kind: ProjectionKind

    val capabilities: ProjectionCapabilities

    val state: StateFlow<ProjectionState>

    val events: SharedFlow<ProjectionEvent>

    /**
     * 预置解码器、权限、前台服务等资源。可重入；已在合适状态时直接返回成功。
     */
    suspend fun prepare(config: ProjectionConfig): Result<Unit>

    /**
     * 建立连接并开始出流。EXTERNAL_ACTIVITY 实现在此触发外部 Activity 并把
     * 结果回收挂上 Lifecycle，因此"成功"仅代表已成功移交画面。
     */
    suspend fun start(endpoint: ProjectionEndpoint): Result<Unit>

    /**
     * 停止出流并释放 transport，但保留可再次 start 的已 prepare 资源。
     */
    suspend fun stop(reason: StopReason): Result<Unit>

    /**
     * 彻底释放。调用后本实例不可复用。
     */
    suspend fun release()

    /**
     * 绑定视频输出。仅 EMBEDDED_SURFACE 支持。
     * 重复调用视为替换；传入新尺寸前应保证 Surface 未被对端占用。
     */
    fun attachVideoSurface(target: ProjectionVideoTarget)

    fun detachVideoSurface()

    /**
     * 回传输入。仅 supportsTouchFeedback / supportsKeyboard 为真时可用。
     */
    fun sendInput(event: ProjectionInputEvent)

    /** 请求对端回到其主界面；不支持返回 false，由壳层降级处理。 */
    fun requestPeerHome(): Boolean
}

/**
 * 每种投屏协议提供一个工厂，由 :connection 通过 Registry 选取。
 * 新增协议 = 新增一个实现此接口的 Gradle module，不动壳层代码。
 */
interface ProjectionSessionFactory {

    val kind: ProjectionKind

    val capabilities: ProjectionCapabilities

    /**
     * 运行环境是否满足。返回 [AvailabilityResult] 而不是 Boolean：
     * 不满足时界面要能显示原因与补救办法。
     *
     * 可能被反复调用（每次 connect 前都查一次），因此必须轻量、无副作用：
     * 最多查一下 PackageManager，不要在这里建连接或申请权限。
     */
    fun checkAvailability(): AvailabilityResult

    fun create(): ProjectionSession
}

interface ProjectionSessionRegistry {

    fun all(): List<ProjectionSessionFactory>

    fun find(kind: ProjectionKind): ProjectionSessionFactory?

    /** 当前优先级最高的可用工厂；为 null 时壳层保持本机首页。 */
    fun preferred(): ProjectionSessionFactory?
}
