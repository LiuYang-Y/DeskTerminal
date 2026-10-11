package com.qingke.desk.projection.carplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.qingke.desk.core.platform.WifiGate
import com.qingke.desk.projection.api.AvailabilityAction
import com.qingke.desk.projection.api.AvailabilityResult
import com.qingke.desk.projection.api.ProjectionCapabilities
import com.qingke.desk.projection.api.ProjectionConfig
import com.qingke.desk.projection.api.ProjectionEndpoint
import com.qingke.desk.projection.api.ProjectionError
import com.qingke.desk.projection.api.ProjectionEvent
import com.qingke.desk.projection.api.ProjectionKind
import com.qingke.desk.projection.api.ProjectionRenderMode
import com.qingke.desk.projection.api.ProjectionInputEvent
import com.qingke.desk.projection.api.ProjectionSession
import com.qingke.desk.projection.api.ProjectionSessionFactory
import com.qingke.desk.projection.api.ProjectionState
import com.qingke.desk.projection.api.ProjectionVideoTarget
import com.qingke.desk.projection.api.StopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 外部 DiPlay 应用的坐标。
 *
 * 用 adb 从本机实测取得（OnePlus PKR110 上已装 DiPlay v0.2.11）：
 *   dumpsys package com.shihab.diplay  ->  versionName=0.2.11, targetSdk=37
 *   cmd package resolve-activity ...   ->  com.shihab.diplay/com.shilapi.xcertplay.DiPlayActivity
 *
 * 注意包名与 Activity 名不同前缀（shihab / shilapi），是 DiPlay 自身的命名，不要"顺手修正"。
 */
object DiPlayTarget {
    const val PACKAGE: String = "com.shihab.diplay"
    const val ACTIVITY: String = "com.shilapi.xcertplay.DiPlayActivity"
}

/**
 * 阶段一的 CarPlay 会话：**把画面整体交给外部已装的 DiPlay**。
 *
 * 与 MockCarPlaySession 的分工：
 *   Mock  EMBEDDED_SURFACE  —— 自绘假画面，用来验证 Surface 链路与逃生手势；
 *   本实现 EXTERNAL_ACTIVITY —— 真机跑通"首页 -> DiPlay -> iPhone 投屏"的完整链路。
 *
 * 拿不到的东西（阶段一的已知限制，不是 bug）：
 *   · 视频 Surface  —— 画面在 DiPlay 进程里渲染，我方 attachVideoSurface 直接抛；
 *   · 输入事件      —— 触摸由 DiPlay 自己处理，我方 pushInput 抛；
 *   · 退出时机      —— 用户可能按返回键，也可能从最近任务切回来，两种都要能被检测到。
 *
 * TODO(step-2): 把 DiPlay 的协议/编解码源码内嵌成本进程会话后，本类退化为降级路径。
 *               红线：只可参考其协议实现，**绝不复用 DiPlay/DiAuto 的 UI、布局、drawable、string**。
 */
class ExternalDiPlaySessionFactory(
    private val appContext: Context,
) : ProjectionSessionFactory {

    override val kind: ProjectionKind = ProjectionKind.CARPLAY

    override val capabilities: ProjectionCapabilities = ProjectionCapabilities(
        renderMode = ProjectionRenderMode.EXTERNAL_ACTIVITY,
        supportsTouchFeedback = false,
        supportsKeyboard = false,
        supportsVoiceButton = false,
        exclusiveAudio = true,
        requiresForegroundService = true,
    )

    override fun checkAvailability(): AvailabilityResult =
        DiPlayInstallProbe.probe(appContext)

    override fun create(): ProjectionSession =
        ExternalDiPlaySession(appContext, capabilities)
}

/**
 * DiPlay 安装状态检查。
 *
 * **关键坑**：targetSdk >= 30 起，PackageManager 的跨包查询受"包可见性"限制 ——
 * manifest 里没声明 `<queries>` 的话，即使 DiPlay 已安装，resolveActivity 也返回 null，
 * 界面会误报"未安装"。`:app/AndroidManifest.xml` 里已补 `<queries>`，改这里时要一并留意。
 *
 * 三种结果：
 *   未安装            -> EXTERNAL_APP_MISSING，提示去装
 *   装了但被停用      -> EXTERNAL_APP_MISSING，提示去应用详情里启用
 *   装了但入口对不上  -> 退化用它的 launcher Activity；连 launcher 都没有才算 UNSUPPORTED
 */
internal object DiPlayInstallProbe {

    private const val TAG = "DeskDiPlay"

    fun probe(context: Context): AvailabilityResult {
        val pm = context.packageManager
        val packageName = DiPlayTarget.PACKAGE

        val packageInfo = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
        }.getOrNull()

