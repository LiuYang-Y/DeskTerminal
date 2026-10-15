package com.qingke.desk.core.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.Choreographer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 效果档位的**运行时裁决者**：把"用户选的等级 + 设备能跑什么 + 现在跑得怎么样"
 * 合成一个实际生效的 [EffectTier]。
 *
 * ---------------------------------------------------------------------------
 * 为什么要有迟滞（hysteresis）
 *
 * 三路信号都会抖：温度会随一次瞬时负载跳一档、掉帧率在动画起停的那一两秒必然偏高、
 * 省电模式开关本身就是一个跳变。如果"达标就升、超标就降"，档位会在两个值之间来回跳，
 * 用户看到的是玻璃时有时无 —— 这比一直停在低档难受得多。
 *
 * 所以三条不对称的规则（**降得快、升得慢**）：
 *
 *   · 降档：连续 [DOWN_AFTER_BAD_WINDOWS] 个坏窗口才降 1 档；
 *           但温度到 SEVERE 立刻降 1 档、CRITICAL 立刻降到最低 —— 热是硬约束，不等。
 *   · 升档：要连续 [UP_AFTER_GOOD_WINDOWS] 个好窗口，且温度回到 MODERATE 以下。
 *   · 冷却：任何一次变更后 [COOLDOWN_MS] 内不允许再次变更。
 *
 * 升档要求的窗口数是降档的两倍，且升档的好窗口阈值（[JANK_GOOD]）比降档的坏窗口
 * 阈值（[JANK_BAD]）严格得多 —— 两条合起来保证"在边界上来回"时档位稳定在原处。
 *
 * ---------------------------------------------------------------------------
 * 掉帧怎么测（以及为什么不用 FrameMetrics）
 *
 * 用 [Choreographer] 做**间歇采样**：每 [SAMPLE_PERIOD_MS] 采 [BURST_MS] 一段，
 * 数这段里有多少帧的间隔超过基准帧间隔的 1.5 倍。
 *
 * 不采样的那 92% 时间里我们不注册任何回调，屏幕空闲时 Choreographer 完全不会被
 * 我们的监控唤醒 —— 这是一台 24 小时常亮的设备，常驻一个逐帧回调本身就是耗电项。
 * `FrameMetricsAggregator` 是逐帧常驻的，不适合这里。
 */
