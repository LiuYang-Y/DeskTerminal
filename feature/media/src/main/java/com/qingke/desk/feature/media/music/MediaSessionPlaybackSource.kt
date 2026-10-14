package com.qingke.desk.feature.media.music

import com.qingke.desk.feature.media.MediaCommand
import com.qingke.desk.feature.media.MediaSessionMonitor
import com.qingke.desk.feature.media.MediaTrack
import com.qingke.desk.feature.media.MediaUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [PlaybackSource] 的第一版实现：**只读系统媒体会话**，不自己解码播放。
 *
 * 这是路线 D 的起点 —— 音乐页在"谁在播"这件事上完全交给系统，
 * 因此不需要任何音频权限、不需要前台 Service，也不会与第三方播放器抢音频焦点。
 * 代价是"选歌"这件事没有（曲库在别人的 App 里），所以这一版的页面是**看**为主。
 *
 * 把 [MediaSessionMonitor] 的 [MediaUiState] 翻成 [PlaybackStatus]，是这一层**唯一**的职责。
 * 刻意不做任何缓存或补齐：源数据是什么样，页面就看到什么样 ——
 * 一旦这里开始"猜"（比如 duration 为空时补一个默认值），
 * 以后换 ExoPlayer 时就会有两套不同的行为要维护。
 *
 * ---------------------------------------------------------------------------
 * 为什么**注入** monitor 而不是自己 new 一个
 *
 * 首页那张媒体卡已经在用同一个 monitor 了。各建一个的话，
 * 同一台设备上会挂两份会话回调、两次 `getActiveSessions` —— 数据一样，
 * 白白多一层 binder 往返，而且两份状态的刷新时机还可能不一致。
 * 所以 monitor 的**生命周期归壳层**（`start`/`stop` 由 DeskRoot 管），
 * 本类只负责"开始/停止观察它"，这正是 [PlaybackSource.start] 的语义。
 *
 * 以后换 ExoPlayer 时，新实现注入的就是 ExoPlayer 而不是 monitor，页面依旧不动。
 *
 * @param scope 收集上游状态用的作用域。**由调用方给**（壳层传 `rememberCoroutineScope()`），
 *              这样它的生命周期跟着组合走，本类不持有任何需要手动释放的资源。
 */
class MediaSessionPlaybackSource(
    private val monitor: MediaSessionMonitor,
    private val scope: CoroutineScope,
) : PlaybackSource {

    private val _status = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Checking)
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private var job: Job? = null

    override fun start() {
        if (job != null) return
        // 上游 [MediaSessionMonitor.state] 初值就是 Checking，collect 会先把它灌进来，
        // 所以这里不需要先手动赋一次值。
        job = scope.launch {
            monitor.state.collect { _status.value = it.toPlaybackStatus() }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    override fun refresh() = monitor.refresh()

    override fun previous() = monitor.command(MediaCommand.PREVIOUS)

    override fun playPause() = monitor.command(MediaCommand.PLAY_PAUSE)

    override fun next() = monitor.command(MediaCommand.NEXT)

    override fun seekTo(positionMs: Long) = monitor.seekTo(positionMs)
}

/** 把媒体卡片的视图模型翻成播放页的数据契约。 */
private fun MediaUiState.toPlaybackStatus(): PlaybackStatus = when (this) {
    MediaUiState.Checking -> PlaybackStatus.Checking
    MediaUiState.NoPermission -> PlaybackStatus.NoPermission
    MediaUiState.Idle -> PlaybackStatus.Idle
    is MediaUiState.Active -> PlaybackStatus.Active(track.toNowPlaying())
}

private fun MediaTrack.toNowPlaying(): NowPlaying = NowPlaying(
    title = title,
    artist = artist,
    album = album,
    artwork = artwork,
    sourcePackage = sourcePackage,
    isPlaying = isPlaying,
    positionMs = positionMs,
    positionTakenAtMs = positionTakenAtMs,
    durationMs = durationMs,
    canPrevious = canSkipPrevious,
    canNext = canSkipNext,
    canPlayPause = canPlayPause,
    canSeek = canSeek,
)