        if (packageInfo == null) {
            Log.w(TAG, "package $packageName not visible/installed")
            return AvailabilityResult.Unavailable(
                code = ProjectionError.Code.EXTERNAL_APP_MISSING,
                headline = "未安装投屏接收端",
                detail = "阶段一通过外部应用 DiPlay 接收 iPhone 的 CarPlay 投屏，" +
                    "本机没有找到 ${DiPlayTarget.PACKAGE}。\n" +
                    "该函数本身已实现，只是缺一个可以拉起的接收端。",
                remedy = "安装 DiPlay 后点「重新检测」；或者等阶段二把协议栈内嵌进本应用，" +
                    "那时不再依赖外部应用。",
                extras = mapOf("package" to packageName),
            )
        }

        val version = packageInfo.versionName ?: "(未知版本)"

        // 用 enabledSetting 而不是 ApplicationInfo.enabled：
        // 后者对 disable-user（市场上常见的一种半停用）不一定为 false。
        val enabledSetting = runCatching { pm.getApplicationEnabledSetting(packageName) }
            .getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
        if (enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
            enabledSetting == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
        ) {
            Log.w(TAG, "$packageName installed but disabled (setting=$enabledSetting)")
            return AvailabilityResult.Unavailable(
                code = ProjectionError.Code.EXTERNAL_APP_MISSING,
                headline = "投屏接收端已被停用",
                detail = "已安装 DiPlay $version，但它的组件处于停用状态，系统不允许拉起它。",
                remedy = "点「去启用」到应用详情页把它恢复，然后点「重新检测」。",
                action = AvailabilityAction.OPEN_APP_DETAILS,
                extras = mapOf("package" to packageName, "version" to version),
            )
        }

        // Wi-Fi 射频开关。
        //
        // 必须看"射频是否打开"而不是"是否已连上 Wi-Fi 网络"：
        // 阶段一的接收端（DiPlay）用 Wi-Fi Direct 跟 iPhone 直连，
        // 它只需要射频开着，**不需要先连家里的路由器**。所以 Gate.query 里
        // enabled 与 connected 是分开的两个字段，这里只认 enabled。
        //
        // 注意：这里只读状态，绝不调用 setWifiEnabled —— 替用户打开开关是越权行为，
        //       而且 CHANGE_WIFI_STATE 属于系统签名级 / 设备所有者级能力，普通应用也调不动。
        //       正确做法是把决定权还给用户：提示页给一个"去开启"按钮跳系统面板。
        val wifi = WifiGate.query(context)
        if (!wifi.enabled) {
            Log.w(TAG, "wifi disabled -> refuse handoff (connected=${wifi.connected})")
            return AvailabilityResult.Unavailable(
                code = ProjectionError.Code.WIFI_DISABLED,
                headline = "需要先打开 Wi-Fi",
                detail = "无线 CarPlay 走的是 Wi-Fi 直连：接收端用它和 iPhone 建立点对点连接，" +
                    "不经过路由器，但要求本机的 Wi-Fi 射频处于打开状态（不必先连上某个网络）。\n" +
                    "现在它是关着的，即使拉起 DiPlay，iPhone 也搜不到这台设备。",
                remedy = "点「去开启」打开系统 Wi‑Fi 面板，打开后再回到本页点「重新检测」。",
                action = AvailabilityAction.OPEN_WIFI_SETTINGS,
                extras = buildMap {
                    put("package", packageName)
                    put("version", version)
                    wifi.ssid?.let { put("ssid", it) }
                },
            )
        }

        val specific = Intent().setComponent(
            ComponentName(DiPlayTarget.PACKAGE, DiPlayTarget.ACTIVITY),
        )
        if (resolveExists(pm, specific)) {
            Log.i(TAG, "DiPlay $version ready, entry ${DiPlayTarget.ACTIVITY}")
            return AvailabilityResult.Ready
        }

        // 入口 Activity 名字随版本可能变；DiPlay 还能用就退化到它的 launcher Activity。
        val launcher = pm.getLaunchIntentForPackage(packageName)
        if (launcher?.resolveActivity(pm) != null) {
            Log.w(TAG, "${DiPlayTarget.ACTIVITY} not resolved, fall back to launcher intent")
            return AvailabilityResult.Ready
        }

        Log.w(TAG, "$packageName has no launchable activity")
        return AvailabilityResult.Unavailable(
            code = ProjectionError.Code.UNSUPPORTED,
            headline = "投屏接收端没有可拉起的界面",
            detail = "已安装 DiPlay $version，但查不到任何可启动的 Activity" +
                "（既没有 ${DiPlayTarget.ACTIVITY}，也没有 launcher 入口）。",
            remedy = "通常是这个版本做了入口调整，升级或重装 DiPlay 后再试。",
            extras = mapOf("package" to packageName, "version" to version),
        )
    }

    private fun resolveExists(pm: PackageManager, intent: Intent): Boolean {
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(intent, 0)
            }
        }.getOrNull()
        return resolved != null
    }
}

