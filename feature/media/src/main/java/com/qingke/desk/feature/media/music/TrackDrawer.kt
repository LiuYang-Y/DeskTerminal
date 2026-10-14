package com.qingke.desk.feature.media.music

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.qingke.desk.feature.media.music.online.QqLibraryState
import com.qingke.desk.feature.media.music.online.QqTrack
import com.qingke.desk.feature.media.music.online.qqAlbumCoverUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * 选歌抽屉。从右侧推入，盖在音乐页之上（z 序最高，仅次于登录浮层之后）。
 *
 * ---------------------------------------------------------------------------
 * 它只认识三样东西：[QqLibraryState]、[DrawerMetrics]、四个意图回调
 *
 * 与登录浮层同一条边界：不认识 musicu、不认识凭证、不认识 ExoPlayer。
 * "放第几首"由 [onSelectTrack] 上抛，实现（ExoPlayerPlaybackSource）自己去取链接。
 *
 * ---------------------------------------------------------------------------
 * 两条实现约定
 *
 * 1. **尺寸全部来自 [metrics]**（设计文档 §6.3 的公式，`MusicLayout.trackDrawer` 推导），
 *    这个文件里没有一个写死的 dp —— 包括圆角（用 percent）与内边距（用行距推导）。
 * 2. **退场有动画**：点遮罩/关闭钮后抽屉滑出去再消失，不是当场闪没。
 *    `animateFloatAsState` 的 `finishedListener` 负责"滑完了才通知父层卸载"。
 */
