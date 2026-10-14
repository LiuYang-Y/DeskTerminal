package com.qingke.desk.feature.media.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.qingke.desk.feature.media.music.online.QqCredential
import com.qingke.desk.feature.media.music.online.QqDeviceFactory
import com.qingke.desk.feature.media.music.online.QqDeviceProfile
import com.qingke.desk.feature.media.music.online.QqLibraryState
import com.qingke.desk.feature.media.music.online.QqLog
import com.qingke.desk.feature.media.music.online.QqLoginController
import com.qingke.desk.feature.media.music.online.QqMusicuApi
import com.qingke.desk.feature.media.music.online.QqQuality
import com.qingke.desk.feature.media.music.online.QqTrack
import com.qingke.desk.feature.media.music.online.isUsableCredential
import com.qingke.desk.feature.media.music.online.qqAlbumCoverUrl
import com.qingke.desk.feature.media.music.spectrum.SpectrumControl
import com.qingke.desk.feature.media.music.spectrum.SpectrumRenderersFactory
import com.qingke.desk.feature.media.music.spectrum.TeeAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 曲库（选歌抽屉）的对外接口。
 *
 * 与 [PlaybackSource] 是同一条边界思路：抽屉只认识"列表 / 刷新 / 打开某张单子 / 放第几首"，
 * 不认识 musicu、不认识凭证、不认识 ExoPlayer。实现（[ExoPlayerPlaybackSource]）在壳层注入，
 * 页面拿不到实现时（例如未来只挂 MediaSession 源）传 null，入口自然不显示。
 */
interface QqMusicLibrary {
    val state: StateFlow<QqLibraryState>
    fun refresh()
    fun openFavorites()
    fun openPlaylist(playlistId: String)
    /** 播放当前列表里第 [index] 首（下标越界时静默忽略）。 */
    fun playTrackAt(index: Int)
}

/**
 * 路线 B 的播放源：**自己登录、自己取链、自己放**。
 *
 * ---------------------------------------------------------------------------
 * 它在整个体系里的位置
 *
 * [PlaybackSource] 的第一版实现（[MediaSessionPlaybackSource]）是"读别人的会话"；
 * 这一版是"自己就是播放器"。两者由壳层的组合器（`RoutingPlaybackSource`）择优，
 * 页面一行都不用改 —— 这正是这一层抽象存在的理由。
 *
 * ---------------------------------------------------------------------------
 * 三条硬纪律
 *
 * 1. **凭证只进不来也出不去**：唯一的入口是 [QqLoginController.credentialOrNull]（读盘、
 *    判过期）。本类不缓存凭证明文超过一次调用的寿命；日志只允许出现
 *    [QqCredential.mask] 与字段长度。取链失败的文案里**不含 URL**（`purl` 带 `vkey=`，
 *    那是签名串，等同于凭证）。
 * 2. **未登录不 crash**：没有凭证时 [status] 停在 [PlaybackStatus.Idle]，
 *    所有入口（刷新 / 选歌）如实报"未登录或凭证已过期"。
 * 3. **播放列表是 ExoPlayer 自己的**：previous / next 由播放器驱动，
 *    本类只负责"把列表喂进去"。
 *
 * ---------------------------------------------------------------------------
 * 线程约定
 *
 * [status] 在主线程更新（ExoPlayer 的回调与心跳协程都跑在壳层给的
 * `rememberCoroutineScope()` 上，即 Dispatchers.Main）；网络在 Dispatchers.IO。
 * 其余方法（previous / playPause / …）可从任意线程调 —— ExoPlayer 的 API 本身线程安全。
 *
 * @param spectrumTee 已移除（第 3 步定稿）：PCM 频谱旁路改由本类**内部持有**
 *        （[SpectrumControl] 委派出去）。原因：media3 在 `:feature:media` 是
 *        `implementation`，`RenderersFactory` / `BaseAudioProcessor` 这些类型一旦
 *        出现在本类的公共签名里，`:shell` 就编译不过（或至少是一条"将来会变成
 *        错误"的警告）。缝收窄成自有类型后，壳层经
 *        [RoutingPlaybackSource] 的 [SpectrumControl] 委派就能驱动门控，
 *        一行 media3 都不碰。
 */
