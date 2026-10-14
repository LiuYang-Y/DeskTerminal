package com.qingke.desk.feature.media.music.spectrum

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.qingke.desk.feature.media.music.online.QqLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 频谱旁路的对外接口（页面 / 壳层只认这个，不认识 media3）。
 *
 * 与 [com.qingke.desk.feature.media.music.QqMusicLibrary] 是同一条边界思路：
 * 实现（[TeeAudioProcessor]）由 [com.qingke.desk.feature.media.music.ExoPlayerPlaybackSource]
 * 内部持有，经 [com.qingke.desk.feature.media.music.RoutingPlaybackSource] 委派出来 ——
 * **media3 的类型因此不出 `:feature:media` 模块**（壳层引不到 `BaseAudioProcessor`，
 * 那是 `implementation` 依赖不该外泄的类）。
 */
interface SpectrumControl {
    /** 平滑后的音量电平，0..1。 */
    val level: StateFlow<Float>

    /** 门控三条件。见 [TeeAudioProcessor.setGates]。 */
    fun setGates(playing: Boolean, visible: Boolean, enabled: Boolean)
}

private const val TAG = "SpectrumTee"

/** 包络上升时间常数（ms）。响度起来要快，不然慢半拍。 */
private const val ATTACK_MS = 30f

/** 包络回落时间常数（ms）。缓慢衰减，圆环才不会每帧抽搐。 */
private const val RELEASE_MS = 350f

/**
 * RMS → 电平的增益。音乐的 RMS 很少超过满幅的三成，
 * 线性映射会把圆环永远压在小尺寸；开方前先放大再压回 0..1。
 */
private const val LEVEL_GAIN = 3f

/** 统计日志的间隔（缓冲数）。约 30s 音频一行。 */
private const val STATS_LOG_EVERY = 1024L

/**
 * PCM 旁路采样器：挂在 ExoPlayer 的音频链上（注入方式见 [SpectrumRenderersFactory]），
 * 把解码后的 PCM 抄一份出来算**音量电平**，产出 [level]（0..1）驱动左右声波圆环。
 *
 * ---------------------------------------------------------------------------
 * 为什么 extends [BaseAudioProcessor]，而不是裸实现 [AudioProcessor]
 *
 * 缓冲区舞步（`configure`/`queueInput`/`getOutput`/`isEnded`/`flush`/`reset`
 * 的调用时机、输出缓冲何时 flip、流末尾怎么收）是 media3 自己踩过坑的代码：
 * 写错一个环节的症状是**音频卡顿/断流**，而"Phase 0 实测 ExoPlayer 能放出声"
 * 是这个模块的立身之本，不值得为省一个基类去冒这个险。
 *
 * 所以这里只覆写两件事（与 media3 自带 `TeeAudioProcessor` 的字节码同构，
 * 1.11.1 javap 实测）：[onConfigure] 原样透传格式，[queueInput] 里
 * `replaceOutputBuffer(remaining).put(buf).flip()` 完成透传拷贝。
 * 分析永远发生在**拷贝之前**、对输入的只读视图上，不碰原缓冲的位置。
 *
 * ---------------------------------------------------------------------------
 * 可停算（本项目硬约束，不是优化）
 *
 * [queueInput] 的**首行**就是判据：三个门（播放中 / 页面可见 / 档位非 OFF）
 * 没全开时，连只读视图都不建，直接透传。三个门的语义与声波圆环相位协程
 * （[com.qingke.desk.feature.media.music.rememberEffectPhase]）逐条对齐 ——
 * 同三条判据，两处实现，谁也不许单独放松。
 *
 * 取证不靠嘴： [analyzedBuffers] / [skippedBuffers] 两个计数器，
 * 门变化与每 [STATS_LOG_EVERY] 个缓冲各落一行文件日志。证伪用例：
 * 档位调到 OFF 后，分析计数**冻结不动**、跳过计数持续上涨。
 *
 * 为什么不用 `isActive()` 做门控：它的语义是"在不在音频链里"，
 * 中途返回 false 有链路重配风险（重配就可能打断音频）。门关时那次
 * ByteBuffer 拷贝是**任何**链上处理器的固定成本，不是效果计算 ——
 * 停算指的是我们的分析（几千次乘加 + 状态流写）一次都不发生。
 *
 * ---------------------------------------------------------------------------
 * 线程与开销
 *
 * [queueInput] 跑在 ExoPlayer 的播放线程。一次分析是几千次乘加
 * （44.1kHz 立体声一个缓冲约 10~40ms 音频，即约 900~3500 帧），
 * 远小于同缓冲的解码开销。[level] 的写是并发安全的（StateFlow 并发原语）。
 *
 * ---------------------------------------------------------------------------
 * 不猜编码
 *
 * 只认 `ENCODING_PCM_16BIT` 与 `ENCODING_PCM_FLOAT`（Android 音频链的两种
 * 合法 PCM）。遇到别的编码直接放弃分析（电平归零），**不猜位宽** ——
 * 猜错的症状是电平乱跳，比没有电平更难查。
 *
 * ---------------------------------------------------------------------------
 * 脱敏
 *
 * 日志里只有门状态与计数，没有任何 PCM 数据、没有任何地址/凭证。
 */