/**
 * 外部 DiPlay 会话实现。
 *
 * 状态流转刻意与真实实现保持一致，只是 start() 真的换行了一件事：拉起外部 Activity。
 * start() 成功只代表"画面已移交"，不代表 iPhone 已经连上 —— iPhone 侧的连接在 DiPlay 里完成，
 * 本进程看不到，也无法替它确认。这是阶段一必须接受的可见性损失。
 */
class ExternalDiPlaySession(
    private val appContext: Context,
    override val capabilities: ProjectionCapabilities,
) : ProjectionSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow(ProjectionState.IDLE)
    override val state: StateFlow<ProjectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProjectionEvent>(extraBufferCapacity = 16)
    override val events: SharedFlow<ProjectionEvent> = _events.asSharedFlow()

    override val id: String = ID
    override val kind: ProjectionKind = ProjectionKind.CARPLAY

    override suspend fun prepare(config: ProjectionConfig): Result<Unit> {
        _state.value = ProjectionState.PREPARING
        // 重复检查一次：checkAvailability() 到 start() 之间用户可能刚好卸载了 DiPlay。
        val availability = DiPlayInstallProbe.probe(appContext)
        if (availability !is AvailabilityResult.Ready) {
            _state.value = ProjectionState.FAILED
            val unavailable = availability as AvailabilityResult.Unavailable
            _events.emit(
                ProjectionEvent.Error(
                    ProjectionError(
                        code = unavailable.code,
                        detail = unavailable.headline,
                    ),
                ),
            )
            return Result.failure(IllegalStateException(unavailable.headline))
        }
        _state.value = ProjectionState.IDLE
        return Result.success(Unit)
    }

    override suspend fun start(endpoint: ProjectionEndpoint): Result<Unit> {
        val intent = buildEntryIntent()
        return runCatching {
            appContext.startActivity(intent)
        }.onSuccess {
            _state.value = ProjectionState.STREAMING
            // 音频被 DiPlay 独占：本端音乐必须让位（B3 既定策略）。
            _events.emit(ProjectionEvent.AudioExclusiveChanged(exclusive = true))
            Log.i(TAG, "handed off to ${intent.component?.flattenToShortString()}")
        }.onFailure { throwable ->
            _state.value = ProjectionState.FAILED
            val message = describeLaunchFailure(throwable)
            Log.e(TAG, "launch failed: $message", throwable)
            _events.emit(
                ProjectionEvent.Error(
                    ProjectionError(
                        code = ProjectionError.Code.EXTERNAL_APP_MISSING,
                        detail = message,
                        cause = throwable,
                    ),
                ),
            )
        }
    }

    override suspend fun stop(reason: StopReason): Result<Unit> {
        _state.value = ProjectionState.STOPPING
        _events.emit(ProjectionEvent.AudioExclusiveChanged(exclusive = false))
        _state.value = ProjectionState.STOPPED
        Log.i(TAG, "stop($reason)")
        return Result.success(Unit)
    }

    override suspend fun release() {
        scope.cancel()
        _state.value = ProjectionState.STOPPED
    }

    override fun attachVideoSurface(target: ProjectionVideoTarget) {
        throw UnsupportedOperationException(EXTERNAL_LIMITATION)
    }

    override fun detachVideoSurface() = Unit

    override fun sendInput(event: ProjectionInputEvent) {
        throw UnsupportedOperationException(EXTERNAL_LIMITATION)
    }

    override fun requestPeerHome(): Boolean = false

    /** 优先走实测得到的入口，查不到再退化到 launcher（与 probe 保持一致的两级策略）。 */
    private fun buildEntryIntent(): Intent {
        // ACTION_MAIN + 显式 component 最接近"正常打开这个应用"；
        // 不要加 CATEGORY_LAUNCHER，那会按 launcher 的语义重建任务栈。
        val base = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(DiPlayTarget.PACKAGE, DiPlayTarget.ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val pm = appContext.packageManager
        return if (base.resolveActivity(pm) != null) {
            base
        } else {
            pm.getLaunchIntentForPackage(DiPlayTarget.PACKAGE)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                ?: base
        }
    }

    private fun describeLaunchFailure(throwable: Throwable): String = when (throwable) {
        is android.content.ActivityNotFoundException ->
            "找不到 DiPlay 的可拉起界面（${DiPlayTarget.PACKAGE}）。它可能刚被卸载。"

        is SecurityException ->
            "系统拒绝拉起 DiPlay：${throwable.message.orEmpty()}。" +
                "通常是它的入口 Activity 没有 exported。"

        else -> "拉起 DiPlay 失败：${throwable.message.orEmpty()}"
    }

    companion object {
        const val ID: String = "external-diplay"
        const val TAG: String = "DeskDiPlay"
        const val EXTERNAL_LIMITATION: String =
            "EXTERNAL_ACTIVITY 会话的画面由外部应用渲染，" +
                "本进程拿不到 Surface、也收不到输入事件 —— 这是阶段一的既定形态。"
    }
}