class ExoPlayerPlaybackSource(
    private val context: Context,
    private val loginController: QqLoginController,
    private val scope: CoroutineScope,
) : PlaybackSource, QqMusicLibrary, SpectrumControl {

    private val _status = MutableStateFlow<PlaybackStatus>(PlaybackStatus.Checking)
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    private val _library = MutableStateFlow(QqLibraryState())
    override val state: StateFlow<QqLibraryState> = _library.asStateFlow()

    /**
     * 自己的 OkHttpClient，不与登录流程共用。
     *
     * 登录的 [QqLoginController] 把两个 client（HTTP / WebSocket）都攥在手里不放出来，
     * 而取链与封面是**播放期**的常驻请求，超时配置与登录那次性流程不同
     * （登录可以等 25 秒，取链等 25 秒用户已经在看转圈了）。复用同一个实例就要
     * 迁就其中一方的超时，所以分开。
     */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val api = QqMusicuApi(client)

    /**
     * PCM 频谱旁路（第 3 步）。**本类内部持有** —— 它只可能旁路我们自己的播放器，
     * 系统会话源没有这东西，所以它天然属于这里。壳层 / 页面经 [SpectrumControl]
     * 驱动门控与读电平，碰不到 media3 的类型（依赖不外泄，见类注释）。
     */
    private val spectrum = TeeAudioProcessor()

    // ---- SpectrumControl -----------------------------------------------------
    //
    // 显式转发而不是 `by spectrum` 委派：委托表达式写在类头会早于 body 属性初始化
    // （Kotlin 的构造顺序），三行转发没有这个坑。

    override val level: StateFlow<Float> get() = spectrum.level

    override fun setGates(playing: Boolean, visible: Boolean, enabled: Boolean) =
        spectrum.setGates(playing, visible, enabled)

    /** 播放器。**惰性创建** —— 不登录就不该有一个 ExoPlayer 实例活在进程里。 */
    private var player: ExoPlayer? = null

    /** 与播放器播放列表一一对应的曲目队列（queue[0] 是用户点中的那首）。 */
    private var queue: List<QqTrack> = emptyList()

    /** 当前曲目的候选地址（域名兜底）。换域名重试时前进一格。 */
    private var currentCandidates: List<String> = emptyList()
    private var candidateCursor = 0

    private var loginJob: Job? = null
    private var tickerJob: Job? = null
    private var playJob: Job? = null

    /** 设备档案只在内存里留一份（与登录流程同一条理由：每次请求都读盘没必要）。 */
    private var device: QqDeviceProfile? = null

    private val artworkCache = LruCache<String, Bitmap>(ARTWORK_CACHE_SIZE)
    @Volatile private var artworkRequested: Set<String> = emptySet()

    // ---- PlaybackSource ------------------------------------------------------

    override fun start() {
        if (loginJob != null) return
        loginJob = scope.launch {
            loginController.state.collect { st ->
                val usable = st.isUsableCredential
                _library.update { it.copy(loggedIn = usable) }
                if (usable) {
                    // 登录成功（或重启后恢复）且曲库还空着 → 拉一次。
                    if (_library.value.tracks.isEmpty() && !_library.value.loading) refreshLibrary()
                } else {
                    // 登出 / 凭证过期：清曲库，**并停掉正在放的歌**。
                    // 凭证没了之后每一次切歌都会失败，让它继续跑只是把失败推迟到下一首；
                    // 而"退出登录 → 还在响"是用户明确没有要求的行为。
                    abandonPlayback()
                }
            }
        }
    }

    override fun stop() {
        loginJob?.cancel()
        loginJob = null
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * 释放播放器。**只由壳层在会话结束时调**（组合销毁即应用退出）。
     *
     * 页面切换不经过这里 —— 用户切去首页时音乐继续放，这是桌面终端该有的行为
     * （与导航"跨屏保活"同一条理由，见 NaviSession 的约定）。
     */
    fun release() {
        stop()
        playJob?.cancel()
        playJob = null
        player?.removeListener(playerListener)
        player?.release()
        player = null
        queue = emptyList()
        currentCandidates = emptyList()
        candidateCursor = 0
        _status.value = PlaybackStatus.Idle
        _library.update { it.copy(nowPlayingMid = null, resolvingIndex = -1) }
    }

    /** 回前台 / 用户下拉刷新：重拉曲库 + 重发一次状态。 */
    override fun refresh() {
        refreshLibrary()
        emitStatus()
    }

    override fun previous() {
        player?.seekToPreviousMediaItem()
        emitStatus()
    }

    override fun playPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        emitStatus()
    }

    override fun next() {
        player?.seekToNextMediaItem()
        emitStatus()
    }

    override fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
        emitStatus()
    }

    // ---- QqMusicLibrary ------------------------------------------------------
    //
    // 注意这里不写 `override`：`refresh()` 在上面（PlaybackSource 的重写）已经同时
    // 满足了两个接口的同名方法，Kotlin 允许一次重写覆盖多个父类型的相同签名。

    private fun refreshLibrary() {
        scope.launch {
            _library.update { it.copy(loading = true, error = null) }
            val cred = credentialOrNull() ?: return@launch failLibrary("未登录或凭证已过期")
            val device = ensureDevice() ?: return@launch failLibrary("设备档案不可用")
            try {
                val fav = api.favoriteSongs(device, cred)
                // 两张单子列表各自独立降级：挂了一张不该让另一张也消失。
                val created = runCatching { api.createdPlaylists(device, cred) }
                    .onFailure { QqLog.w(TAG, "自建歌单拉取失败（我喜欢仍可用）：${it.describe()}") }
                    .getOrDefault(emptyList())
                val faved = runCatching { api.favPlaylists(device, cred) }
                    .onFailure { QqLog.w(TAG, "收藏歌单拉取失败（自建歌单仍可用）：${it.describe()}") }
                    .getOrDefault(emptyList())
                _library.update {
                    it.copy(
                        loading = false,
                        tracks = fav.tracks,
                        playlists = created + faved,
                        selectedPlaylistId = null,
                        error = null,
                    )
                }
                QqLog.i(
                    TAG,
                    "曲库已加载：我喜欢 ${fav.tracks.size} 首（total=${fav.total} hasMore=${fav.hasMore}），" +
                        "自建 ${created.size} + 收藏 ${faved.size} 张歌单",
                )
            } catch (t: Throwable) {
                // 取消不是失败。
                if (t is kotlinx.coroutines.CancellationException) throw t
                failLibrary("曲库加载失败：${t.describe()}")
            }
        }
    }

    override fun openFavorites() {
        scope.launch {
            _library.update { it.copy(loading = true, error = null, selectedPlaylistId = null) }
            val cred = credentialOrNull() ?: return@launch failLibrary("未登录或凭证已过期")
            val device = ensureDevice() ?: return@launch failLibrary("设备档案不可用")
            try {
                val fav = api.favoriteSongs(device, cred)
                _library.update { it.copy(loading = false, tracks = fav.tracks, error = null) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failLibrary("我喜欢加载失败：${t.describe()}")
            }
        }
    }

    override fun openPlaylist(playlistId: String) {
        scope.launch {
            _library.update { it.copy(loading = true, error = null, selectedPlaylistId = playlistId) }
            val cred = credentialOrNull() ?: return@launch failLibrary("未登录或凭证已过期")
            val device = ensureDevice() ?: return@launch failLibrary("设备档案不可用")
            try {
                val page = api.playlistSongs(device, cred, playlistId)
                _library.update {
                    it.copy(
                        loading = false,
                        tracks = page.tracks,
                        selectedPlaylistId = playlistId,
                        error = null,
                    )
                }
                QqLog.i(TAG, "歌单 $playlistId 加载 ${page.tracks.size} 首（total=${page.total}）")
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                failLibrary("歌单加载失败：${t.describe()}")
            }
        }
    }

    /**
     * 播放当前列表里第 [index] 首。**两段式**：
     *
     * 1. 先只取点中那一首的链接，立即开始播（用户点完不该对着转圈等一整张单子）；
     * 2. 再在后台按顺序补全播放列表（封顶 [MAX_QUEUE_RESOLVE] 首，失败的跳过），
     *    用 `addMediaItems` **追加**到当前曲目之后 —— 不重建播放列表、不重缓冲当前曲目。
     *
     * 队列方向是"从点中处往后、wrap 回开头"，封顶 20 首：拉 100 首的链接按每次
     * ~300ms 算是半分钟，那期间用户早就换歌了。
     */
    override fun playTrackAt(index: Int) {
        val tracks = _library.value.tracks
        if (index !in tracks.indices) return
        playJob?.cancel()
        playJob = scope.launch {
            _library.update { it.copy(resolvingIndex = index, error = null) }
            val cred = credentialOrNull() ?: return@launch failLibrary("未登录或凭证已过期")
            val device = ensureDevice() ?: return@launch failLibrary("设备档案不可用")

            val first = tracks[index]
            val link = try {
                api.getPlayLink(device, cred, first, QqQuality.DEFAULT)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                QqLog.w(TAG, "取链接失败（第 ${index + 1} 行）：${t.describe()}")
                _library.update { it.copy(resolvingIndex = -1, error = "这首歌取不到播放链接：${t.describe()}") }
                return@launch
            }

            queue = listOf(first)
            currentCandidates = link.candidates
            candidateCursor = 0
            val p = ensurePlayer()
            p.setMediaItem(MediaItem.fromUri(link.url))
            p.prepare()
            p.play()
            _library.update { it.copy(resolvingIndex = -1, nowPlayingMid = first.mid, error = null) }
            QqLog.i(TAG, "开始播放：第 ${index + 1}/${tracks.size} 首（文件名段长度=${link.filename.length}）")

            // ---- 第二段：后台补全播放列表 ----
            // 任务身份 + 播放器身份双重校验：用户又点了一首（playJob 被换掉）或壳层
            // 释放了播放器（release）时，这里的追加必须立刻停手 —— 往已释放的播放器上
            // addMediaItems 会抛 IllegalStateException。
            val myJob = coroutineContext[kotlinx.coroutines.Job]
            val rest = mutableListOf<QqTrack>()
            var offset = 1
            while (rest.size < MAX_QUEUE_RESOLVE - 1 && offset < tracks.size) {
                rest += tracks[(index + offset) % tracks.size]
                offset++
            }
            for (t in rest) {
                if (playJob !== myJob || player !== p) return@launch
                val next = runCatching { api.getPlayLink(device, cred, t, QqQuality.DEFAULT) }
                    .onFailure { e -> QqLog.w(TAG, "队列补全跳过一首：${e.describe()}") }
                    .getOrNull() ?: continue
                // 每首之间让出一次协程：取消（用户又点了一首）要能及时生效。
                kotlinx.coroutines.yield()
                if (playJob !== myJob || player !== p) return@launch
                runCatching { p.addMediaItems(queue.size, listOf(MediaItem.fromUri(next.url))) }
                    .onFailure { e -> QqLog.w(TAG, "队列追加失败：${e.describe()}") }
                queue = queue + t
            }
            QqLog.i(TAG, "播放列表补全：${queue.size} 首")
        }
    }

    // ---- 内部：播放器 ---------------------------------------------------------

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = emitStatus()

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            manageTicker()
            emitStatus()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 换域名重试的游标只在换曲目时归零。
            candidateCursor = 0
            val mid = player?.currentMediaItemIndex
                ?.let { queue.getOrNull(it)?.mid }
            _library.update { it.copy(nowPlayingMid = mid) }
            emitStatus()
        }

        override fun onPlayerError(error: PlaybackException) {
            handlePlaybackError(error)
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        // 频谱旁路的注入（第 3 步）：tee 常驻（零开销地）坐在音频链上透传，
        // 真正的"算不算"由门控决定 —— 见 TeeAudioProcessor 的 queueInput 首行。
        val p = ExoPlayer.Builder(context, SpectrumRenderersFactory(context, spectrum))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机自动暂停。桌面终端常接着音箱，但用户随时可能拔线 ——
            // 不处理的话拔线后歌还在放，是"看起来没人在放却还在响"的那类困惑。
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(playerListener)
        player = p
        return p
    }

    /**
     * 播放出错的域名兜底重试。
     *
     * Phase 0 实测四个 https 域名 6/6 全部 206，所以正常情况下**永远不进这里**；
     * 它防的是"某个 CDN 域名单独挂掉"这种单点故障 —— 那时候第一个候选必然全军覆没。
     * 候选列表来自取链响应（`sip` 在前、https 兜底域名在后，只含 https 条目）。
     */
    private fun handlePlaybackError(error: PlaybackException) {
        val p = player ?: return
        val idx = p.currentMediaItemIndex
        val track = queue.getOrNull(idx)
        QqLog.w(TAG, "播放出错（第 ${idx + 1} 首）：${error.errorCodeName} ${error.describe()}")
        val next = currentCandidates.getOrNull(candidateCursor + 1)
        if (next != null) {
            candidateCursor++
            QqLog.i(TAG, "换域名重试（候选 ${candidateCursor + 1}/${currentCandidates.size}）")
            p.replaceMediaItem(idx, MediaItem.fromUri(next))
            p.prepare()
            p.play()
            return
        }
        // 候选都试过了。**不放 URL 进日志**（带 vkey= 签名串），只留曲目身份与错误码。
        QqLog.w(TAG, "候选域名全部失败：${track?.name.orEmpty()} ${error.errorCodeName}")
        _library.update {
            it.copy(
                error = "播放失败：${track?.name.orEmpty()}（${error.errorCodeName}）",
                resolvingIndex = -1,
            )
        }
        emitStatus()
    }

    /** 500ms 一次的位置采样。页面自己按墙上时钟外推，这里只负责让采样值别太旧。 */
    private fun manageTicker() {
        val playing = player?.isPlaying == true
        if (!playing) {
            tickerJob?.cancel()
            tickerJob = null
            return
        }
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                delay(POSITION_TICK_MS)
                emitStatus()
            }
        }
    }

    private fun emitStatus() {
        val p = player
        val idx = p?.currentMediaItemIndex ?: -1
        val track = queue.getOrNull(idx)
        if (p == null || track == null) {
            _status.value = PlaybackStatus.Idle
            return
        }
        val duration = if (p.duration > 0L) p.duration else track.intervalMs
        _status.value = PlaybackStatus.Active(
            NowPlaying(
                title = track.name,
                artist = track.artists,
                album = track.album,
                artwork = artworkOf(track),
                sourcePackage = context.packageName,
                isPlaying = p.isPlaying,
                positionMs = p.currentPosition.coerceAtLeast(0L),
                positionTakenAtMs = SystemClock.elapsedRealtime(),
                durationMs = duration,
                canPrevious = p.hasPreviousMediaItem(),
                canNext = p.hasNextMediaItem(),
                canPlayPause = true,
                canSeek = p.isCurrentMediaItemSeekable,
            ),
        )
    }

    private fun abandonPlayback() {
        playJob?.cancel()
        playJob = null
        tickerJob?.cancel()
        tickerJob = null
        player?.stop()
        player?.clearMediaItems()
        queue = emptyList()
        currentCandidates = emptyList()
        candidateCursor = 0
        _status.value = PlaybackStatus.Idle
        _library.update { it.copy(tracks = emptyList(), playlists = emptyList(), selectedPlaylistId = null, nowPlayingMid = null, resolvingIndex = -1) }
    }

    // ---- 内部：封面 -----------------------------------------------------------

    /**
     * 封面。**同步取缓存，未命中就走异步加载然后重发状态** ——
     * 组合期不该有一次网络 IO，而"等图到了再显示"会让页面在换歌时闪一下空封面。
     */
    private fun artworkOf(track: QqTrack): Bitmap? {
        val mid = track.albumMid
        if (mid.isEmpty()) return null
        artworkCache.get(mid)?.let { return it }
        if (mid !in artworkRequested) {
            artworkRequested = artworkRequested + mid
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { loadArtwork(mid) }
                if (bmp != null) artworkCache.put(mid, bmp)
                emitStatus()
            }
        }
        return null
    }

    /**
     * 专辑封面。`https://y.gtimg.cn/music/photo_new/T002R300x300M000<albumMid>.jpg`。
     *
     * **公开 CDN、不带任何凭证**（与取链那条路完全不同）。失败只记 W 日志：
     * 封面是装饰，取不到不影响播放（ArtworkBackdrop 会拿 frameKey 算一个兜底底色）。
     */
    private fun loadArtwork(albumMid: String): Bitmap? = runCatching {
        client.newCall(Request.Builder().url(qqAlbumCoverUrl(albumMid)).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            BitmapFactory.decodeStream(resp.body.byteStream())
        }
    }.onFailure {
        QqLog.w(TAG, "封面加载失败（albumMid 长度=${albumMid.length}）：${it.describe()}")
    }.getOrNull()

    // ---- 内部：凭证与设备 ------------------------------------------------------

    private suspend fun credentialOrNull(): QqCredential? = loginController.credentialOrNull()

    private fun ensureDevice(): QqDeviceProfile? {
        device?.let { return it }
        val loaded = runCatching { QqDeviceFactory.loadOrCreate(context) }
            .onFailure { QqLog.w(TAG, "设备档案读取失败：${it.describe()}") }
            .getOrNull() ?: return null
        device = loaded
        return loaded
    }

    private fun failLibrary(reason: String) {
        QqLog.w(TAG, reason)
        _library.update { it.copy(loading = false, resolvingIndex = -1, error = reason) }
    }

    private companion object {
        const val TAG = "QqPlayback"

        /** 位置采样间隔。见 [manageTicker]。 */
        const val POSITION_TICK_MS = 500L

        /**
         * 播放列表补全的封顶首数（含点中那首）。
         *
         * 20 是"补全等待"与"队列长度"的折中：按每次取链 ~300ms 算，补满 20 首约 6 秒，
         * 期间当前曲目已在放。要更长就得上并发取链（上游限流风险未验），本期不做。
         */
        const val MAX_QUEUE_RESOLVE = 20

        /** 封面缓存条目数。一屏抽屉 + 当前曲目，8 条足够。 */
        const val ARTWORK_CACHE_SIZE = 8
    }
}

/**
 * 异常的脱敏描述：**类名 + 消息，但不含 URL 与凭证**。
 *
 * [com.qingke.desk.feature.media.music.online.QqProtocolException] 的消息只有
 * 阶段名与上游返回码（http/global/upstream），是安全的；其它异常的消息理论上可能带上
 * URL（OkHttp 的 IOException 会带 host），所以这里**主动剥掉 query 段之后的部分** ——
 * `purl` 的签名在 query 里（`?vkey=…`）。
 */
private fun Throwable.describe(): String {
    val raw = message ?: javaClass.simpleName
    val safe = raw.substringBefore('?')
    return "${javaClass.simpleName}: $safe"
}
