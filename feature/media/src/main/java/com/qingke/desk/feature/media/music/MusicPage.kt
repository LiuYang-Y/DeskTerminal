package com.qingke.desk.feature.media.music

import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.platform.BurnInGuard
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.LocalEffectTier
import com.qingke.desk.feature.media.MediaPermission
import com.qingke.desk.feature.media.mediaSourceLabel
import com.qingke.desk.feature.media.music.artwork.ArtworkBackdrop
import com.qingke.desk.feature.media.music.artwork.ArtworkVisual
import com.qingke.desk.feature.media.music.lyrics.ImportResult
import com.qingke.desk.feature.media.music.lyrics.LyricsDoc
import com.qingke.desk.feature.media.music.lyrics.LyricsRepository
import com.qingke.desk.feature.media.music.online.QqLoginPane
import com.qingke.desk.feature.media.music.online.QqLoginState
import com.qingke.desk.feature.media.music.online.QqLibraryState
import com.qingke.desk.feature.media.music.online.QqLog
import com.qingke.desk.feature.media.music.online.isUsableCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** HUD 无操作多久自动淡出。用户口径是 4 秒。 */
private const val HUD_AUTO_HIDE_MS = 4_000L

/**
 * 播放位置的刷新间隔。
 *
 * 200 ms = 5 Hz。选它而不是逐帧，是因为它同时喂三样东西：
 *   · 歌词的"当前行/下一行"（换行是秒级事件，5 Hz 绰绰有余）；
 *   · 逐字高亮的推进（人眼对 200 ms 的字级推进已经读不出卡顿）；
 *   · 进度条与拖柄（真正的逐帧元素，但它走**绘制期读状态**，见 [MusicHud]）。
 *
 * 200 ms 的重组范围只有两行 [Text]（歌词）—— 页面的骨架、背景、HUD 都不参与。
 */
private const val POSITION_TICK_MS = 200L

/** 背景大衬字的不透明度。取用户给的范围（0.14~0.18）的中值。 */
private const val WATERMARK_ALPHA = 0.16f

/** OFF 档时水印的不透明度。比别的档低一截，但**不归零** —— 归零等于整页只剩一块纯色。 */
private const val WATERMARK_ALPHA_OFF = 0.09f

/**
 * 路线 D 的音乐页。**全屏沉浸式**，铺满内容区（不吃侧栏，也不吃挖孔区）。
 *
 * ---------------------------------------------------------------------------
 * 它只认两样东西：[PlaybackSource] 与 [LyricsRepository]
 *
 * 前者决定"在放什么"，后者决定"唱到哪句"。页面不认识 `MediaSession`，
 * 也不认识歌词解析库的类型 —— 以后换成 ExoPlayer / 本地曲库 / 别的歌词源，
 * 这一页一行都不改。这是这一版最重要的结构决定。
 *
 * ---------------------------------------------------------------------------
 * 每首歌只算一次的三件事
 *
 * 封面取色、底图模糊、歌词拉取，全部挂在 `track.frameKey` 这个键上
 * （[NowPlaying.frameKey]）。于是它们**只在换歌时发生**：
 *   · 播放中一分钱不花；
 *   · 暂停/继续、切 HUD、拖进度条都不会重算；
 *   · 同一首歌来回切时走的是本页 `remember` 里那个 4 项的小缓存。
 *
 * 缓存放在**组合作用域**（`remember`）而不是进程级的单例里，是有意的：
 * 它装的是 `ImageBitmap`（原生 Bitmap）。本项目已经有硬约束 ——
 * 进程级 object 持有原生对象就得在宿主销毁时显式清，否则整份位图活到进程结束。
 * 挂在组合上，页面一走它就跟着走。
 *
 * ---------------------------------------------------------------------------
 * 可停算（硬约束）
 *
 * 声波圆环的相位协程只在"**这一档有圆环** 且 **正在播放**"时启动
 * （见 [rememberEffectPhase]）。暂停、切走页面、把档位调到"关"，
 * 三者任一成立时进程里没有任何 `withFrameNanos` 在排队。
 * 对一台 24 小时常亮的设备，"跑着但看不见"和"没跑"在耗电上是两回事。
 */