class TeeAudioProcessor : BaseAudioProcessor(), SpectrumControl {

    private val _level = MutableStateFlow(0f)

    /**
     * 平滑后的音量电平，0..1。任何线程可读；写在播放线程。
     *
     * 门没全开时恒为 0（不是"停在最后的响度上"）—— 暂停再播放时
     * 圆环从静默重新长出来，与相位归零是同一条行为。
     */
    override val level: StateFlow<Float> = _level.asStateFlow()

    // ---- 门控三条件 ----------------------------------------------------------

    @Volatile private var gatePlaying = false

    @Volatile private var gateVisible = false

    @Volatile private var gateEnabled = false

    // ---- 取证计数器 ----------------------------------------------------------

    private val analyzedCount = AtomicLong(0)
    private val skippedCount = AtomicLong(0)

    /** 实际做了分析的缓冲数（门全开时增长；门关时冻结）。 */
    val analyzedBuffers: Long get() = analyzedCount.get()

    /** 门关闭时透传但跳过分析的缓冲数（OFF 档下持续上涨的那一个）。 */
    val skippedBuffers: Long get() = skippedCount.get()

    // ---- 输入格式（configure 时记下，queueInput 时用）------------------------

    private var encoding = C.ENCODING_INVALID
    private var channelCount = 0
    private var sampleRate = 0
    private var bytesPerFrame = 0

    /** 包络当前值。与 [_level] 同步写；门关 / flush / reset 时一起归零。 */
    @Volatile private var envelope = 0f

    private val gatesAllOpen: Boolean
        get() = gatePlaying && gateVisible && gateEnabled

    /**
     * 门控。由音乐页调用 —— 它是唯一同时知道"播放中 / 页面可见 / 效果档位"
     * 三件事的地方（壳层不知道档位，播放源不知道页面可不可见）。
     *
     * 变化时落一行文件日志：OFF 档有没有在算，靠这行 + 两个计数器对上。
     */
    override fun setGates(playing: Boolean, visible: Boolean, enabled: Boolean) {
        val changed = playing != gatePlaying ||
            visible != gateVisible ||
            enabled != gateEnabled
        gatePlaying = playing
        gateVisible = visible
        gateEnabled = enabled
        if (!changed) return
        QqLog.i(
            TAG,
            "门控变化 playing=$playing visible=$visible enabled=$enabled" +
                "（累计 分析=${analyzedCount.get()} 跳过=${skippedCount.get()}）",
        )
        if (!gatesAllOpen) {
            envelope = 0f
            _level.value = 0f
        }
    }

