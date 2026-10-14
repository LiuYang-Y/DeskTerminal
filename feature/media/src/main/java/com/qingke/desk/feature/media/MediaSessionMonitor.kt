package com.qingke.desk.feature.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "DeskMedia"

/**
 * 媒体会话监视器。读第三方播放器的 MediaSession，并把 transport controls 转发过去。
 *
 * ## 权限前提（这条是硬约束，不是实现选择）
 *
 * [MediaSessionManager.getActiveSessions] 要求调用方二选一：
 *   1. 持有 MEDIA_CONTENT_CONTROL —— signature 级，第三方 App 拿不到；
 *   2. 已启用的 NotificationListenerService，并把它的 ComponentName 传进去。
 * 本模块走第 2 条。没授权时 getActiveSessions 直接抛 SecurityException，
 * 所以**每一次调用都必须包在 runCatching 里**，否则进程会崩在 binder 返回处。
 *
 * ## 为什么用"已启用包名"判断而不是等 onListenerConnected
 *
 * isEnabled() 读的是系统设置（NotificationManagerCompat.getEnabledListenerPackages），
 * 它是同步的、随时可查的；而服务的绑定时机由系统决定，应用在前台时也可能尚未绑定。
 * 用设置值做判据，用户从设置页勾完回来立刻就能生效，不用等系统重新绑一次。
 *
 * ## 生命周期
 * [start] / [stop] 成对调用。stop 之后所有 callback 都会注销 —— MediaController
 * 的 callback 是挂在会话上的，漏注销会让已销毁的 UI 继续被回调。
 */
class MediaSessionMonitor(context: Context) {

