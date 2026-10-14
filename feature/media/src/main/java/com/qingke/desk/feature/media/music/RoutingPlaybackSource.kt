package com.qingke.desk.feature.media.music

import com.qingke.desk.feature.media.MediaCommand
import com.qingke.desk.feature.media.MediaTrack
import com.qingke.desk.feature.media.MediaUiState
import com.qingke.desk.feature.media.music.online.QqLog
import com.qingke.desk.feature.media.music.spectrum.SpectrumControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 双源组合器：**QQ 自播源（[ExoPlayerPlaybackSource]）优先，系统会话源兜底**。
 *
 * ---------------------------------------------------------------------------
 * 为什么需要它（而不是让页面自己二选一）
 *
 * 音乐页与首页音乐卡都只认 [PlaybackSource] 这一个抽象（见 [PlaybackSource] 的类注释：
 * "换实现时页面一行不动"）。现在有两个实现，而"该显示谁"是一条**跨源**的判断 ——
 * 它既要知道 QQ 源在不在播，也要知道有没有别的 App 在播。这条判断必须有一个唯一的家，
 * 否则两个页面会各判一次、然后在同一秒钟显示不同的东西。
 *
 * ---------------------------------------------------------------------------
 * 择优规则（按序，先匹配先生效）
 *
 * 1. **QQ 源正在播** → QQ（用户刚从抽屉里点了歌，这时候别的 App 插进来也不该抢画面）；
 * 2. **QQ 源有曲目（暂停中）且没有任何源在播** → QQ（暂停后 UI 还得在，否则用户
 *    没有办法从这一页恢复播放 —— 首页卡与音乐页会一起退回"暂无播放"）；
 * 3. 其余 → 系统会话源（[MediaSessionPlaybackSource]，跟随别的 App）。
 *
 * 第 2 条是刻意加的"粘性"：没有它，用户在音乐页按下暂停，HUD 会当场消失。
 * 它的代价是"别的 App 暂停播放时画面不切" —— 但那时候用户本来也没在听，
 * 显示哪一边都是死数据，粘在自己刚操作过的那一边更不容易困惑。
 *
 * ---------------------------------------------------------------------------
 * 自激（self-excitation）是怎么被挡住的
 *
 * 我们自己发布的 MediaSession 会被 [com.qingke.desk.feature.media.MediaSessionMonitor]
 * 当成第三方会话读回来，形成"卡片显示我自己在播我自己"的环。两道防线：
 *
 *  · **第一道（结构性）**：monitor 的 `pick()` 过滤本应用包名 —— 我们自己发的会话
 *    永远不会进 monitor 的状态流；
 *  · **第二道（择优性）**：即使过滤漏了，本类在 QQ 源活跃时也优先选 QQ，
 *    monitor 的本包会话没有机会上屏。
 *
 * 两道的**验证方式是一个能证伪的用例**：QQ 播放中，monitor 的原始会话列表里
 * 不允许出现本包名（`com.qingke.desk`）。第一道让它恒不出现；真出现了，
 * 说明过滤被改坏或者有别的路径在发会话 —— 那是回归，不是噪声。
 *
 * @param qq QQ 自播源。壳层创建、随应用会话存活（切页不停播）。
 * @param session 系统会话源。它的 start/stop 归本类管（页面不再直接碰它）。
 *
 * 频谱旁路（[SpectrumControl]）只可能来自 [qq] —— 系统会话源没有自己的播放器，
 * 所以直接委派；这也是"页面拿到的 spectrum 非空 ⟺ 有 QQ 自播源"的由来。
 */