class EffectGovernor(
    private val context: Context,
    initialProfile: DeviceProfile,
    private val scope: CoroutineScope,
) {

    // ---------- 对外状态 ----------

    /**
     * 当前设备档案。
     *
     * 做成可变而不是构造参数定死，是因为 `MainActivity` 在 manifest 里声明了
     * `configChanges=...|density|screenSize`，**配置变更不会重建 Activity**，
     * 只回调 `onConfigurationChanged`。用 `wm size` / `wm density` 改屏幕、
     * 或者手机转 180°，都必须靠 [updateProfile] 把新档案喂进来，
     * 否则刷新率基准与分辨率会一直停在上一次的值上。
     */
    private val _profile = MutableStateFlow(initialProfile)
    val profile: StateFlow<DeviceProfile> = _profile

    /** 配置变更（含 `wm size` / `wm density`）时调用。 */
    fun updateProfile(profile: DeviceProfile) {
        _profile.value = profile
        // 档位的**能力**部分依赖 API 与机型，跟着重算。
        refreshBaseTier()
    }

    /** 用户选择的等级（含 AUTO）。由 [setLevel] 写入。 */
    private val _level = MutableStateFlow(EffectLevel.AUTO)
    val level: StateFlow<EffectLevel> = _level

    /** 运行时降级步数，0..[MAX_PENALTY]。 */
    private val _penalty = MutableStateFlow(0)
    val penalty: StateFlow<Int> = _penalty

    /** 调试页的强制档位。非 null 时完全绕过上面一切。 */
    private val _override = MutableStateFlow<EffectTier?>(null)
    val override: StateFlow<EffectTier?> = _override

    /** 最近一次测量的掉帧率（0..1）。-1 = 还没测过。 */
    private val _jankRatio = MutableStateFlow(-1f)
    val jankRatio: StateFlow<Float> = _jankRatio

    /** 最近一次测量的实测帧率。-1 = 还没测过。 */
    private val _fps = MutableStateFlow(-1f)
    val fps: StateFlow<Float> = _fps

    /**
     * `PowerManager.getCurrentThermalStatus()` 的值；-1 = 这台机器读不到（API < 29）。
     * 取值 NONE 0 / LIGHT 1 / MODERATE 2 / SEVERE 3 / CRITICAL 4 / EMERGENCY 5 / SHUTDOWN 6。
     */
    private val _thermal = MutableStateFlow(THERMAL_UNKNOWN)
    val thermal: StateFlow<Int> = _thermal

    /** 系统省电模式是否开启。 */
    private val _powerSave = MutableStateFlow(false)
    val powerSave: StateFlow<Boolean> = _powerSave

    /** 上一次变更档位的原因，调试页显示用。 */
    private val _lastReason = MutableStateFlow("未变更")
    val lastReason: StateFlow<String> = _lastReason

    /** 由"用户选择 + 设备能力"算出的基准档位（不含运行时降级）。 */
    val baseTier: StateFlow<EffectTier> = combine(_level, _profile) { level, profile ->
        resolveBaseTier(level, profile)
    }.stateIn(scope, SharingStarted.Eagerly, resolveBaseTier(EffectLevel.AUTO, initialProfile))

    /** **实际生效**的档位。全工程只读这一个。 */
    val tier: StateFlow<EffectTier> = combine(baseTier, _penalty, _override) { base, penalty, override ->
        override ?: base.degradedBy(penalty)
    }.stateIn(scope, SharingStarted.Eagerly, resolveBaseTier(EffectLevel.AUTO, initialProfile))

    /**
     * **音乐舞台**的生效档位（2026-10-15 用户口径，与 [tier] 解耦）。
     *
     * 玻璃层的 `minApi` 门槛不该误伤音乐特效 —— 圆环与歌词模糊在 API 26+ 全部原生。
     * AUTO 时基线直接 HIGH（只叠运行时段数）；手动锁定时按锁定值、不受 minApi 限制。
     * 详见 [resolveMusicTier]。
     */
    val musicTier: StateFlow<EffectTier> = combine(_level, _penalty, _override) { level, penalty, override ->
        resolveMusicTier(level, penalty, override)
    }.stateIn(scope, SharingStarted.Eagerly, resolveMusicTier(EffectLevel.AUTO, 0, null))

    /** [updateProfile] 后强制重算一次基准档位（combine 已经会做，这里只为可读性留个入口）。 */
    private fun refreshBaseTier() {
        // combine(_level, _profile) 会在 _profile 变化时自动重算，这里什么都不用做。
        // 留一个空实现而不是让 updateProfile 看起来"忘了重算"，是为了后来的人不必怀疑。
    }

    // ---------- 采样状态 ----------

    private var consecutiveBad = 0
    private var consecutiveGood = 0
    private var lastChangeAtMs = 0L
    private var samplerJob: Job? = null

    private val powerManager: PowerManager? get() = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

    // 温度监听原先挂在一个**自己建的** `Executors.newSingleThreadExecutor()` 上。
    // 那条路有两个问题，2026-10-14 一起去掉了：
    //   1. 那个线程池从建出来到进程结束**没有任何地方 shutdown()** —— 常亮的设备上，
    //      它连同那条常驻线程一直挂着，而这个监听器在 Activity 重建时会被重新注册；
    //   2. 回调因此在后台线程直接改状态（`_thermal` / `_penalty` / 迟滞计数器），
    //      和采样协程形成竞争。
    // 换成 mainExecutor 之后：不拥有任何需要释放的资源（拿到的是主 looper 上的一个
    // Handler 包装），回调与 Choreographer 采样、以及所有 StateFlow 的读取者都在同一条
    // 线程上排队，不再有"两个线程同时改 consecutiveBad"这种事。
    // 主线程上只做一次 StateFlow 赋值 + 一个 `scope.launch`，耗时在微秒级，
    // 不会影响 16 ms 的帧预算。
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null
    private var powerSaveReceiver: BroadcastReceiver? = null

    // ---------- 生命周期 ----------

    fun setLevel(level: EffectLevel) {
        _level.value = level
        // 手动改档等于一次"用户意图"，把迟滞状态清零，否则刚被降过的档位
        // 会在用户手动锁定后又被残留的连续坏窗口继续往下压。
        resetHysteresis("用户设置 → ${level.label}")
    }

    /** 调试页专用：强制档位。传 null 取消强制。 */
    fun setOverride(tier: EffectTier?) {
        _override.value = tier
    }

    fun start() {
        if (samplerJob?.isActive == true) return

        _thermal.value = readThermalStatus()
        _powerSave.value = readPowerSave()

        registerThermalListener()
        registerPowerSaveReceiver()

        samplerJob = scope.launch { sampleLoop() }
    }

    fun stop() {
        samplerJob?.cancel()
        samplerJob = null
        unregisterThermalListener()
        unregisterPowerSaveReceiver()
    }

    private fun resetHysteresis(reason: String) {
        consecutiveBad = 0
        consecutiveGood = 0
        lastChangeAtMs = System.currentTimeMillis()
        _lastReason.value = reason
    }

    // ---------- 采样主循环 ----------

    private suspend fun sampleLoop() {
        while (scope.isActive) {
            delay(SAMPLE_PERIOD_MS)
            val sample = measureFrameBurst() ?: continue

            val measuredFps = if (sample.frames >= 2 && sample.durationMs > 0) {
                sample.frames * 1000f / sample.durationMs
            } else {
                -1f
            }
            _fps.value = measuredFps
            _jankRatio.value = sample.jankRatio

            evaluate(sample)
        }
    }

    /**
     * 一次采样后的裁决。
     *
     * 温度是**硬信号**：不看连续窗口数，直接压档位。
     * 掉帧是**软信号**：要靠连续窗口累积，避免动画起停的那一下误伤。
     */
    private fun evaluate(sample: Sample) {
        val thermal = _thermal.value
        val now = System.currentTimeMillis()
        val inCooldown = now - lastChangeAtMs < COOLDOWN_MS

        // --- 温度：立刻生效，不受冷却限制（热到 SEVERE 还在等 45 秒是没有道理的）---
        val thermalFloor = when {
            thermal >= PowerManager.THERMAL_STATUS_CRITICAL -> MAX_PENALTY
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 1
            else -> 0
        }
        if (thermalFloor > _penalty.value) {
            _penalty.value = thermalFloor
            resetHysteresis("温度 ${thermalLabel(thermal)} → 降 ${thermalFloor} 档")
            return
        }

        // --- 掉帧：软信号 ---
        val bad = sample.jankRatio >= badThreshold()
        val good = sample.jankRatio < JANK_GOOD

        if (bad) {
            consecutiveBad++
            consecutiveGood = 0
        } else if (good) {
            consecutiveGood++
            consecutiveBad = 0
        } else {
            // 落在迟滞带里：既不算好也不算坏，两边都不累积。
            consecutiveBad = 0
            consecutiveGood = 0
        }

        if (inCooldown) return

        if (consecutiveBad >= DOWN_AFTER_BAD_WINDOWS && _penalty.value < MAX_PENALTY) {
            _penalty.value = _penalty.value + 1
            resetHysteresis("掉帧 ${percent(sample.jankRatio)} 连续 ${consecutiveBad} 次 → 降 1 档")
            return
        }

        val thermalAllowsUpgrade = thermal == THERMAL_UNKNOWN ||
            thermal <= PowerManager.THERMAL_STATUS_MODERATE
        if (consecutiveGood >= UP_AFTER_GOOD_WINDOWS &&
            _penalty.value > 0 &&
            thermalAllowsUpgrade &&
            thermalFloor == 0
        ) {
            _penalty.value = _penalty.value - 1
            resetHysteresis("掉帧 ${percent(sample.jankRatio)} 连续 ${consecutiveGood} 次正常 → 升 1 档")
        }
    }

    /** 省电模式下把"算坏"的门槛压低：既然用户在省电，就别等到 25% 才降。 */
    private fun badThreshold(): Float =
        if (_powerSave.value) JANK_BAD_POWER_SAVE else JANK_BAD

    // ---------- 测量 ----------

    private data class Sample(
        val frames: Int,
        val jankFrames: Int,
        val durationMs: Long,
    ) {
        val jankRatio: Float get() = if (frames == 0) 0f else jankFrames.toFloat() / frames
    }

    /**
     * 采一段 [BURST_MS] 的帧间隔。
     *
     * 返回 null 表示这次没采到（屏幕灭了、Choreographer 没喂帧），调用方直接跳过 ——
     * **"没数据"不等于"很卡"**，这点很关键：机器常亮但用户盖住了屏幕时不能把档位压死。
     */
    private suspend fun measureFrameBurst(): Sample? = runCatching {
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(BURST_MS + BURST_TIMEOUT_SLACK_MS) {
                postFrameBurst()
            }
        }
    }.getOrNull()

    private suspend fun postFrameBurst(): Sample = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val startedAtMs = System.currentTimeMillis()
        val startedAtNs = System.nanoTime()
        var frames = 0
        var jankFrames = 0
        var lastFrameNs = 0L

        val expectedNs = if (_profile.value.refreshRateHz > 0f) {
            (1_000_000_000L / _profile.value.refreshRateHz.toDouble()).toLong()
        } else {
            DEFAULT_FRAME_NS
        }
        val jankThresholdNs = (expectedNs * JANK_FRAME_FACTOR).toLong()

        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frames++
                if (lastFrameNs != 0L && frameTimeNanos - lastFrameNs > jankThresholdNs) {
                    jankFrames++
                }
                lastFrameNs = frameTimeNanos

                val elapsedMs = System.currentTimeMillis() - startedAtMs
                if (elapsedMs < BURST_MS && frames < MAX_SAMPLE_FRAMES) {
                    choreographer.postFrameCallback(this)
                } else if (continuation.isActive) {
                    continuation.resume(
                        Sample(
                            frames = frames,
                            jankFrames = jankFrames,
                            durationMs = (System.nanoTime() - startedAtNs) / 1_000_000L,
                        ),
                    )
                }
            }
        }

        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }

    // ---------- 系统信号 ----------

    private fun readThermalStatus(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return THERMAL_UNKNOWN
        val manager = powerManager ?: return THERMAL_UNKNOWN
        return runCatching { manager.currentThermalStatus }.getOrDefault(THERMAL_UNKNOWN)
    }

    private fun registerThermalListener() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val manager = powerManager ?: return
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            _thermal.value = status
            // 温度是硬信号，不等下一次采样窗口 —— 但它仍然受"升档要冷却"约束，
            // 所以这里只更新值，实际的压档/放档在 evaluate() 里统一做。
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                scope.launch {
                    val floor = if (status >= PowerManager.THERMAL_STATUS_CRITICAL) MAX_PENALTY else 1
                    if (floor > _penalty.value) {
                        _penalty.value = floor
                        resetHysteresis("温度 ${thermalLabel(status)} → 降 $floor 档")
                    }
                }
            }
        }
        runCatching {
            // `mainExecutor` 是 API 28+ 的成员；本分支已被上面的 `SDK_INT < Q`（29）挡住，
            // 所以这里不会有低版本调用（minSdk 26 用不到这个重载，
            // `addThermalStatusListener` 本身也是 API 29 才有的）。
            manager.addThermalStatusListener(context.applicationContext.mainExecutor, listener)
            thermalListener = listener
        }
    }

    private fun unregisterThermalListener() {
        val listener = thermalListener ?: return
        runCatching { powerManager?.removeThermalStatusListener(listener) }
        thermalListener = null
    }

    private fun readPowerSave(): Boolean =
        runCatching { powerManager?.isPowerSaveMode == true }.getOrDefault(false)

    private fun registerPowerSaveReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                    _powerSave.value = readPowerSave()
                }
            }
        }
        runCatching {
            context.applicationContext.registerReceiver(
                receiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            )
            powerSaveReceiver = receiver
        }
    }

    private fun unregisterPowerSaveReceiver() {
        val receiver = powerSaveReceiver ?: return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
        powerSaveReceiver = null
    }

    // ---------- 展示辅助 ----------

    /** 热状态的一行文字。调试页与设置页共用，避免两处写出两套叫法。 */
    fun thermalLabel(status: Int = _thermal.value): String = when (status) {
        THERMAL_UNKNOWN -> "未知（API < 29）"
        PowerManager.THERMAL_STATUS_NONE -> "正常"
        PowerManager.THERMAL_STATUS_LIGHT -> "轻微"
        PowerManager.THERMAL_STATUS_MODERATE -> "中等"
        PowerManager.THERMAL_STATUS_SEVERE -> "严重"
        PowerManager.THERMAL_STATUS_CRITICAL -> "临界"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "紧急"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "即将关机"
        else -> "未知（$status）"
    }

    /** 实测帧率的一行文字。-1 表示还没测到。 */
    fun fpsLabel(): String {
        val value = _fps.value
        return if (value < 0f) "测量中…" else "${value.roundToInt()} fps"
    }

    /** 掉帧率的一行文字。 */
    fun jankLabel(): String {
        val value = _jankRatio.value
        return if (value < 0f) "测量中…" else percent(value)
    }

    /**
     * 一行状态说明。设置浮层与调试页共用，避免两处写出两套口径。
     *
     * 例子：`高（设备最高 高 · 掉帧 8% · 温度 正常）`。
     * 被运行时降级时会多出一段：`已自动降 1 档`。
     */
    fun statusLine(): String {
        val current = tier.value
        val base = baseTier.value
        val penalty = _penalty.value
        val parts = mutableListOf<String>()

        parts += if (base == current) {
            "设备最高 ${base.label}"
        } else {
            "设备最高 ${base.label}，已自动降 $penalty 档"
        }
        parts += "掉帧 ${jankLabel()}"
        parts += "温度 ${thermalLabel()}"
        if (_powerSave.value) parts += "省电模式"

        return "${current.label}（${parts.joinToString(" · ")}）"
    }

    private fun percent(value: Float): String = "${(value * 100).roundToInt()}%"

    companion object {
        /** 读不到热状态时的哨兵。API 29 以下恒为此值。 */
        const val THERMAL_UNKNOWN: Int = -1

        /** 采样周期。**两次采样之间不注册任何回调**，这段时间是纯空闲。 */
        const val SAMPLE_PERIOD_MS: Long = 6_000L

        /** 每次采样持续多久。占空比 500/6000 ≈ 8%。 */
        const val BURST_MS: Long = 500L

        /** 单次采样的兜底超时：屏幕灭了 Choreographer 不喂帧，不能把协程挂死。 */
        const val BURST_TIMEOUT_SLACK_MS: Long = 1_500L

        /** 单次采样最多数多少帧，防止高刷屏上数组无意义地长。 */
        const val MAX_SAMPLE_FRAMES: Int = 240

        /** 间隔超过基准帧间隔的这个倍数算一次掉帧。1.5 = 掉了一整帧。 */
        const val JANK_FRAME_FACTOR: Double = 1.5

        /** 读不到刷新率时按 60Hz 的帧间隔判。 */
        const val DEFAULT_FRAME_NS: Long = 16_666_667L

        /** 掉帧率高于此值算一个坏窗口。 */
        const val JANK_BAD: Float = 0.25f

        /** 省电模式下的坏窗口门槛。 */
        const val JANK_BAD_POWER_SAVE: Float = 0.15f

        /** 掉帧率低于此值才算一个好窗口 —— 比坏窗口门槛严格得多，这是迟滞的一半。 */
        const val JANK_GOOD: Float = 0.10f

        /** 连续几个坏窗口降一档。 */
        const val DOWN_AFTER_BAD_WINDOWS: Int = 2

        /** 连续几个好窗口升一档。**是降档门槛的两倍**，这是迟滞的另一半。 */
        const val UP_AFTER_GOOD_WINDOWS: Int = 4

        /** 一次变更后多久内不允许再变。 */
        const val COOLDOWN_MS: Long = 45_000L

        /** 最多降几档。2 = 从 HIGH 最多降到 OFF。 */
        const val MAX_PENALTY: Int = 2
    }
}