@Composable
fun MusicPage(
    source: PlaybackSource,
    lyricsRepository: LyricsRepository,
    /**
     * QQ 音乐账号的登录状态。**这一页只读状态，不认识 MQTT / musicu**
     * —— 与它不认识 `MediaSession` 是同一条边界（见 [PlaybackSource] 的类注释）。
     */
    loginState: QqLoginState,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onMessage: ((String) -> Unit)? = null,
    /**
     * 选歌抽屉的数据源。**null = 当前没有 QQ 自播源**（例如壳层只挂了 MediaSession 源），
     * 此时"选歌"入口不出现 —— 页面不认识 ExoPlayer，只认识这个抽象。
     */
    library: QqMusicLibrary? = null,
) {
    val context = LocalContext.current
    val tier = LocalEffectTier.current
    val scope = rememberCoroutineScope()

    // 登录浮层是否展开。归页面持有（它是纯 UI 状态），登录流程本身归壳层。
    //
    // 开/关各写一条日志：这个布尔是"浮层为什么不见了"这类问题的**唯一直接证据** ——
    // 状态流（Preparing/QrReady/...）在壳层好好地推着，而浮层由这个本地布尔决定显隐，
    // 两边对不上时（状态在走、浮层没了），只有这条日志能分清是"谁把它关了"。
    var showLoginPane by remember { mutableStateOf(false) }
    LaunchedEffect(showLoginPane, loginState) {
        QqLog.i("MusicPage", "浮层可见性=$showLoginPane 状态=${loginState::class.simpleName}")
    }

    // 选歌抽屉的可见性。与登录浮层同一条理由留日志：这个布尔是"抽屉为什么点不开"
    // 这类问题的唯一直接证据（library 为 null 时入口根本不出现，是另一条路径）。
    var showDrawer by remember { mutableStateOf(false) }
    LaunchedEffect(showDrawer, library != null) {
        QqLog.i("MusicPage", "抽屉可见性=$showDrawer 有曲库源=${library != null}")
    }

    val status by source.status.collectAsState()
    val track = (status as? PlaybackStatus.Active)?.track

    // 抽屉的曲库状态。**无条件 collect**（library 为 null 时结果是 null）——
    // 放进 if 分支里 collect 会让状态收集的生命周期跟着可见性走，
    // 而"后台拉歌单"必须在抽屉不可见时也活着（用户点了播放会跳回页面）。
    val libraryState: State<QqLibraryState>? = library?.state?.collectAsState()

    // ---- 位置心跳 ----------------------------------------------------------
    val nowMs = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(track?.frameKey, track?.isPlaying) {
        nowMs.longValue = SystemClock.elapsedRealtime()
        if (track?.isPlaying != true) return@LaunchedEffect
        while (true) {
            delay(POSITION_TICK_MS)
            nowMs.longValue = SystemClock.elapsedRealtime()
        }
    }

    /**
     * 外推后的播放位置。
     *
     * 经 `derivedStateOf` 出去、**以 [State] 的形式交给下层**，这样：
     *   · 进度条可以直接在绘制期读它（不产生重组）；
     *   · 歌词层单独读它（只有它重组）。
     * 页面自己**不读**这个值 —— 读了就会让整页跟着 5 Hz 重组。
     */
    val positionState: State<Long> = remember(track) {
        derivedStateOf { track?.positionAt(nowMs.longValue) ?: 0L }
    }

    // ---- 封面取色 + 底图（每首歌一次）--------------------------------------
    val visualCache = remember { LruCache<String, ArtworkVisual>(VISUAL_CACHE_SIZE) }
    val fallbackVisual = remember { ArtworkBackdrop.compute(artwork = null, seed = FALLBACK_SEED) }
    var visual by remember { mutableStateOf(fallbackVisual) }
    LaunchedEffect(track?.frameKey) {
        val current = track
        if (current == null) {
            visual = fallbackVisual
            return@LaunchedEffect
        }
        visualCache.get(current.frameKey)?.let {
            visual = it
            return@LaunchedEffect
        }
        val computed = withContext(Dispatchers.Default) {
            ArtworkBackdrop.compute(current.artwork, current.frameKey)
        }
        visualCache.put(current.frameKey, computed)
        visual = computed
    }

    // ---- 歌词（每首歌一次）-------------------------------------------------
    var lyrics by remember { mutableStateOf(LyricsDoc.Empty) }
    var lyricsLoading by remember { mutableStateOf(false) }
    LaunchedEffect(track?.frameKey) {
        val current = track
        if (current == null) {
            lyrics = LyricsDoc.Empty
            lyricsLoading = false
            return@LaunchedEffect
        }
        lyricsLoading = true
        lyrics = lyricsRepository.load(current)
        lyricsLoading = false
    }

    // ---- 本地导入 ----------------------------------------------------------
    //
    // 用 SAF 的 OpenDocument 而不是自己摸文件路径：这台设备上应用没有
    // "读全盘"的权限（也不该有），文件从系统选择器进来时带的是**一次性授权**，
    // 不需要任何存储权限。
    //
    // MIME 用 `*/*`：.lrc 没有统一的注册类型，实测各 ROM 报什么的都有
    // （text/plain、application/octet-stream、甚至空）。写死一个类型会把文件变灰。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val current = track
        if (current == null) {
            onMessage?.invoke("先在音乐 App 里播放一首歌，再为它导入歌词")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            when (val result = lyricsRepository.importLocal(current, uri)) {
                is ImportResult.Ok -> {
                    lyrics = result.doc
                    onMessage?.invoke("已导入歌词（${result.doc.lines.size} 行）")
                }

                ImportResult.NoTimeline -> onMessage?.invoke(
                    "这个文件没有时间戳，只支持带时间轴的 .lrc",
                )

                is ImportResult.Failed -> onMessage?.invoke(result.reason)
            }
        }
    }

    // ---- HUD 可见性 --------------------------------------------------------
    var hudVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(hudVisible, interaction) {
        if (!hudVisible) return@LaunchedEffect
        delay(HUD_AUTO_HIDE_MS)
        hudVisible = false
    }
    val wake by rememberUpdatedState {
        hudVisible = true
        interaction++
    }
    val hudFade by rememberFadeAlpha(hudVisible)

    // ---- 效果档位 ----------------------------------------------------------
    val wave = remember(tier) { waveSpec(tier) }
    val bg = remember(tier) { backgroundSpec(tier) }
    val phase = rememberEffectPhase(
        // **可停算的判据**：这一档得画圆环，而且确实在播。
        // 页面离开组合时 LaunchedEffect 自然取消，"页面不可见"这一条不需要额外判断。
        active = wave.rings > 0 && track?.isPlaying == true,
        periodMs = wave.periodMs,
    )

    // ---- 防烧屏微位移 ------------------------------------------------------
    //
    // 与首页同一套：走挂钟时间纯函数，所以配置重建（旋转、`wm size`）不会让位置跳变。
    // 它只作用在**静止元素**上（水印、歌词、HUD）—— 背景渐变与圆环本来就在动，
    // 再叠一层漂移只是白算。
    val wallClockMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            wallClockMs.longValue = System.currentTimeMillis()
            delay(DeskDimens.BURN_IN_DRIFT_PERIOD_MIN * 60_000L / DRIFT_STEPS)
        }
    }
    val drift = remember(wallClockMs.longValue) {
        BurnInGuard.driftOffsetDp(
            nowMs = wallClockMs.longValue,
            rangeDp = DeskDimens.burnInDriftRange.value,
            periodMinutes = DeskDimens.BURN_IN_DRIFT_PERIOD_MIN,
        )
    }
    val driftX = drift.first.dp
    val driftY = drift.second.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            // 唤醒层。**放在最前面（z 序最底）**，所以它接不到 HUD 按钮上的点击 ——
            // 那些是被声明在它之后的兄弟节点，命中测试先给它们。
            .pointerInput(Unit) { detectTapGestures { wake() } },
    ) {
        val metrics = remember(maxWidth, maxHeight) {
            MusicLayout.metrics(maxWidth.value, maxHeight.value)
        }
        // 登录浮层的尺寸与音乐页**同源**：都用这个 BoxWithConstraints 的实测约束，
        // 谁也不去读 DeviceProfile（那是 WindowMetrics.bounds 的上界，小米上会多出 16dp）。
        val paneMetrics = remember(maxWidth, maxHeight) {
            MusicLayout.loginPane(maxWidth.value, maxHeight.value)
        }
        // 选歌抽屉同理（公式见 MusicLayout.trackDrawer / 设计文档 §6.3）。
        val drawerMetrics = remember(maxWidth, maxHeight) {
            MusicLayout.trackDrawer(maxWidth.value, maxHeight.value)
        }

        MusicBackdrop(
            visual = visual,
            spec = bg,
            driftX = driftX,
            driftY = driftY,
        )

        Watermark(
            text = watermarkTextOf(track?.artist.orEmpty(), track?.title),
            size = metrics.watermarkSize,
            alpha = if (tier == EffectTier.OFF) WATERMARK_ALPHA_OFF else WATERMARK_ALPHA,
            driftX = driftX,
            driftY = driftY,
            modifier = Modifier.align(Alignment.Center),
        )

        WaveRings(
            spec = wave,
            maxRadius = metrics.waveMaxRadiusDp,
            accent = visual.accent,
            phase = phase,
            driftX = driftX,
            driftY = driftY,
        )

        if (track == null) {
            MusicStatusCard(
                status = status,
                metrics = metrics,
                loginState = loginState,
                modifier = Modifier.align(Alignment.Center),
                onOpenSettings = {
                    MediaPermission.openSettings(context) { reason -> onMessage?.invoke(reason) }
                },
                // 没在播的时候 HUD 不存在，所以这里是**唯一**的登录入口 ——
                // 少了它，"还没登录 → 没内容 → 看不到入口"就是一个死循环。
                onOpenLogin = { showLoginPane = true },
                // 已登录时同一位置换成"选歌"：状态卡是空态页面的主操作区，
                // 登录过的人到这里要的是"放一首"，不是"再登录一次"。
                onOpenDrawer = if (loginState.isUsableCredential && library != null) {
                    {
                        showDrawer = true
                        wake()
                    }
                } else {
                    null
                },
            )
        } else {
            LyricsLayer(
                doc = lyrics,
                position = positionState,
                metrics = metrics,
                placeholder = when {
                    lyricsLoading -> "正在获取歌词…"
                    lyrics.instrumental -> "纯音乐，请欣赏"
                    lyrics.unreachable -> "网络暂时不可用，稍后自动重试"
                    lyrics.isEmpty -> "未找到同步歌词"
                    else -> null
                },
                driftX = driftX,
                driftY = driftY,
            )
        }

        // ---- 歌词来源角标（顶部居中）--------------------------------------
        //
        // 放在顶部**中间**而不是左边或右边：壳层的设置齿轮会跟着侧栏镜像到
        // 左上或右上（见 DeskRoot 的 overlayCorner），放两侧总有一边会撞上。
        if (track != null) {
            Text(
                text = lyricsSourceLabel(lyrics, lyricsLoading),
                fontSize = metrics.chipSize,
                lineHeight = metrics.chipSize * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.52f),
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = metrics.pad.dp)
                    // 与 HUD 同步淡出：看词态里这一行属于"chrome"，不是内容。
                    .alpha(hudFade),
            )
        }

        if (track != null) {
            MusicHud(
            visual = visual,
            title = track.title,
            artist = track.artist,
            sourceLabel = sourceLabelOf(track.sourcePackage),
            positionMs = positionState,
            durationMs = track.durationMs,
            isPlaying = track.isPlaying,
            canSeek = track.canSeek,
            canPrevious = track.canPrevious,
            canNext = track.canNext,
            canPlayPause = track.canPlayPause,
            metrics = metrics,
            fade = hudFade,
            // 隐藏时真的不可点 —— `Modifier.alpha(0f)` 不影响命中测试，
            // 少了这一条会变成"看不见的地方点一下，歌突然跳了"。
            interactive = hudVisible,
            driftX = driftX,
            driftY = driftY,
            onPrevious = {
                source.previous()
                wake()
            },
            onPlayPause = {
                source.playPause()
                wake()
            },
            onNext = {
                source.next()
                wake()
            },
            onSeek = { position ->
                source.seekTo(position)
                wake()
            },
            onImportLyrics = {
                importLauncher.launch(arrayOf("*/*"))
                wake()
            },
            accountLabel = hudAccountLabel(loginState),
            onAccountClick = {
                showLoginPane = true
                wake()
            },
            // 已登录且壳层提供了曲库源时才给这颗 chip（null = 不渲染）。
            onPickTrack = if (loginState.isUsableCredential && library != null) {
                {
                    showDrawer = true
                    wake()
                }
            } else {
                null
            },
            )
        }

        // ---- 登录浮层 ----------------------------------------------------
        //
        // 放在**最后**（z 序最高）：它要盖住歌词、HUD 与状态卡。
        // 遮罩自己消费点击，所以浮层展开时下面那层唤醒层收不到事件。
        if (showLoginPane) {
            QqLoginPane(
                state = loginState,
                metrics = paneMetrics,
                onStartLogin = onStartLogin,
                onCancel = onCancelLogin,
                onLogout = onLogout,
                onDismiss = { showLoginPane = false },
            )
        }

        // ---- 选歌抽屉（第 2 步）-------------------------------------------
        //
        // 与登录浮层并列的最高层。**library 为 null 时整个分支不出现** ——
        // 页面对"能不能选歌"的唯一判断依据就是这个抽象在不在。
        if (showDrawer && library != null && libraryState != null) {
            TrackDrawer(
                state = libraryState.value,
                metrics = drawerMetrics,
                onSelectTrack = { index -> library.playTrackAt(index) },
                onRefresh = { library.refresh() },
                onOpenFavorites = { library.openFavorites() },
                onOpenPlaylist = { id -> library.openPlaylist(id) },
                onDismiss = {
                    showDrawer = false
                    wake()
                },
            )
        }
    }
}