    /** 透传：格式原样返回。改了格式 [androidx.media3.exoplayer.audio.DefaultAudioSink] 要重配音频链。 */
    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        encoding = inputAudioFormat.encoding
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate
        bytesPerFrame = inputAudioFormat.bytesPerFrame
        return inputAudioFormat
    }

    /**
     * 每个 PCM 缓冲一次。**首行是停算判据**（见类注释）。
     *
     * 透传与 media3 自带 `TeeAudioProcessor` 同构（javap 实测 1.11.1）：
     * 只读视图给分析、原缓冲整份拷进输出缓冲后 flip。
     */
    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!gatesAllOpen) {
            // 停算分支：只透传。只读视图（asReadOnlyBuffer 的分配）都不发生。
            skippedCount.incrementAndGet()
            passThrough(inputBuffer, remaining)
            logStatsIfDue()
            return
        }

        analyzedCount.incrementAndGet()
        // asReadOnlyBuffer 而不是 Util.createReadOnlyByteBuffer：后者就是这么实现的
        // （字节码实测），直接调用少一个对巨型 Util 类的加载依赖，单测里也跑得更干净。
        analyze(inputBuffer.asReadOnlyBuffer(), remaining)
        passThrough(inputBuffer, remaining)
        logStatsIfDue()
    }

    private fun passThrough(inputBuffer: ByteBuffer, remaining: Int) {
        replaceOutputBuffer(remaining).put(inputBuffer).flip()
    }

    /**
     * 换歌 / seek：包络归零。不让上一首尾音的响度带到下一首的第一帧
     * （表现会是"每首歌开头圆环都先炸一下"）。
     */
    override fun onFlush() {
        envelope = 0f
        _level.value = 0f
    }

    override fun onReset() {
        envelope = 0f
        _level.value = 0f
    }

    // ---- 分析 ----------------------------------------------------------------

    private fun analyze(view: ByteBuffer, byteCount: Int) {
        if (sampleRate <= 0 || channelCount <= 0 || bytesPerFrame <= 0) return
        val frames = byteCount / bytesPerFrame
        if (frames <= 0) return
        // 缓冲时长由格式精确算出（不是估的）：包络的 attack/release 靠它折算系数。
        val dtMs = frames * 1000f / sampleRate

        val rms = when (encoding) {
            C.ENCODING_PCM_16BIT -> rms16Bit(view, byteCount)
            C.ENCODING_PCM_FLOAT -> rmsFloat(view, byteCount)
            else -> return // 不认识的编码不猜（见类注释）
        }
        envelope = envelopeStep(envelope, rmsToLevel(rms), dtMs, ATTACK_MS, RELEASE_MS)
        _level.value = envelope
    }

    /** 16 位小端 PCM 的 RMS（按满幅 32768 归一）。绝对读，不动视图位置。 */
    private fun rms16Bit(view: ByteBuffer, byteCount: Int): Float {
        val shorts = view.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = minOf(shorts.remaining(), byteCount / 2)
        if (count <= 0) return 0f
        var sum = 0f
        for (i in 0 until count) {
            val v = shorts.get(i) / 32768f
            sum += v * v
        }
        return sqrt(sum / count)
    }

    /** 浮点 PCM（本身即 -1..1）的 RMS。 */
    private fun rmsFloat(view: ByteBuffer, byteCount: Int): Float {
        val floats = view.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val count = minOf(floats.remaining(), byteCount / 4)
        if (count <= 0) return 0f
        var sum = 0f
        for (i in 0 until count) {
            val v = floats.get(i)
            sum += v * v
        }
        return sqrt(sum / count)
    }

    private fun logStatsIfDue() {
        val total = analyzedCount.get() + skippedCount.get()
        if (total == 0L || total % STATS_LOG_EVERY != 0L) return
        QqLog.i(
            TAG,
            "频谱统计 分析=${analyzedCount.get()} 跳过=${skippedCount.get()}" +
                "（每 $STATS_LOG_EVERY 个缓冲一行）",
        )
    }
}

// ---------------------------------------------------------------------------------
// 纯函数（单测直接盯着；不放 companion 里是为了测试可见性）
// ---------------------------------------------------------------------------------

/** RMS → 0..1 电平。开方让中等响度也占得到大半行程。 */
internal fun rmsToLevel(rms: Float): Float {
    if (rms <= 0f) return 0f
    return sqrt(rms * LEVEL_GAIN).coerceIn(0f, 1f)
}

/**
 * 指数平滑包络的一步。
 *
 * 上升用 [attackMs]、回落用 [releaseMs] 两个时间常数（不是同一个）——
 * 声音变大要跟得上，变小要留余韵，这是所有 VU 表的共性，不是口味问题。
 */
internal fun envelopeStep(
    current: Float,
    target: Float,
    dtMs: Float,
    attackMs: Float,
    releaseMs: Float,
): Float {
    if (dtMs <= 0f) return current.coerceIn(0f, 1f)
    val tau = if (target > current) attackMs else releaseMs
    if (tau <= 0f) return target.coerceIn(0f, 1f)
    val k = 1f - exp(-dtMs / tau)
    return (current + (target - current) * k).coerceIn(0f, 1f)
}
