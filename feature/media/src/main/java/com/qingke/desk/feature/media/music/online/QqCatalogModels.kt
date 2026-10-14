package com.qingke.desk.feature.media.music.online

import org.json.JSONArray
import org.json.JSONObject

/**
 * 音质档位。**枚举结构先留好，本期只开 128k 一档** ——
 * Phase 0 实测过 320k / flac 的"取链接 + 字节数"（`docs/2026-10-14-music-phase0-native-playback.md`
 * §2.2 / §4.3），但那两档的**实际播放**未验（同文档 §11），所以本期不放出来。
 *
 * 前缀与后缀是 Phase 0 逐字节实测的常量，不是猜的：
 *
 * | 档位 | 前缀 | 后缀 |
 * | --- | --- | --- |
 * | 128k | `M500` | `.mp3` |
 * | 320k | `M800` | `.mp3` |
 * | flac | `F000` | `.flac` |
 * | m4a | `C400` | `.m4a` |
 * | ape | `A000` | `.ape` |
 */
enum class QqQuality(val prefix: String, val ext: String, val label: String) {
    M128("M500", ".mp3", "128k"),
    M320("M800", ".mp3", "320k"),
    FLAC("F000", ".flac", "FLAC"),
    M4A("C400", ".m4a", "M4A"),
    APE("A000", ".ape", "APE"),
    ;

    companion object {
        /** 本期默认档。唯一对外可选的档位。 */
        val DEFAULT: QqQuality = M128
    }
}

/**
 * 曲库里的一首歌。**全部字段都是公开标识或元数据，不含任何凭证。**
 *
 * @param songId 数字 id（`songlist[].id`）。写操作用它，本期只读。
 * @param mid songmid。取链接的 `filename` 段用它。
 * @param mediaMid `media_mid`。**服务端会把请求里的 songmid 段规范化掉**（Phase 0 §4.3：
 *                送 36 字符、回 22 字符），所以真正参与拼 URL 的是它；缺失时退回 [mid]。
 * @param intervalMs 时长（上游单位是秒）。
 * @param size128 128k 档声明字节数。Phase 0 的"完整曲目"判据拿它对照 CDN 的 Content-Range。
 */
data class QqTrack(
    val songId: String,
    val mid: String,
    val mediaMid: String,
    val name: String,
    val artists: String,
    val album: String,
    val albumMid: String,
    val intervalMs: Long,
    val size128: Long,
) {
    /** 取链接用的 media 段：`media_mid` 优先，缺失退回 songmid（Phase 0 实测两者等价）。 */
    val mediaIdOrMid: String get() = mediaMid.ifEmpty { mid }

    /** 列表行的身份键。与 [com.qingke.desk.feature.media.music.NowPlaying.frameKey] 同思路：稳定、可写进日志。 */
    val key: String get() = "$mid|$name|$intervalMs"
}

/**
 * 歌单摘要（"我喜欢"之外的用户歌单）。
 *
 * @param id 歌单 id（`disstid`）。拉它的歌曲时原样回传。
 */
data class QqPlaylistSummary(
    val id: String,
    val name: String,
    val coverUrl: String,
    val songCount: Int,
)

/**
 * 曲库状态。**抽屉的唯一数据来源** —— 它不认识 musicu、不认识凭证，
 * 与 [QqLoginState] 只给界面看是同一条边界。
 *
 * @param tracks 当前列表的歌曲（"我喜欢"或某个歌单）。
 * @param selectedPlaylistId null = "我喜欢"，否则是 [QqPlaylistSummary.id]。
 * @param resolvingIndex 正在取播放链接的行下标；-1 = 没有在解析。
 * @param nowPlayingMid 正在播放的 songmid（仅本源播放时非空），供抽屉高亮。
 * @param loading 列表加载中（拉歌单/拉歌曲）。
 * @param error 上一次失败的原因。**已经过脱敏**（阶段名 + 上游码，无凭证）。
 */
data class QqLibraryState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val tracks: List<QqTrack> = emptyList(),
    val playlists: List<QqPlaylistSummary> = emptyList(),
    val selectedPlaylistId: String? = null,
    val resolvingIndex: Int = -1,
    val nowPlayingMid: String? = null,
    val error: String? = null,
) {
    /** 当前列表的名字（抽屉标题用）。 */
    val listTitle: String
        get() = when (selectedPlaylistId) {
            null -> "我喜欢"
            else -> playlists.firstOrNull { it.id == selectedPlaylistId }?.name ?: "歌单"
        }
}

/**
 * 专辑封面 URL（公开 CDN、**不带任何凭证**）。
 *
 * 与取链那条路（`u.y.qq.com` + Cookie）完全不同：这是 y.qq.com 网页自己用的封面地址，
 * 匿名可拉。播放源（NowPlaying.artwork）与选歌抽屉的行封面共用这一个函数 ——
 * 两处各写一个模板字符串的话，将来换分辨率（300x300 → 800x800）必然只改一头。
 *
 * @param albumMid 专辑 mid。为空时调用方自己处理（返回 null / 占位图）。
 */
internal fun qqAlbumCoverUrl(albumMid: String): String =
    "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"

/**
 * 解析 `music.srfDissInfo.DissInfo / CgiGetDiss` 的 `songlist[]`。
 *
 * 条目形状是 QQ 的经典结构（Phase 0 的搜索响应同款），但**逐字段 defensive**：
 * 第三方会话的填充质量参差不齐，歌单条目也是——有的 `singer` 是数组有的是单对象，
 * 有的 `album.mid` 缺失。缺字段就退成空串/0，不让一次解析失败炸掉整个列表。
 */
internal fun parseQqSongList(data: JSONObject): List<QqTrack> {
    val arr = data.optJSONArray("songlist") ?: return emptyList()
    return buildList {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val mid = o.optString("mid")
            // 没有 mid 的条目既取不到链接也认不出身份，直接丢 —— 留着也是一行点不动的死条目。
            if (mid.isEmpty()) continue
            val file = o.optJSONObject("file") ?: JSONObject()
            add(
                QqTrack(
                    songId = o.optString("id"),
                    mid = mid,
                    mediaMid = firstNonEmpty(
                        o.optJSONObject("file")?.optString("media_mid").orEmpty(),
                        o.optString("media_mid"),
                    ),
                    name = firstNonEmpty(o.optString("name"), o.optString("title"), mid),
                    artists = singerNamesOf(o),
                    album = o.optJSONObject("album")?.optString("name").orEmpty(),
                    albumMid = o.optJSONObject("album")?.optString("mid").orEmpty(),
                    intervalMs = o.optLong("interval", 0L) * 1000L,
                    size128 = file.optLong("size_128mp3", 0L),
                ),
            )
        }
    }
}

/** `singer` 字段三种形状都见过：数组、单对象、缺省。 */
private fun singerNamesOf(o: JSONObject): String {
    val raw = o.opt("singer") ?: return ""
    val names = when (raw) {
        is JSONArray -> (0 until raw.length())
            .mapNotNull { (raw.optJSONObject(it) ?: return@mapNotNull null).optString("name").takeIf { n -> n.isNotEmpty() } }

        is JSONObject -> listOf(raw.optString("name")).filter { it.isNotEmpty() }
        else -> emptyList()
    }
    return names.joinToString(" / ")
}

private fun firstNonEmpty(vararg values: String): String =
    values.firstOrNull { it.isNotEmpty() }.orEmpty()
