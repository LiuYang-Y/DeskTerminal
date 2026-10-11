package com.qingke.desk.feature.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.ui.DeskDimens
import kotlinx.coroutines.delay
import android.os.SystemClock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember

/** 卡片底色。与 feature:home 里 MUSIC 那档保持一致。 */
private val MediaCardColor = Color(0xFF9A4E4E)

private val ArtSize: Dp = 60.dp
private val ControlSize: Dp = 40.dp
private val ControlIcon: Dp = 26.dp

/**
 * 音乐卡片。取代 feature:home 里那张写死的占位 MUSIC 卡，
 * 内容全部来自 MediaSession。
 *
 * 三种状态各有各的样式：
 *  - [MediaUiState.NoPermission] 整张卡可点，点了去开通知使用权；
 *  - [MediaUiState.Idle] 纯信息，说明"为什么这里是空的"；
 *  - [MediaUiState.Active] 封面 + 歌名 + 艺术家 + 三键控制。
 *
 * **高度一律不写死。** 卡片实际高度由栅格按可用高度反推（见 :shell/HomeScreen），
 * 基准机实测内容区只有约 88 dp，所以这里用 SpaceBetween 让三段自己分摊余量，
 * 换台机器卡片变矮也不会把某一行挤没。
 */
@Composable
fun MediaCardTile(
    state: MediaUiState,
    onCommand: (MediaCommand) -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier,
    driftX: Dp = 0.dp,
    driftY: Dp = 0.dp,
) {
    Card(
        modifier = modifier
            .fillMaxSize()
            .offset(x = driftX, y = driftY),
        shape = RoundedCornerShape(DeskDimens.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MediaCardColor),
    ) {
        when (state) {
            MediaUiState.Checking -> Placeholder(
                icon = { Icon(Icons.Default.MusicNote, null, Modifier.size(28.dp), Color.White) },
                title = "正在检查媒体状态",
                subtitle = "",
            )

            MediaUiState.NoPermission -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onRequestPermission),
                contentAlignment = Alignment.Center,
            ) {
                Placeholder(
                    icon = {
                        Icon(
                            Icons.Default.NotificationsOff,
                            null,
                            Modifier.size(28.dp),
                            Color.White,
                        )
                    },
                    title = "需要通知使用权",
                    subtitle = "点击前往系统设置开启",
                )
            }

            MediaUiState.Idle -> Placeholder(
                icon = { Icon(Icons.Default.MusicNote, null, Modifier.size(28.dp), Color.White) },
                title = "暂无播放",
                subtitle = "在音乐 App 里开始播放后会显示在这里",
            )

            is MediaUiState.Active -> ActiveBody(
                track = state.track,
                onCommand = onCommand,
            )
        }
    }
}

@Composable
private fun ActiveBody(
    track: MediaTrack,
    onCommand: (MediaCommand) -> Unit,
) {
    // 进度自己按秒走（见 progressOf 的注释），这里只负责"让界面每秒重算一次"。
    var nowMs by remember(track) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track.isPlaying, track.positionMs, track.positionTakenAtMs) {
        if (!track.isPlaying) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            nowMs = SystemClock.elapsedRealtime()
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(DeskDimens.cardPadding),
    ) {
        // 窄卡走另一套排布：**封面先让位**。
        //
        // 地图布局把卡片从 230 dp 压到约 150 dp 之后，内容区只剩约 118 dp，
        // 而封面自己就占 60 dp + 12 dp 间距 —— 剩下 46 dp 里排三个 40 dp 的控制键，
        // 结果是被裁到只剩半个图标（真机截图确认：三键全废）。
        // 控制键是这张卡唯一能操作的部分，不能让它第一个被牺牲，所以这里
        // 放弃封面，把宽度还给标题和三键。
        // maxWidth 属于 BoxWithConstraintsScope，进到 Column / Row 的 content lambda
        // 之后隐式 receiver 就换成 ColumnScope / RowScope 了，这里先取出来带过去。
        val availableWidth = maxWidth
        val narrow = !canKeepArtwork(availableWidth)

        val titleBlock: @Composable () -> Unit = { TitleBlock(track) }
        val progressBlock: @Composable () -> Unit = { ProgressBlock(track, nowMs) }

        if (narrow) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                titleBlock()
                progressBlock()
                Controls(track, onCommand, narrowControlSize(availableWidth))
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(ArtGap),
            ) {
                Artwork(track)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    titleBlock()
                    progressBlock()
                    Controls(track, onCommand, ControlSize)
                }
            }
        }
    }
}

/**
 * 封面能不能留。
 *
 * 判据不是"卡片有多宽"，而是"留了封面之后三个控制键还排不排得下"——
 * 因为这张卡唯一的操作就是这三个键，它们比封面重要得多。
 *
 * 基准机实测：
 *  - 首页三列时内容区 198 dp，留封面后剩 126 dp，三个 40 dp 键要 120 dp —— 刚好够，封面保留；
 *  - 地图布局把卡片压到约 150 dp，留封面后只剩约 46 dp —— 排不下，封面让位。
 * 原先按"卡片宽度 < 200 dp 就算窄"一刀切，结果首页（198 dp）也被误判成窄卡把封面丢了，
 * 所以改成这种"按内容算"的写法。
 */
private val ArtGap: Dp = 12.dp

private fun canKeepArtwork(available: Dp): Boolean =
    available - ArtSize - ArtGap >= ControlSize * 3