    private val appContext = context.applicationContext
    private val manager: MediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, MediaNotificationListener::class.java)

    /**
     * MediaSession 的回调全部走主线程 Handler。
     * 不直接用 context.mainExecutor：那是 API 28 才加的，minSdk 26 上取不到。
     */
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<MediaUiState>(MediaUiState.Checking)
    val state: StateFlow<MediaUiState> = _state.asStateFlow()

    /** 当前持有的会话 -> 挂在它上面的回调。LinkedHashMap 保留 getActiveSessions 的优先顺序。 */
    private val active = LinkedHashMap<MediaController, MediaController.Callback>()

    /** 当前卡片正在展示的那个会话。控制指令只发给它。 */
    @Volatile
    private var activeController: MediaController? = null

    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null
    private var started = false

    private val bridgeCallback: () -> Unit = { handler.post { refresh() } }

    fun start() {
        if (started) return
        started = true
        MediaListenerBridge.register(bridgeCallback)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        MediaListenerBridge.unregister(bridgeCallback)
        releaseAll()
    }

    /** 重新查一遍。授权状态变化、回前台、服务重绑都调它。 */
    fun refresh() {
        if (!started) return
        if (!isEnabled()) {
            Log.d(TAG, "notification listener not enabled -> NoPermission")
            releaseAll()
            _state.value = MediaUiState.NoPermission
            return
        }

        val list = runCatching { manager.getActiveSessions(listenerComponent) }
            .onFailure { Log.w(TAG, "getActiveSessions failed: ${it.javaClass.simpleName}") }
            .getOrNull()

        if (list == null) {
            // 设置里开了但系统还没认（极少见）。保持上一次状态，不把界面打成空。
            Log.w(TAG, "getActiveSessions returned null")
            return
        }

        syncControllers(list)
        ensureSessionsListener()
        emitCurrent()
    }

    /** 通知使用权是否已授予本应用。 */
    fun isEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

    // ---------------------------------------------------------------- 会话同步

    private fun syncControllers(list: List<MediaController>) {
        val incoming = list.associateBy { it.sessionToken }
        // 先摘掉已经不存在的
        val gone = active.keys.filter { it.sessionToken !in incoming }
        gone.forEach { controller ->
            active.remove(controller)?.let { runCatching { controller.unregisterCallback(it) } }
        }
        // 再补上新的
        list.forEach { controller ->
            if (active.containsKey(controller)) return@forEach
            val callback = controllerCallback()
            active[controller] = callback
            // 实测（compileSdk 37）：MediaController 只保留 (Callback, Handler?) 这一档重载，
            // (Executor, Callback) 那一档在本 SDK 的桩里不存在 —— 别照抄旧示例。
            runCatching { controller.registerCallback(callback, handler) }
                .onFailure { Log.w(TAG, "registerCallback failed: ${it.message}") }
        }
    }

    private fun controllerCallback(): MediaController.Callback =
        object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                handler.post { emitCurrent() }
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                handler.post { emitCurrent() }
            }

            override fun onSessionDestroyed() {
                handler.post { refresh() }
            }
        }

    @Suppress("DEPRECATION")
    private fun ensureSessionsListener() {
        if (sessionsListener != null) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { refresh() }
        // 同 registerCallback：(ComponentName, Executor, listener) 在本 SDK 的桩里没有，
        // 实际可用的是 (listener, ComponentName?, Handler?)。
        val ok = runCatching {
            manager.addOnActiveSessionsChangedListener(listener, listenerComponent, handler)
        }.onFailure { Log.w(TAG, "addOnActiveSessionsChangedListener failed: ${it.message}") }
            .isSuccess
        if (ok) sessionsListener = listener
    }

    private fun releaseAll() {
        active.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        active.clear()
        activeController = null
        runCatching {
            sessionsListener?.let { manager.removeOnActiveSessionsChangedListener(it) }
        }
        sessionsListener = null
    }

    // ------------------------------------------------------------------- 取数

    /**
     * 挑一个"最该被展示"的会话。
     *
     * 排序键依次是：有标题 > 正在播 > 系统给的优先级（列表下标）。
     * 把"有标题"放在最前面，是因为实测到某些 App（例如短视频）会挂着
     * 一条 STATE_PLAYING 但完全没有元数据的会话 —— 若只按 playing 挑，
     * 卡片会显示一条空曲目，把真正在放音乐的那条压掉。
     */
    private fun pick(): MediaController? {
        val all = active.keys.toList()
        return all
            .mapIndexed { index, controller -> index to controller }
            .sortedWith(
                compareByDescending<Pair<Int, MediaController>> {
                    readTitle(it.second).isNotBlank()
                }.thenByDescending {
                    it.second.playbackState?.state == PlaybackState.STATE_PLAYING
                }.thenBy { it.first },
            )
            .firstOrNull()
            ?.second
    }

    private fun emitCurrent() {
        if (!isEnabled()) {
            _state.value = MediaUiState.NoPermission
            return
        }
        val controller = pick()
        if (controller == null) {
            activeController = null
            _state.value = MediaUiState.Idle
            return
        }
        activeController = controller
        _state.value = MediaUiState.Active(readTrack(controller))
    }

    private fun readTrack(controller: MediaController): MediaTrack {
        val metadata = runCatching { controller.metadata }.getOrNull()
        val playback = runCatching { controller.playbackState }.getOrNull()
        val actions = playback?.actions ?: 0L

        return MediaTrack(
            title = readTitle(controller),
            artist = firstNonBlank(
                metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            ),
            album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
            sourcePackage = runCatching { controller.packageName }.getOrDefault(""),
            artwork = readArtwork(metadata),
            isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
            positionMs = playback?.position ?: MediaTrack.UNKNOWN_POSITION,
            durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
            positionTakenAtMs = SystemClock.elapsedRealtime(),
            canSkipPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
            canSkipNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
            canPlayPause = actions and (
                PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or
                    PlaybackState.ACTION_PLAY_PAUSE
                ) != 0L,
            // 跳转进度的能力位。注意它是 ACTION_SEEK_TO 而不是 ACTION_PLAY_FROM_*
            // —— 后者是"从头/从某条媒体项播"，与拖进度条不是一回事。
            canSeek = actions and PlaybackState.ACTION_SEEK_TO != 0L,
        )
    }

    private fun readTitle(controller: MediaController): String {
        val metadata = runCatching { controller.metadata }.getOrNull() ?: return ""
        return firstNonBlank(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
        )
    }

    /**
     * 封面。第三方给什么的都有，按"Bitmap 优先、URI 兜底"的顺序试。
     *
     * URI 那一档必须整段包 runCatching：那是别的应用的 content://，
     * 我们没有它的读权限，openInputStream 抛 SecurityException 是常态而不是异常。
     * 解码也做了 2 倍降采样，避免把 1080 的封面原图塞进卡片。
     */
    private fun readArtwork(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).forEach { key ->
            val bitmap = runCatching { metadata.getBitmap(key) }.getOrNull()
            if (bitmap != null) return bitmap
        }

        val uriText = runCatching {
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        }.getOrNull()
        if (uriText.isNullOrBlank()) return null

        return runCatching {
            val uri = Uri.parse(uriText)
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.onFailure { Log.d(TAG, "artwork uri decode failed: ${it.javaClass.simpleName}") }
            .getOrNull()
    }

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    // ------------------------------------------------------------------- 控制

    /**
     * 发一条 transport control。
     *
     * 播放/暂停不能按"当前是不是在播"简单取反：PlaybackState 可能是 null
     * （会话刚建还没提交状态），此时只能按"意图是播放"处理。
     * 另外整段包 runCatching —— 目标会话随时可能已经失效，
     * sendCommand 会抛 DeadObjectException，不能让它冒到 UI 线程外面去。
     */
    fun command(command: MediaCommand) {
        val controller = activeController
        if (controller == null) {
            Log.w(TAG, "command $command dropped: no active session")
            return
        }
        val controls = runCatching { controller.transportControls }.getOrNull() ?: return
        runCatching {
            when (command) {
                MediaCommand.PREVIOUS -> controls.skipToPrevious()
                MediaCommand.NEXT -> controls.skipToNext()
                MediaCommand.PLAY_PAUSE -> {
                    val state = runCatching { controller.playbackState?.state }.getOrNull()
                    if (state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
                }
            }
        }.onFailure { Log.w(TAG, "command $command failed: ${it.javaClass.simpleName}") }
        Log.d(TAG, "command $command -> ${controller.packageName}")
    }

    /**
     * 跳转进度。给音乐页的可拖动进度条用。
     *
     * 与 [command] 同一套防护：会话可能已失效（`DeadObjectException`），
     * 整段包 runCatching，不让它冒到 UI 线程外面。
     *
     * **不做能力位二次判断** —— 判断在读取侧（[MediaTrack.canSeek]）做，界面据此决定
     * 进度条可不可拖。这里再判一次的话，遇到"能力位没填但实际能跳"的播放器
     * （实测常见）就会白白丢掉一个能用得上的功能。
     *
     * 负数/超过时长由系统侧自己夹取，这里只挡住明显的非法值。
     */
    fun seekTo(positionMs: Long) {
        if (positionMs < 0L) return
        val controller = activeController
        if (controller == null) {
            Log.w(TAG, "seekTo dropped: no active session")
            return
        }
        val controls = runCatching { controller.transportControls }.getOrNull() ?: return
        runCatching { controls.seekTo(positionMs) }
            .onFailure { Log.w(TAG, "seekTo($positionMs) failed: ${it.javaClass.simpleName}") }
        Log.d(TAG, "seekTo $positionMs -> ${controller.packageName}")
    }
}