@Composable
fun TrackDrawer(
    state: QqLibraryState,
    metrics: DrawerMetrics,
    onSelectTrack: (Int) -> Unit,
    onRefresh: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 0 = 完全在位，1 = 完全移出。退场动画播放期间父层还挂着这个 composable。
    var closing by remember { mutableStateOf(false) }
    val exit by animateFloatAsState(
        targetValue = if (closing) 1f else 0f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        finishedListener = { if (closing) onDismiss() },
        label = "track-drawer-exit",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // 遮罩。退场时跟着淡出；退场中不再接受点击（再点一次会触发第二轮关闭）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(1f - exit)
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(enabled = !closing) { closing = true },
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(vertical = metrics.outerYDp)
                .width(metrics.drawerWDp)
                .offset {
                    IntOffset((exit * metrics.drawerWDp.toPx()).roundToInt(), 0)
                }
                .clip(
                    RoundedCornerShape(
                        topStartPercent = 8,
                        bottomStartPercent = 8,
                    ),
                )
                .background(DrawerSurface)
                .padding(horizontal = (metrics.rowGap * 2f).dp),
        ) {
            DrawerHeader(
                state = state,
                metrics = metrics,
                onClose = { closing = true },
            )

            if (state.playlists.isNotEmpty()) {
                DrawerTabs(
                    state = state,
                    metrics = metrics,
                    onOpenFavorites = onOpenFavorites,
                    onOpenPlaylist = onOpenPlaylist,
                )
            }

            DrawerBody(
                state = state,
                metrics = metrics,
                onSelectTrack = onSelectTrack,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun DrawerHeader(
    state: QqLibraryState,
    metrics: DrawerMetrics,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = metrics.rowGapDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.listTitle,
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = drawerSubtitle(state, metrics),
                fontSize = metrics.artistSize,
                lineHeight = metrics.artistSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 关闭键的触控区给到整行高（≥48dp），图标用标题字号。
        Box(
            modifier = Modifier
                .size(metrics.rowHDp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}

/** "共 N 首 · 每屏约 M 行" —— [DrawerMetrics.visibleRows] 在产品里唯一的去处。 */
private fun drawerSubtitle(state: QqLibraryState, metrics: DrawerMetrics): String = when {
    state.loading -> "正在加载…"
    state.error != null -> state.error
    else -> "共 ${state.tracks.size} 首 · 每屏约 ${metrics.visibleRows} 行"
}

@Composable
private fun DrawerTabs(
    state: QqLibraryState,
    metrics: DrawerMetrics,
    onOpenFavorites: () -> Unit,
    onOpenPlaylist: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = metrics.rowGapDp),
        horizontalArrangement = Arrangement.spacedBy(metrics.rowGapDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            TabChip(
                text = "我喜欢",
                selected = state.selectedPlaylistId == null,
                metrics = metrics,
                onClick = onOpenFavorites,
            )
        }
        items(state.playlists, key = { it.id }) { pl ->
            TabChip(
                text = pl.name,
                selected = state.selectedPlaylistId == pl.id,
                metrics = metrics,
                onClick = { onOpenPlaylist(pl.id) },
            )
        }
    }
}

@Composable
private fun TabChip(
    text: String,
    selected: Boolean,
    metrics: DrawerMetrics,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f),
            )
            .clickable(onClick = onClick)
            .padding(
                horizontal = (metrics.titleSizeSp * 0.6f).dp,
                vertical = (metrics.artistSizeSp * 0.4f).dp,
            ),
    ) {
        Text(
            text = text,
            fontSize = metrics.artistSize,
            lineHeight = metrics.artistSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
            color = Color.White.copy(alpha = if (selected) 0.95f else 0.62f),
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ColumnScope.DrawerBody(
    state: QqLibraryState,
    metrics: DrawerMetrics,
    onSelectTrack: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    when {
        state.loading && state.tracks.isEmpty() -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.85f))
                Text(
                    text = "正在加载曲库…",
                    fontSize = metrics.artistSize,
                    lineHeight = metrics.artistSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                    color = Color.White.copy(alpha = 0.62f),
                    modifier = Modifier.padding(top = metrics.rowGapDp),
                )
            }
        }

        state.tracks.isEmpty() -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = metrics.rowGapDp),
            ) {
                Text(
                    text = state.error ?: "这个列表还没有歌曲",
                    fontSize = metrics.artistSize,
                    lineHeight = metrics.artistSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                    color = Color.White.copy(alpha = 0.72f),
                )
                if (state.error != null) {
                    Text(
                        text = "重试",
                        fontSize = metrics.artistSize,
                        lineHeight = metrics.artistSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                        color = Color.White.copy(alpha = 0.92f),
                        maxLines = 1,
                        modifier = Modifier
                            .padding(top = metrics.rowGapDp)
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.White.copy(alpha = 0.14f))
                            .clickable(onClick = onRefresh)
                            .padding(
                                horizontal = metrics.titleSizeSp.dp,
                                vertical = (metrics.artistSizeSp * 0.5f).dp,
                            ),
                    )
                }
            }
        }

        else -> LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(vertical = metrics.rowGapDp),
            verticalArrangement = Arrangement.spacedBy(metrics.rowGapDp),
        ) {
            itemsIndexed(state.tracks, key = { _, t -> t.key }) { index, track ->
                TrackRow(
                    track = track,
                    metrics = metrics,
                    playing = state.nowPlayingMid == track.mid,
                    resolving = state.resolvingIndex == index,
                    onClick = { onSelectTrack(index) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: QqTrack,
    metrics: DrawerMetrics,
    playing: Boolean,
    resolving: Boolean,
    onClick: () -> Unit,
) {
    var cover by remember(track.albumMid) { mutableStateOf(CoverLoader.peek(track.albumMid)) }
    LaunchedEffect(track.albumMid) {
        if (cover == null && track.albumMid.isNotEmpty()) {
            cover = CoverLoader.load(track.albumMid)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(metrics.rowHDp)
            .clip(RoundedCornerShape(percent = 10))
            .background(if (playing) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = metrics.rowGapDp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(metrics.coverSideDp)
                .clip(RoundedCornerShape(percent = 12))
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = cover
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(metrics.coverSideDp * 0.42f),
                )
            }
        }

        Column(
            modifier = Modifier
                .padding(start = (metrics.rowGap * 1.5f).dp)
                .weight(1f),
        ) {
            Text(
                text = track.name,
                fontSize = metrics.titleSize,
                lineHeight = metrics.titleSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White,
                fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = trackSubtitle(track),
                fontSize = metrics.artistSize,
                lineHeight = metrics.artistSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            resolving -> CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(metrics.titleSizeSp.dp),
            )
            playing -> Text(
                text = "播放中",
                fontSize = metrics.artistSize,
                lineHeight = metrics.artistSize * MusicLayout.LOGIN_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}

/** `歌手 · 时长`。时长缺失（interval=0）时只显示歌手。 */
private fun trackSubtitle(track: QqTrack): String {
    val artist = track.artists.ifBlank { "未知艺术家" }
    val secs = track.intervalMs / 1000L
    if (secs <= 0L) return artist
    return "$artist · ${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
}

/**
 * 抽屉行封面的加载器。**独立于播放源的封面缓存**：
 *
 * 抽屉要一次画几十行的封面，而播放源只为"正在播的那一首"加载。
 * 共用一个缓存会让一边的条目把另一边挤掉；分开则各按各的寿命管理。
 * 客户端也独立（小对象、短寿命、10 秒超时足够，与取链的 25 秒期望不同）。
 */
private object CoverLoader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cache = android.util.LruCache<String, Bitmap>(32)

    fun peek(albumMid: String): Bitmap? = cache.get(albumMid)

    suspend fun load(albumMid: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(qqAlbumCoverUrl(albumMid)).build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    android.graphics.BitmapFactory.decodeStream(resp.body.byteStream())
                }
        }.getOrNull()?.also { cache.put(albumMid, it) }
    }
}

/** 抽屉底色。与登录浮层同一族（比音乐页背景亮一档才"浮"得起来）。 */
private val DrawerSurface = Color(0xFF17171A).copy(alpha = 0.97f)