/** 窄卡里控制键的边长：均分可用宽度，但不小于 28 dp（手臂距离外的最小可点尺寸）。 */
private fun narrowControlSize(available: Dp): Dp = ((available - 8.dp) / 3).coerceIn(28.dp, ControlSize)

@Composable
private fun TitleBlock(track: MediaTrack) {
    Column {
        Text(
            text = track.title,
            fontSize = 17.sp,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitleOf(track),
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.72f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProgressBlock(track: MediaTrack, nowMs: Long) {
    if (track.hasDuration) {
        // 注意：Material3 的 progress 参数**不是**可组合 lambda，
        // 里面不能调 @Composable 函数。所以实时进度在外面算好再以普通值传进来。
        val progress = progressOf(track, nowMs)
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.22f),
        )
    } else {
        Spacer(modifier = Modifier.height(4.dp))
    }
}

/**
 * 实时进度。
 *
 * MediaSession 给的 position 是**采样瞬间**的值，不会自己走；播放中要靠
 * "采样后经过的时间"自己补上去，暂停就停在采样值。
 * 只在 [MediaTrack.hasDuration] 为真时被调用，所以 duration 一定大于 0。
 *
 * 刻意做成普通函数而不是 @Composable：调用点处在 Material3 的 progress lambda 里，
 * 那个 lambda 不提供可组合上下文。
 */
private fun progressOf(track: MediaTrack, nowMs: Long): Float {
    if (track.positionMs < 0) return 0f
    val elapsed = if (track.isPlaying) (nowMs - track.positionTakenAtMs).coerceAtLeast(0) else 0L
    val current = (track.positionMs + elapsed).coerceAtMost(track.durationMs)
    return (current.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f)
}

@Composable
private fun Artwork(track: MediaTrack) {
    val bitmap: Bitmap? = track.artwork
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(ArtSize)
                .clip(RoundedCornerShape(10.dp)),
        )
    } else {
        Box(
            modifier = Modifier
                .size(ArtSize)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(ArtSize * 0.5f),
            )
        }
    }
}

@Composable
private fun Controls(
    track: MediaTrack,
    onCommand: (MediaCommand) -> Unit,
    buttonSize: Dp,
) {
    // SpaceEvenly 而不是 spacedBy：三个按钮在基准机只有约 126 dp 可用宽，
    // 固定间距会顶出去，让它们自己均分才不会溢出。
    // 图标随按钮一起缩，否则 28 dp 的圈里塞 26 dp 的图标会顶边。
    val iconSize = minOf(ControlIcon, buttonSize * 0.65f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(
            size = buttonSize,
            enabled = track.canSkipPrevious,
            onClick = { onCommand(MediaCommand.PREVIOUS) },
        ) {
            Icon(
                imageVector = Icons.Default.SkipPrevious,
                contentDescription = "上一首",
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
        ControlButton(
            size = buttonSize,
            enabled = track.canPlayPause,
            onClick = { onCommand(MediaCommand.PLAY_PAUSE) },
        ) {
            Icon(
                imageVector = if (track.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (track.isPlaying) "暂停" else "播放",
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
        ControlButton(
            size = buttonSize,
            enabled = track.canSkipNext,
            onClick = { onCommand(MediaCommand.NEXT) },
        ) {
            Icon(
                imageVector = Icons.Default.SkipNext,
                contentDescription = "下一首",
                tint = Color.White,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * 副标题：艺术家 · 来源应用 · 专辑。
 *
 * 顺序是按"截断时先丢谁"排的。基准机上这一行只有约 126 dp 宽，
 * 长一点必然被省略号截掉，所以把最不重要的专辑放最后（截图实测：
 * 专辑排在来源前面时，"QQ音乐"这个标签会被整段吃掉）。
 *
 * 每一段都做去重与去空 —— 实测 QQ 音乐会把**专辑名填成歌名**，
 * 照单全收会渲染出"非酋 / 薛明媛/朱贺 · 非酋"这种自我重复。
 */
private fun subtitleOf(track: MediaTrack): String {
    val parts = buildList {
        if (track.artist.isNotBlank()) add(track.artist)
        sourceLabel(track.sourcePackage).takeIf { it.isNotBlank() }?.let(::add)
        val album = track.album.orEmpty()
        if (album.isNotBlank() && album != track.artist && album != track.title) add(album)
    }
    return parts.joinToString(" · ").ifBlank { "未知艺术家" }
}

/**
 * 控制按钮。
 *
 * 用 Box + clickable 而不是 IconButton：IconButton 为了保证 48dp 触控区会自带
 * 一圈内边距，而这张卡内容区只有约 88 dp 高，塞三个 IconButton 就顶出去了。
 * 桌面终端在手臂距离外操作，40 dp 已经够按。
 */
@Composable
private fun ControlButton(
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun Placeholder(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(DeskDimens.cardPadding),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontSize = DeskDimens.cardBody,
                color = Color.White,
                maxLines = 1,
            )
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = DeskDimens.cardCaption,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 把包名压成一个短标签。认得的给中文名，认不得的退成包名最后一段。 */
private fun sourceLabel(packageName: String): String = when (packageName) {
    "com.tencent.qqmusic" -> "QQ音乐"
    "com.netease.cloudmusic" -> "网易云音乐"
    "com.kugou.android" -> "酷狗"
    "com.tencent.qqmusicpad" -> "QQ音乐 HD"
    "com.ss.android.ugc.aweme" -> "抖音"
    "com.ximalaya.ting.android" -> "喜马拉雅"
    else -> packageName.substringAfterLast('.').takeIf { it.isNotBlank() } ?: packageName
}
