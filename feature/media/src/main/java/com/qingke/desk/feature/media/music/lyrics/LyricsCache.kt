package com.qingke.desk.feature.media.music.lyrics

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

private const val TAG = "DeskLyricsCache"

/**
 * 歌词的本机缓存。**原始 LRC 文本 + 一层"查过但确实没有"的负缓存**。
 *
 * 缓存的键是曲目身份（[com.qingke.desk.feature.media.music.NowPlaying.frameKey]），
 * 落成文件的文件名是它的 SHA-1 —— 直接用曲目名当文件名会踩到三件事：
 * 中文/emoji 文件名在不同 ROM 上的编码、`/` 与 `\` 之类的非法字符、
 * 以及文件名长度上限。哈希之后一律是 40 个 ASCII 字符，没有例外。
 *
 * ---------------------------------------------------------------------------
 * 为什么要有负缓存
 *
 * 没有它的话，一首**确实没有歌词**的歌（比如纯器乐、冷门小样）会在每次切回它时
 * 重新走一遍网络。设备离线时每次都要等一轮连接超时，而结果是注定的。
 * 负缓存只管 [NEGATIVE_TTL_MS]（6 小时）—— 不是"永远记住"：
 * 在线歌词库是动态的，今天没有的歌词过几天可能就有了。
 *
 * ---------------------------------------------------------------------------
 * 容量
 *
 * 上限 [MAX_ENTRIES] 个文件，超了就按最后修改时间删最旧的。桌面终端是长期运行的设备，
 * 不设上限的话这个目录只会单向增长。单条 10 KB 量级 × 200 条 ≈ 2 MB，可以接受。
 *
 * 本类所有方法都是**阻塞 IO**：只在 `Dispatchers.IO` 上调用。
 */
class LyricsCache(context: Context) {

    private val dir: File = File(context.filesDir, "lyrics")

    /** 读缓存。null = 没缓存过。 */
    fun read(key: String): String? {
        val file = fileFor(key) ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }
            .onFailure { Log.w(TAG, "read failed: ${it.javaClass.simpleName}") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            // 顺手刷新访问时间，让 LRU 淘汰反映"最近用过"而不是"最近写过"。
            .also { runCatching { file.setLastModified(System.currentTimeMillis()) } }
    }

    fun write(key: String, raw: String) {
        if (raw.isBlank()) return
        val file = fileFor(key) ?: return
        runCatching {
            ensureDir()
            file.writeText(raw, Charsets.UTF_8)
            prune()
        }.onFailure { Log.w(TAG, "write failed: ${it.javaClass.simpleName}") }
    }

    /**
     * 这首歌是不是"刚查过、确实没有歌词"。
     *
     * TTL **记在文件内容里**而不是全局常量：负缓存有两种来历（见 [markMissing]），
     * "网络失败"的比"确认没有"的过期快得多。读出来解析不了就当已过期 ——
     * 代价只是多拉一次，而方向永远是"更愿意重试"。
     */
    fun isKnownMissing(key: String): Boolean {
        val file = negativeFileFor(key) ?: return false
        if (!file.isFile) return false
        val ttl = runCatching { file.readText(Charsets.UTF_8).trim().toLongOrNull() }
            .getOrNull() ?: DEFAULT_MISSING_TTL_MS
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > ttl) {
            runCatching { file.delete() }
            return false
        }
        return true
    }

    /**
     * 标记"这首歌这次没拿到"。
     *
     * @param ttlMs 这条负记录的有效期。**两个调用方必须给不同的值**：
     *   "确认库里没有"（404 / 搜完无同步轴）用 [DEFAULT_MISSING_TTL_MS] ——
     *   结果是注定的，6 小时内不必再问；
     *   "网络暂时不可达"（IOException / 5xx）用短得多的 [TRANSIENT_MISSING_TTL_MS] ——
     *   把瞬时失败固化成 6 小时是**真 bug**（2026-10-14 真机抓到：一首明明在库里的歌，
     *   因为拉取时恰好网络抖了一下，之后 6 小时全部显示"未找到同步歌词"），
     *   只需要一个"别在断网时反复敲服务器"的短闸，5 分钟足够。
     */
    fun markMissing(key: String, ttlMs: Long = DEFAULT_MISSING_TTL_MS) {
        val file = negativeFileFor(key) ?: return
        runCatching {
            ensureDir()
            file.writeText(ttlMs.toString(), Charsets.UTF_8)
        }.onFailure { Log.w(TAG, "neg write failed: ${it.javaClass.simpleName}") }
    }

    /** 清掉某首歌的全部痕迹。用户导入本地歌词后调它，避免旧的负缓存把新歌词挡住。 */
    fun invalidate(key: String) {
        runCatching { fileFor(key)?.delete() }
        runCatching { negativeFileFor(key)?.delete() }
    }

    fun clear() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }

    // ------------------------------------------------------------------ 内部

    private fun ensureDir() {
        if (!dir.isDirectory) dir.mkdirs()
    }

    private fun fileFor(key: String): File? = File(dir, "${hash(key)}.lrc")

    private fun negativeFileFor(key: String): File? = File(dir, "${hash(key)}.none")

    /**
     * 超过上限就删最旧的。
     *
     * 只在**写入之后**跑（不是每次读）：读是高频路径，不该带上一次目录扫描。
     * 每次写入扫一遍目录，代价是一个 200 项的 listFiles —— 换歌才发生一次，可以忽略。
     */
    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_ENTRIES)
            .forEach { runCatching { it.delete() } }
    }

    private fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { b ->
                val v = b.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    // TRANSIENT_MISSING_TTL_MS 对外开放（LyricsRepository 要用它）；
    // 其余是本类实现细节。DEFAULT_MISSING_TTL_MS 同时是 markMissing 的默认参数值。
    companion object {
        /** "确认库里没有"的负缓存时长。 */
        private const val DEFAULT_MISSING_TTL_MS = 6 * 60 * 60 * 1000L

        /** "网络暂时不可达"的负缓存时长。挡住断网时的反复重试，又不固化失败。 */
        const val TRANSIENT_MISSING_TTL_MS = 5 * 60 * 1000L

        private const val MAX_ENTRIES = 200

        private val HEX = "0123456789abcdef".toCharArray()
    }
}
