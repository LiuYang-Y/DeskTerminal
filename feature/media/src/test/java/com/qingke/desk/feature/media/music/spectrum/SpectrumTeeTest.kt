package com.qingke.desk.feature.media.music.spectrum

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [TeeAudioProcessor] 的取证测试。
 *
 * 这里**直接驱动 queueInput**（不经过 ExoPlayer）：BaseAudioProcessor 的缓冲舞步
 * 是纯 java.nio 代码（javap 实测零 android 引用），JVM 单测能完整走通
 * "configure → queueInput → getOutput" 全链路。要盯的三件事：
 *
 *  1. **门控即停算**：门没全开时 analyzed 冻结、skipped 照涨、电平恒零；
 *  2. **透传不失真**：出去的 PCM 与进来的一字节不差（音频链的正确性）；
 *  3. **包络形状**：上升快、回落慢，且都夹在 0..1。
 */
class SpectrumTeeTest {

    private fun pcm16(frames: Int, amplitude: Short): ByteBuffer =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames * 2) { putShort(amplitude) }
            flip()
        }

    private fun configure(
        tee: TeeAudioProcessor,
        encoding: Int = C.ENCODING_PCM_16BIT,
        channels: Int = 2,
        sampleRate: Int = 44_100,
    ) {
        tee.configure(AudioProcessor.AudioFormat(sampleRate, channels, encoding))
    }

    // ---- 门控与计数器 -------------------------------------------------------

    @Test
    fun `门全关时零分析只透传`() {
        val tee = TeeAudioProcessor()
        configure(tee)
        // 不调 setGates：三门默认全关。
        repeat(3) { tee.queueInput(pcm16(1024, 24_000)) }

        assertEquals(0L, tee.analyzedBuffers)
        assertEquals(3L, tee.skippedBuffers)
        assertEquals(0f, tee.level.value, 0f)
    }

    @Test
    fun `门全开时有分析且电平上升`() {
        val tee = TeeAudioProcessor()
        configure(tee)
        tee.setGates(playing = true, visible = true, enabled = true)
        tee.queueInput(pcm16(1024, 24_000))

        assertEquals(1L, tee.analyzedBuffers)
        assertEquals(0L, tee.skippedBuffers)
        // 24_000/32768 ≈ 0.73 的满幅方波：一缓冲（约 46ms）就该有明显电平。
        assertTrue("电平应 > 0，实际 ${tee.level.value}", tee.level.value > 0f)
        assertTrue("电平应 <= 1，实际 ${tee.level.value}", tee.level.value <= 1f)
    }

    @Test
    fun `门再关上时电平立即归零且跳过计数继续涨`() {
        val tee = TeeAudioProcessor()
        configure(tee)
        tee.setGates(true, true, true)
        tee.queueInput(pcm16(1024, 24_000))
        assertTrue(tee.level.value > 0f)

        tee.setGates(playing = false, visible = false, enabled = false)
        assertEquals(0f, tee.level.value, 0f)
        repeat(2) { tee.queueInput(pcm16(1024, 24_000)) }
        assertEquals(1L, tee.analyzedBuffers)
        assertEquals(2L, tee.skippedBuffers)
    }

    @Test
    fun `三个门缺任何一个都停算`() {
        // 枚举三门里"只有一个开"的三种组合，每一种都必须零分析。
        val combos = listOf(
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, false),
            Triple(false, false, false),
        )
        for ((playing, visible, enabled) in combos) {
            val tee = TeeAudioProcessor()
            configure(tee)
            tee.setGates(playing, visible, enabled)
            tee.queueInput(pcm16(512, 24_000))
            assertEquals("playing=$playing visible=$visible enabled=$enabled", 0L, tee.analyzedBuffers)
            assertEquals(1L, tee.skippedBuffers)
            assertEquals(0f, tee.level.value, 0f)
        }
    }

    // ---- 透传正确性（音频不能断、不能坏）-----------------------------------

    @Test
    fun `透传的PCM与输入逐字节一致`() {
        val tee = TeeAudioProcessor()
        configure(tee)
        tee.setGates(true, true, true)
        val input = pcm16(2048, 12_345.toShort())
        tee.queueInput(input)

        val output = tee.getOutput()
        assertEquals(input.capacity(), output.remaining())
        val inBytes = input.array().copyOf()
        val outBytes = ByteArray(output.remaining()).also { output.get(it) }
        assertTrue("透传结果必须与输入一致", inBytes.contentEquals(outBytes))
    }

    @Test
    fun `空缓冲不进计数器`() {
        val tee = TeeAudioProcessor()
        configure(tee)
        tee.setGates(true, true, true)
        tee.queueInput(ByteBuffer.allocate(0))
        assertEquals(0L, tee.analyzedBuffers)
        assertEquals(0L, tee.skippedBuffers)
    }

    // ---- 包络 ---------------------------------------------------------------

    @Test
    fun `静音缓冲后电平缓慢衰减但不会立刻归零`() {
        val tee = TeeAudioProcessor()
        configure(tee)
        tee.setGates(true, true, true)
        tee.queueInput(pcm16(1024, 24_000))
        val loud = tee.level.value
        assertTrue(loud > 0f)

        var previous = loud
        repeat(5) {
            tee.queueInput(pcm16(1024, 0))
            val now = tee.level.value
            assertTrue("静音下电平应单调下降（$previous → $now）", now < previous)
            previous = now
        }
        assertTrue("衰减应是渐变的（5 个缓冲后仍 > 0）", previous > 0f)

        // 约 60 个缓冲（约 2.8s 音频）后应衰减到接近零。
        repeat(55) { tee.queueInput(pcm16(1024, 0)) }
        assertTrue("长时间静音后应收敛到接近零，实际 ${tee.level.value}", tee.level.value < 0.05f)
    }

    @Test
    fun `flush与reset后电平归零`() {
        val tee = TeeAudioProcessor()
        configure(tee)
        tee.setGates(true, true, true)
        tee.queueInput(pcm16(1024, 24_000))
        assertTrue(tee.level.value > 0f)

        tee.flush()
        assertEquals("flush 后电平应归零（换歌/seek 不带旧响度）", 0f, tee.level.value, 0f)

        tee.queueInput(pcm16(1024, 24_000))
        assertTrue(tee.level.value > 0f)
        tee.reset()
        assertEquals(0f, tee.level.value, 0f)
    }

    // ---- 纯函数 -------------------------------------------------------------

    @Test
    fun `rmsToLevel边界与单调`() {
        assertEquals(0f, rmsToLevel(0f), 0f)
        assertEquals(0f, rmsToLevel(-1f), 0f)
        assertEquals(1f, rmsToLevel(1f), 1e-6f)
        assertEquals(1f, rmsToLevel(100f), 0f)
        assertTrue(rmsToLevel(0.05f) < rmsToLevel(0.1f))
        assertTrue(rmsToLevel(0.1f) < rmsToLevel(0.3f))
        // 中等响度要占得到大半行程（开方的意义）。
        assertTrue("0.1 的 RMS 应映射到 0.5 以上，实际 ${rmsToLevel(0.1f)}", rmsToLevel(0.1f) > 0.5f)
    }

    @Test
    fun `包络上升快于回落`() {
        val up = envelopeStep(0f, 1f, dtMs = 100f, attackMs = 30f, releaseMs = 350f)
        val down = 1f - envelopeStep(1f, 0f, dtMs = 100f, attackMs = 30f, releaseMs = 350f)
        assertTrue("同样 100ms，上升应远大于回落（up=$up down=$down）", up > down * 2f)
    }

    @Test
    fun `包络步进的边界`() {
        // dt <= 0：不动。
        assertEquals(0.4f, envelopeStep(0.4f, 1f, dtMs = 0f, attackMs = 30f, releaseMs = 350f), 0f)
        // 时间常数为 0：一步到位。
        assertEquals(0.9f, envelopeStep(0.2f, 0.9f, dtMs = 10f, attackMs = 0f, releaseMs = 0f), 0f)
        // 永远夹在 0..1（目标越界也一样）。
        assertTrue(envelopeStep(0.5f, 5f, 1000f, 30f, 350f) <= 1f)
        assertTrue(envelopeStep(0.5f, -5f, 1000f, 30f, 350f) >= 0f)
        // 收敛方向：朝目标靠近但不超过。
        val toward = envelopeStep(0.2f, 0.8f, dtMs = 10f, attackMs = 30f, releaseMs = 350f)
        assertTrue(toward in 0.2f..0.8f)
    }

    @Test
    fun `不认识的编码不猜格式`() {
        val tee = TeeAudioProcessor()
        // 8BIT 不是音频链会给 QQ 流媒体的格式，但万一来了：不透传分析、不瞎映射。
        configure(tee, encoding = C.ENCODING_PCM_8BIT, channels = 1)
        tee.setGates(true, true, true)
        tee.queueInput(ByteBuffer.allocate(1024))
        assertEquals(1L, tee.analyzedBuffers)
        assertEquals(0f, tee.level.value, 0f)
    }
}
