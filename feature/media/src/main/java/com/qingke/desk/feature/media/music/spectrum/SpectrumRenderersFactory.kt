package com.qingke.desk.feature.media.music.spectrum

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * 把 [TeeAudioProcessor] 注入 ExoPlayer 音频链的渲染器工厂。
 *
 * ---------------------------------------------------------------------------
 * 为什么走这条路（而不是别的方式拿 PCM）
 *
 * media3 没有公开 API 能"顺带"拿到解码后的 PCM。留给产品的缝就是
 * `DefaultRenderersFactory.buildAudioSink(Context, boolean, boolean)`
 * （protected，javap 实测 1.11.1 签名就是这个三个参数）—— 它拿到的两个布尔是
 * ExoPlayer.Builder 传下来的设备能力开关，**必须原样透传**：
 * 丢了 `enableFloatOutput` 会改变浮点输出路径，丢了
 * `enableAudioTrackPlaybackParams` 会禁掉变速不变调，都是平白的功能损失。
 *
 * `setAudioProcessors(arrayOf(tee))` 是**整体替换**默认的空数组
 * （默认链里本来就没有用户处理器）；DefaultAudioSink 内部仍会自己补
 * 声道映射等必要处理器，不会因为这一个数组就少了什么。
 *
 * **为什么用无参 `DefaultAudioSink.Builder()` 而不是 `Builder(context)`**：
 * 带 context 的构造会查询设备能力，可能启用 encoded audio passthrough / offload ——
 * 那条路上 PCM 不过软件链，[TeeAudioProcessor] 什么都看不到（症状是"歌在放、
 * 圆环死着"，比没有频谱更难查）。无参构造用 DEFAULT 能力集（不支持编码直通），
 * 音频必然走解码 → PCM 路径，tee 才一定拿得到数据。覆写收到的 context 参数
 * **故意不用**，两个能力布尔必须透传（见下）。
 *
 * 壳层把本工厂交给 [com.qingke.desk.feature.media.music.ExoPlayerPlaybackSource]
 * 的 `renderersFactory` 参数即可（那个参数第 2 步就留好了）。
 */
class SpectrumRenderersFactory(
    context: Context,
    private val tee: TeeAudioProcessor,
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = DefaultAudioSink.Builder()
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .setAudioProcessors(arrayOf(tee))
        .build()
}
