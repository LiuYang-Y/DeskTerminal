package com.qingke.desk.feature.media.music

import android.graphics.Bitmap
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放中的曲目快照。
 *
 * 与 [com.qingke.desk.feature.media.MediaTrack] 形状接近但**不是同一个类型**，
 * 这一点是刻意的：[MediaTrack] 是"媒体卡片"的视图模型（要带来源应用、要能缺省），
 * 而这里是"播放页"的数据契约 —— 以后换成 ExoPlayer 或本地曲库时，
 * 这个类由另一条实现来产出，页面一行都不用改。
 *
 * 除 [title] / [sourcePackage] 外全部可能缺省：第三方播放器的 MediaSession
 * 填充质量参差不齐（有的只给标题、有的 duration 是 -1）。
 *
 * @param positionMs [positionTakenAtMs] 那一刻的播放位置。它是一次性采样值，
 *                   界面要显示"走动"的进度必须用 [positionAt] 自己外推。
 */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String?,
    val artwork: Bitmap?,
    /** 来源应用包名，用于在页面上标一句"谁在播"。允许为空串。 */
    val sourcePackage: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val positionTakenAtMs: Long,
    val durationMs: Long,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val canPlayPause: Boolean,
    val canSeek: Boolean,
) {

    /** 有效时长。第三方常填 -1 或 0 表示"未知"。 */
    val hasDuration: Boolean get() = durationMs > 0

    /**
     * 曲目身份键。**用于"每首歌只算一次"的派生计算**（封面取色、底图模糊、歌词拉取）。
     *
     * 刻意不用 `hashCode()`：一来要能写进日志、二来要能直接当缓存文件名用。
     * 时长取整到秒 —— 同一首歌在不同会话里毫秒位可能差几毫秒，
     * 带上毫秒会让缓存整片失效（代价是"同名同歌手同时长"会被当成同一首，
     * 但那种情况本来就无法从 MediaSession 区分）。
     *
     * 封面**不参与**这个键：artwork 是 Bitmap，没有稳定标识，
     * 而且换封面必然伴随元数据变化。
     */
    val frameKey: String
        get() = buildString {
            append(title.trim())
            append('\u0001')
            append(artist.trim())
            append('\u0001')
            append(album.orEmpty().trim())
            append('\u0001')
            append(durationMs / 1000L)
        }

    /**
     * 按墙上时间外推当前播放位置（毫秒）。
     *
     * 播放中 = 采样值 + 采样后经过的时间；暂停/未知时就是采样值本身。
     * 不做任何夹取 —— 调用方按需要夹到 `0..durationMs`（进度条要夹，
     * 歌词匹配反而**不该**夹到时长上，末尾的歌词行可能超出 duration）。
     */
    fun positionAt(nowMs: Long): Long {
        if (positionMs < 0L) return 0L
        if (!isPlaying) return positionMs
        val elapsed = (nowMs - positionTakenAtMs).coerceAtLeast(0L)
        return positionMs + elapsed
    }
}

/**
 * 播放源的对外状态。
 *
 * [NoPermission] 必须单独成态：用户看到的是"为什么这里没东西"，
 * 而原因在系统设置里（通知使用权），不显式给出就没法自助解决。
 */
sealed interface PlaybackStatus {

    /** 还没做完第一次检查。首帧瞬态。 */
    data object Checking : PlaybackStatus

    /** 通知使用权没开。 */
    data object NoPermission : PlaybackStatus

    /** 已授权，但当前没有任何活跃会话。 */
    data object Idle : PlaybackStatus

    /** 已授权且有会话。 */
    data class Active(val track: NowPlaying) : PlaybackStatus
}

/**
 * 播放源抽象。
 *
 * ---------------------------------------------------------------------------
 * 为什么要有这一层
 *
 * 路线 D 的第一版**不自己解码播放**，只读系统的媒体会话（第三方 App 在播什么）。
 * 但"以后换成 ExoPlayer 或本地曲库"是确定要走的路，届时换掉的是**数据从哪来**，
 * 而不是页面长什么样。所以页面只认这个接口：它不知道 MediaSession 的存在，
 * 也不认识 `MediaController`。
 *
 * 这条边界的价值在换实现那天才兑现：接口下面的实现可以整个替换，
 * 上面的布局、动效、歌词、进度条一行都不动。
 *
 * ---------------------------------------------------------------------------
 * 线程约定
 *
 * [status] 是 `StateFlow`，**在哪个线程更新由实现自己保证**（当前实现保证在主线程）。
 * 其余方法可以从任意线程调，实现内部各自 post 回主线程 —— 界面不必关心。
 */
interface PlaybackSource {

    /** 当前播放状态。界面唯一的数据来源。 */
    val status: StateFlow<PlaybackStatus>

    /** 开始监视。与 [stop] 成对调用；重复调用要幂等。 */
    fun start()

    /** 停止监视并注销所有回调。 */
    fun stop()

    /**
     * 立刻重查一遍。
     *
     * 授权状态可能在别处被改（用户去系统设置里勾了通知使用权），
     * 所以"回到前台"必须重查，否则页面会一直停在过期的 [PlaybackStatus.NoPermission]。
     */
    fun refresh()

    fun previous()

    fun playPause()

    fun next()

    /** 跳转进度。不支持跳转的源应当**静默忽略**而不是抛异常。 */
    fun seekTo(positionMs: Long)
}