class RoutingPlaybackSource(
    private val qq: ExoPlayerPlaybackSource,
    private val session: MediaSessionPlaybackSource,
    private val scope: CoroutineScope,
) : PlaybackSource, QqMusicLibrary by qq, SpectrumControl by qq {

    private val _status = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Checking)
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    /**
     * 首页音乐卡的状态。与 [status] **同一次择优的结果**，只是换成卡片的契约
     * （[MediaUiState]）—— 两张界面永远看到同一个胜者，不存在"音乐页显示 QQ、
     * 首页卡显示系统会话"的各判一次。
     */
    private val _mediaUiState = MutableStateFlow<MediaUiState>(MediaUiState.Checking)
    val mediaUiState: StateFlow<MediaUiState> = _mediaUiState.asStateFlow()

    private var job: Job? = null

    /** 上一次的胜者。只为日志服务（切换时留一行文件证据）。 */
    private var lastWinner: PlaybackSource? = null

    override fun start() {
        if (job != null) return
        qq.start()
        session.start()
        job = scope.launch {
            combine(qq.status, session.status) { qqStatus, sessionStatus ->
                qqStatus to sessionStatus
            }.collect { (qqStatus, sessionStatus) ->
                route(qqStatus, sessionStatus)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        session.stop()
        qq.stop()
    }

    override fun refresh() = currentWinner().refresh()

    override fun previous() = currentWinner().previous()

    override fun playPause() = currentWinner().playPause()

    override fun next() = currentWinner().next()

    override fun seekTo(positionMs: Long) = currentWinner().seekTo(positionMs)

    /**
     * 首页卡的三键控制。**发给当前胜者**，不是固定发给某一个源 ——
     * 卡片上显示的是谁，按键就该作用到谁（发错了的症状是"按暂停，别人的播放器动了"）。
     */
    fun command(command: MediaCommand) {
        when (command) {
            MediaCommand.PREVIOUS -> currentWinner().previous()
            MediaCommand.PLAY_PAUSE -> currentWinner().playPause()
            MediaCommand.NEXT -> currentWinner().next()
        }
    }

    // ---- 内部 ---------------------------------------------------------------

    /** 纯函数版的择优规则。路由与指令分发共用它，两处不可能漂开。 */
    private fun preferQq(qqStatus: PlaybackStatus, sessionStatus: PlaybackStatus): Boolean {
        val qqActive = qqStatus as? PlaybackStatus.Active ?: return false
        if (qqActive.track.isPlaying) return true
        val sessionActive = sessionStatus as? PlaybackStatus.Active ?: return true
        return !sessionActive.track.isPlaying
    }

    private fun currentWinner(): PlaybackSource =
        if (preferQq(qq.status.value, session.status.value)) qq else session

    private fun route(qqStatus: PlaybackStatus, sessionStatus: PlaybackStatus) {
        val useQq = preferQq(qqStatus, sessionStatus)
        val chosen = if (useQq) qqStatus else sessionStatus
        _status.value = chosen
        _mediaUiState.value = chosen.toMediaUiState()

        val winner = if (useQq) qq else session
        if (winner !== lastWinner) {
            lastWinner = winner
            QqLog.i(
                TAG,
                "双源切换 -> ${winnerName(winner)}" +
                    "（qq=${label(qqStatus)} monitor=${label(sessionStatus)}）",
            )
        }
    }

    private fun winnerName(source: PlaybackSource): String = when (source) {
        qq -> "QQ自播"
        session -> "系统会话"
        else -> source.javaClass.simpleName
    }

    private fun label(status: PlaybackStatus): String = when (status) {
        PlaybackStatus.Checking -> "Checking"
        PlaybackStatus.Idle -> "Idle"
        PlaybackStatus.NoPermission -> "NoPermission"
        is PlaybackStatus.Active -> "Active(${status.track.title.take(12)} playing=${status.track.isPlaying})"
    }

    private companion object {
        const val TAG = "DualSource"
    }
}

/** 播放页的契约翻成首页卡的契约（[MediaSessionPlaybackSource] 里那个映射的逆运算）。 */
private fun PlaybackStatus.toMediaUiState(): MediaUiState = when (this) {
    PlaybackStatus.Checking -> MediaUiState.Checking
    PlaybackStatus.NoPermission -> MediaUiState.NoPermission
    PlaybackStatus.Idle -> MediaUiState.Idle
    is PlaybackStatus.Active -> MediaUiState.Active(track.toMediaTrack())
}

private fun NowPlaying.toMediaTrack(): MediaTrack = MediaTrack(
    title = title,
    artist = artist,
    album = album,
    sourcePackage = sourcePackage,
    artwork = artwork,
    isPlaying = isPlaying,
    positionMs = positionMs,
    durationMs = durationMs,
    positionTakenAtMs = positionTakenAtMs,
    canSkipPrevious = canPrevious,
    canSkipNext = canNext,
    canPlayPause = canPlayPause,
    canSeek = canSeek,
)