/**
 * HUD 账号 chip 上的文字。
 *
 * **脱敏**：只可能是"未登录"或昵称，绝不出现 uin 或 key 的任何片段 ——
 * 它直接画在屏幕上，用户会截图、也会投屏到车机。
 */
private fun hudAccountLabel(state: QqLoginState): String = when (state) {
    is QqLoginState.LoggedIn -> state.cred.nick.ifEmpty { "已登录" }
    QqLoginState.LoggedOut -> "未登录"
    QqLoginState.Preparing -> "登录中"
    is QqLoginState.QrReady, QqLoginState.Scanned, QqLoginState.Exchanging -> "扫码中"
    is QqLoginState.Expired -> "登录已过期"
    is QqLoginState.Failed -> "登录失败"
}

private const val VISUAL_CACHE_SIZE = 4
private const val FALLBACK_SEED = "deskterminal"
private const val DRIFT_STEPS = 20L

/** 顶部那行"歌词从哪来"。它同时是**验证时最直接的一条读据**。 */
private fun lyricsSourceLabel(doc: LyricsDoc, loading: Boolean): String {
    if (loading) return "歌词 · 获取中…"
    if (doc.unreachable) return "歌词 · 网络不可用（本地缓存也没有）"
    if (doc.instrumental) return "歌词 · 纯音乐"
    if (doc.isEmpty) return "歌词 · 未找到（可在左下导入本地 LRC）"
    val timing = if (doc.hasWordTiming) "逐字" else "逐行"
    return "歌词 · ${doc.source.label} · $timing · ${doc.lines.size} 行"
}

