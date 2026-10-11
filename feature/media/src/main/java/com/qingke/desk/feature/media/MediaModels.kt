package com.qingke.desk.feature.media

import android.graphics.Bitmap

/**
 * 当前正在播放的曲目快照。
 *
 * 除了 [packageName] 之外**全部可能是空值**：第三方播放器的 MediaSession
 * 填充质量参差不齐（有的只给标题、有的给的是 URI 而不是 Bitmap、有的 duration 是 -1）。
 * 所以这里没有任何非空断言，UI 侧每项都要能承受缺省。
 *
 * @param positionMs [positionTakenAtMs] 那一刻的播放位置。因为它是一次性采样值，
 *                   界面要显示"走动"的进度，必须结合这两个字段自己推算，
 *                   不能拿 [positionMs] 当实时值一直用。
 */
data class MediaTrack(
    val title: String,
    val artist: String,
    val album: String? = null,
    /** 来源应用包名，用于在卡片上标注"谁在播"。 */
    val sourcePackage: String,
    val artwork: Bitmap? = null,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    /** 取到 [positionMs] 的墙上时间（SystemClock.elapsedRealtime）。 */
    val positionTakenAtMs: Long,
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
    val canPlayPause: Boolean = false,
) {

    /** 有效时长。第三方常填 -1 或 0 表示"未知"。 */
    val hasDuration: Boolean get() = durationMs > 0

    companion object {
        const val UNKNOWN_POSITION: Long = -1L
    }
}

/**
 * 卡片可处于的四种状态。
 *
 * [NoPermission] 必须单独成态而不是"降级成空卡片"：用户看到的是"为什么这里没东西"，
 * 而原因不在应用内、要去系统设置里开 —— 不显式给出就没法自助解决。
 */
sealed interface MediaUiState {

    /** 还没做完第一次检查。首帧瞬态。 */
    data object Checking : MediaUiState

    /** 通知使用权没开。卡片整体可点，点了跳设置页。 */
    data object NoPermission : MediaUiState

    /** 已授权，但当前没有任何活跃会话（没有任何 App 在播 / 播完退出了）。 */
    data object Idle : MediaUiState

    /** 已授权且有会话。 */
    data class Active(val track: MediaTrack) : MediaUiState
}

enum class MediaCommand {
    PREVIOUS,
    PLAY_PAUSE,
    NEXT,
}