/**
 * 包名 → 短标签。**与首页媒体卡共用同一个函数**（[mediaSourceLabel]）——
 * 同一首歌在两张界面上的来源名必须一致，而"QQ音乐"这个映射
 * （含我们自己的 ExoPlayer 源，其 sourcePackage 是本包名）只有一处定义。
 */
private fun sourceLabelOf(packageName: String): String = mediaSourceLabel(packageName)

/**
 * 歌词层。
 *
 * 单独抽一个 composable，是为了把"读播放位置"这件事**关在这一个小盒子里**：
 * 整页只有它会因为 200 ms 的位置心跳而重组，背景 / 水印 / 圆环 / HUD 都不参与。
 */
@Composable
private fun LyricsLayer(
    doc: LyricsDoc,
    position: State<Long>,
    metrics: MusicMetrics,
    placeholder: String?,
    driftX: androidx.compose.ui.unit.Dp,
    driftY: androidx.compose.ui.unit.Dp,
) {
    LyricStage(
        doc = doc,
        positionMs = position.value.toInt(),
        metrics = metrics,
        placeholder = placeholder,
        driftX = driftX,
        driftY = driftY,
    )
}

/** 没有活跃会话时中央那张说明卡。三种状态各有各的话要说。 */
@Composable
private fun MusicStatusCard(
    status: PlaybackStatus,
    metrics: MusicMetrics,
    loginState: QqLoginState,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
    onOpenLogin: () -> Unit,
    /** 已登录时的"选歌"入口。null = 不渲染（没登录或没有曲库源）。 */
    onOpenDrawer: (() -> Unit)? = null,
) {
    val title: String
    val detail: String
    val clickable: (() -> Unit)?

    when (status) {
        PlaybackStatus.Checking -> {
            title = "正在检查媒体状态"
            detail = ""
            clickable = null
        }

        PlaybackStatus.NoPermission -> {
            title = "需要通知使用权"
            detail = "点这里前往系统设置开启"
            clickable = onOpenSettings
        }

        PlaybackStatus.Idle -> {
            title = "暂无播放"
            detail = "在任意音乐 App 里开始播放，这里会自动跟随"
            clickable = null
        }

        is PlaybackStatus.Active -> return
    }

    Column(
        modifier = modifier
            .padding(PaddingValues(horizontal = metrics.pad.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(metrics.hudTextGap.dp),
    ) {
        Text(
            text = title,
            fontSize = (metrics.lyricSizeSp * 0.72f).sp,
            lineHeight = (metrics.lyricSizeSp * 0.72f * MusicLayout.LYRIC_LINE_HEIGHT_EM).sp,
            color = Color.White,
            textAlign = TextAlign.Center,
            // 这一层单独挂点击（而不是整列挂），因为下面还要放一颗**另一个意图**的按钮。
            modifier = if (clickable != null) Modifier.clickable(onClick = clickable) else Modifier,
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                fontSize = metrics.artistSize,
                lineHeight = metrics.artistSize * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.62f),
                textAlign = TextAlign.Center,
            )
        }
        // 只有"需要用户做点什么才能登录"的状态才给这颗按钮。
        // 已登录时这里不该出现 —— 一个点不动的按钮比没有按钮更让人困惑。
        if (loginState is QqLoginState.LoggedOut ||
            loginState is QqLoginState.Expired ||
            loginState is QqLoginState.Failed
        ) {
            Text(
                text = if (loginState is QqLoginState.LoggedOut) "登录 QQ 音乐" else "重新登录",
                fontSize = metrics.artistSize,
                lineHeight = metrics.artistSize * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                modifier = Modifier
                    .padding(top = metrics.hudTextGap.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onOpenLogin)
                    .padding(horizontal = metrics.pad.dp, vertical = metrics.hudTextGap.dp),
            )
        }
        // 已登录 → 同一位置的主操作变成"选歌"。
        // 与上面那颗按钮**互斥**（登录态二选一），所以两条 if 不会同时成立。
        if (onOpenDrawer != null && loginState.isUsableCredential) {
            Text(
                text = "选歌",
                fontSize = metrics.artistSize,
                lineHeight = metrics.artistSize * MusicLayout.HUD_TEXT_LINE_HEIGHT_EM,
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                modifier = Modifier
                    .padding(top = metrics.hudTextGap.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onOpenDrawer)
                    .padding(horizontal = metrics.pad.dp, vertical = metrics.hudTextGap.dp),
            )
        }
    }
}
