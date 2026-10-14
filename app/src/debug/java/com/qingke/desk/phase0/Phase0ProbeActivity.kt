/*
 * Phase 0（路线 B）原生可行性探针 —— **只存在于 debug 变体**。
 *
 * 这是 spike/music-phase0-qq 分支的一次性验证工具，不是产品代码：
 *   · 它住在 src/debug/，release 构建里根本不存在（探针不进正式构建）；
 *   · 它唯一依赖 media3-exoplayer，且同样是 debugImplementation；
 *   · 它不做登录、不做搜索、不做 UI —— 只回答一个问题：
 *     「拿本机已有的合法凭证，Kotlin 直连 QQ 标准客户端接口，能不能取到完整可播的 CDN URL 并真的放出声？」
 *
 * 它验证五条断言（对应 docs/09-原生重构评估.md §6 的 Phase 0 验收表）：
 *   ① UrlGetVkey 返回非空 purl
 *   ② sip 为空时兜底域名能解析出可播地址，且 CDN 返回的 Content-Range 总量 = 搜索声明的 size128
 *   ③ ExoPlayer 能 open 到 READY，且 currentPosition 单调推进（真出声，不是"报成功但没响"）
 *   ④ CPU 由外部脚本按 /proc/<pid>/stat 采样（探针不测自己，避免自证）
 *   ⑤ 换 songmid 重复
 *
 * 用法（真机）：
 *   adb shell am start -n com.qingke.desk/com.qingke.desk.phase0.Phase0ProbeActivity \
 *     --es songmid 0039MnYb0qxYhV --es media_mid 003Qui1q2u1Zho \
 *     --es label 'VIP-晴天' --ei size128 4317292 --ei interval 269 \
 *     --es mode play --ei maxsec 200
 *
 *   mode=probe 只做到第 ② 步（取链接与量字节），不播放 —— 用于快速扫多首。
 *   mode=play  再走第 ③ 步。
 *
 * 日志：files/<phase0.log>，同时镜像到 logcat 的 "Phase0" 标签。
 *   adb exec-out run-as com.qingke.desk cat files/phase0.log
 */

package com.qingke.desk.phase0

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class Phase0ProbeActivity : Activity() {

    // ---- 日志 ---------------------------------------------------------------
    @Volatile private var logFile: File? = null

    private fun ts(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun log(tag: String, msg: String) {
        val line = "${ts()} [$tag] $msg"
        Log.i(LOG_TAG, line)
        val f = logFile ?: return
        synchronized(this) {
            try {
                f.appendText(line + "\n")
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "写日志失败: ${t.message}")
            }
        }
    }

    // ---- 参数 ---------------------------------------------------------------
    private var songmid = ""
    private var mediaMid = ""
    private var quality = "128"
    private var label = ""
    private var declaredSize = 0L
    private var declaredInterval = 0
    private var mode = "play"
    private var maxSec = 200

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())

    // 位置单调性统计（断言 ③ 的判据来源）
    private var lastPos = -1L
    private var posSamples = 0
    private var posRegressions = 0
    private var loopWraps = 0
    private var lastWrapAtTick = -1
    private var lastWrapIntervalMs = -1L
    private var firstPos = -1L
    private var maxPos = -1L
    private var readyAtMs = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        logFile = File(filesDir, "phase0.log")

        songmid = intent.getStringExtra("songmid") ?: ""
        mediaMid = intent.getStringExtra("media_mid") ?: ""
        quality = intent.getStringExtra("quality") ?: "128"
        label = intent.getStringExtra("label") ?: ""
        declaredSize = intent.getIntExtra("size128", 0).toLong()
        declaredInterval = intent.getIntExtra("interval", 0)
        mode = intent.getStringExtra("mode") ?: "play"
        maxSec = intent.getIntExtra("maxsec", 200)

        log("RUN", "==== 新一次探针 ====")
        log("RUN", "label=$label songmid=$songmid media_mid=${mediaMid.ifEmpty { "(空,退回songmid)" }} " +
                "quality=$quality mode=$mode maxsec=${maxSec}s 声明size128=$declaredSize 声明interval=${declaredInterval}s")
        log("ENV", "build=debug model=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} " +
                "release=${android.os.Build.VERSION.RELEASE}")

        Thread { runProbe() }.start()
    }

    /**
     * 同一个实例再次收到 `am start`。
     *
     * 不处理时的实测症状：第二次 `am start` 被 singleInstance 直接吞掉 ——
     * 系统打印 "Activity not started, intent has been delivered to currently running
     * top-most instance."，探针**看起来跑过了却没有新日志**，很容易误判成"这次请求失败"。
     * 处理方式：把新 intent 收下再 recreate()，让一整轮探针从头跑。
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    // ---- 主流程（后台线程） --------------------------------------------------
    private fun runProbe() {
        val cred = readCredential()
        if (cred == null) {
            log("FAIL", "读不到凭证文件 files/phase0-cred.json —— 探针终止（不尝试任何无凭证路径）")
            return
        }
        log("CRED", "凭证已加载（只在本进程内存中；musicId 长度=${cred.musicId.length}，" +
                "musickey 长度=${cred.musickey.length}，loginType=${cred.loginType}）")

        if (songmid.isEmpty()) {
            log("FAIL", "缺 songmid 参数")
            return
        }

        // ------------------------------------------------------------------
        // 断言 ①：取 purl
        // ------------------------------------------------------------------
        val t0 = System.currentTimeMillis()
        val vkey = try {
            requestVkey(cred, songmid, mediaMid.ifEmpty { songmid }, quality)
        } catch (t: Throwable) {
            log("FAIL", "UrlGetVkey 抛异常: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        log("VKEY", "耗时 ${System.currentTimeMillis() - t0}ms | http=${vkey.httpStatus} " +
                "globalCode=${vkey.globalCode} upstreamCode=${vkey.upstreamCode}")

        if (vkey.purl.isEmpty()) {
            log("A1", "断言① 失败：purl 为空（result=${vkey.result} errtype=${vkey.errtype}）")
            return
        }
        log("A1", "断言① 成立：purl 非空（长度=${vkey.purl.length}，'?' 前=${vkey.purl.substringBefore('?').length} 字符，" +
                "文件名段首4=${vkey.purl.take(4)}，含 vkey= 参数=${vkey.purl.contains("vkey=")}）")
        log("VKEY", "sip 条数=${vkey.sip.size} | midurlinfo.result=${vkey.result} " +
                "pneedbuy=${vkey.pneedbuy} isbuy=${vkey.isbuy} uiAlert=${vkey.uiAlert} " +
                "filename 段首4=${vkey.filename.take(4)}")

        // ------------------------------------------------------------------
        // 断言 ②：解析出可播地址，并用 Range 量出真实总字节数
        // ------------------------------------------------------------------
        val candidates = buildList {
            addAll(vkey.sip.filter { !it.startsWith("http://ws") })
            addAll(vkey.sip.filter { it.startsWith("http://ws") })
            // sip 为空时（UrlGetVkey 实测恒为空）的兜底列表。
            // 实测结论：同一 purl 在下列 **https** 域名上全部 206 ——
            // 所以正式实现不需要开明文流量，这里优先用 https。
            add("https://ws.stream.qqmusic.qq.com/")
            add("https://dl.stream.qqmusic.qq.com/")
            add("https://isure.stream.qqmusic.qq.com/")
            add("https://sjy6.stream.qqmusic.qq.com/")
            add("http://sjy6.stream.qqmusic.qq.com/")
        }.distinct()

        var chosen: String? = null
        var totalBytes = -1L
        for (dm in candidates) {
            val probe = probeRange(dm + vkey.purl)
            val verdict = when {
                probe.status == 206 || probe.status == 200 -> "OK"
                else -> "不可用"
            }
            log("A2", "候选域名 ${dm} -> http=${probe.status} contentRange=$verdict(${probe.contentRange})")
            if (chosen == null && (probe.status == 206 || probe.status == 200)) {
                chosen = dm
                totalBytes = probe.totalBytes
            }
        }
        if (chosen == null) {
            log("A2", "断言② 失败：所有候选域名都拿不到 200/206")
            return
        }

        val sizeVerdict = if (declaredSize > 0) {
            if (totalBytes == declaredSize) "逐字节相等 -> 是完整曲目"
            else "**不相等**（差 ${totalBytes - declaredSize}）-> 可能是试听片段或换档"
        } else "无声明值可比"
        log("A2", "断言② 成立：选定域名=$chosen 真实总字节=$totalBytes 声明size128=$declaredSize -> $sizeVerdict")

        if (mode != "play") {
            log("END", "mode=$mode，不播放，探针结束")
            return
        }

        // ------------------------------------------------------------------
        // 断言 ③：交给 ExoPlayer，看它能不能真的放出声
        // ------------------------------------------------------------------
        val url = chosen + vkey.purl
        handler.post { startPlayback(url, totalBytes) }
    }

    private fun startPlayback(url: String, totalBytes: Long) {
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .build()
        player = p

        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val name = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?$state"
                }
                if (state == Player.STATE_READY && readyAtMs < 0) readyAtMs = System.currentTimeMillis()
                log("STATE", "onPlaybackStateChanged=$name " +
                        "dur=${p.duration}ms isPlaying=${p.isPlaying}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                log("STATE", "onIsPlayingChanged=$isPlaying pos=${p.currentPosition}")
            }

            override fun onPlayerError(error: PlaybackException) {
                log("FAIL", "onPlayerError: ${error.errorCodeName} ${error.message}")
                log("FAIL", "cause=${error.cause?.javaClass?.name}: ${error.cause?.message}")
            }
        })

        p.setMediaItem(MediaItem.fromUri(url))
        // 单曲循环：与 folia 那次 30 分钟功耗测试**同条件**（单曲循环）。
        // 这也是必须的 —— 目标曲目只有 269s，不循环的话 4 分半后就是 ENDED + 静止，
        // 30 分钟窗口里后半段测到的是"空转"而不是"播放"。
        p.repeatMode = Player.REPEAT_MODE_ONE
        log("PLAY", "prepare() URL 协议=${url.substringBefore("://")} 路径长度=${url.substringAfter("://").length} " +
                "repeatMode=REPEAT_MODE_ONE")
        p.prepare()
        p.play()

        // 每秒采一次位置：断言③ 的判据就是这条序列本身
        val ticker = object : Runnable {
            var elapsed = 0
            override fun run() {
                val pl = player ?: return
                val pos = pl.currentPosition
                val dur = pl.duration
                if (lastPos >= 0 && pos < lastPos) {
                    /*
                     * 必须把两种"位置变小"分开，否则工具会自己造出假阴性。
                     *
                     * 实测教训：31 分钟单曲循环那次，第一版判据把 7 次**循环回绕**全算成了
                     * "位置回退"，直接输出"断言③ 不成立"。而那 7 次都是
                     * `269xxx -> ~0`，间隔 269.63~269.69s（均值 269.655s），
                     * 与曲长 269.746s 吻合 —— 是 REPEAT_MODE_ONE 的正常回绕。
                     *
                     * 所以回绕的判据是三个条件同时成立：开了单曲循环 + 上一位置已经贴近曲尾
                     * （≥95% 时长）+ 新位置接近 0。三者缺一就是真的异常，不能放过。
                     */
                    val isWrap = pl.repeatMode == Player.REPEAT_MODE_ONE && dur > 0 &&
                            lastPos >= dur * 95 / 100 && pos < 2000
                    if (isWrap) {
                        loopWraps++
                        if (lastWrapAtTick >= 0) lastWrapIntervalMs = (elapsed - lastWrapAtTick) * 1000L
                        lastWrapAtTick = elapsed
                        log("TICK", "循环回绕 #$loopWraps：$lastPos -> $pos" +
                                (if (lastWrapIntervalMs > 0) "（距上次回绕 ${lastWrapIntervalMs}ms，对照曲长 ${dur}ms）" else ""))
                    } else {
                        posRegressions++
                        log("TICK", "**位置回退（非回绕，需排查）** $lastPos -> $pos（计数=$posRegressions）")
                    }
                }
                if (firstPos < 0) firstPos = pos
                if (pos > maxPos) maxPos = pos
                lastPos = pos
                posSamples++
                elapsed += 1
                log("TICK", "t=${elapsed}s pos=${pos}ms dur=${pl.duration}ms buffered=${pl.bufferedPosition} " +
                        "isPlaying=${pl.isPlaying} state=${pl.playbackState}")

                if (elapsed >= maxSec) {
                    val durVerdict = if (declaredInterval > 0 && dur > 0) {
                        val d = Math.abs(dur - declaredInterval * 1000L)
                        if (d <= 3000) "与搜索声明的 ${declaredInterval}s 吻合（差 ${d}ms）-> 完整曲目"
                        else "**与声明 ${declaredInterval}s 差 ${d}ms** -> 可疑（试听片段约 30s）"
                    } else "无声明时长可比"
                    log("A3", "断言③ 判据：采样 $posSamples 次，**真回退 $posRegressions 次**，" +
                            "循环回绕 $loopWraps 次，首位置=$firstPos 末位置=$maxPos")
                    if (loopWraps > 0 && lastWrapIntervalMs > 0) {
                        log("A3", "回绕间隔均值=最近一次 ${lastWrapIntervalMs}ms，对照曲长 ${dur}ms —— " +
                                "数量级吻合则说明回绕就是单曲循环本身")
                    }
                    log("A3", "播放器时长=${dur}ms -> $durVerdict")
                    log("A3", "CDN 总字节=$totalBytes（对照搜索声明）")
                    val ok = posSamples > 5 && posRegressions == 0 && maxPos > 3000
                    log("A3", if (ok) "断言③ 成立：位置单调推进（已剔除循环回绕），播放器真实在跑"
                            else "断言③ **不成立**，见上")
                    log("END", "探针播放阶段结束")
                    pl.stop()
                    return
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(ticker, 1000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        super.onDestroy()
    }

    // ---- 凭证（只读设备私有目录；不落日志、不外发） ----------------------------
    private class Cred(
        val musicId: String,
        val musickey: String,
        val loginType: Int,
        val openUdid: String,
        val androidId: String,
        val osRelease: String,
        val sdk: Int,
        val model: String,
        val fingerprint: String,
        val qimei: String,
        val qimei36: String,
        val sessionUid: String,
        val sessionSid: String,
        val channelId: String,
    )

    private fun readCredential(): Cred? {
        val f = File(filesDir, "phase0-cred.json")
        if (!f.isFile) return null
        return try {
            val root = JSONObject(f.readText())
            val d = root.getJSONObject("device")
            val c = root.getJSONObject("credential")
            Cred(
                musicId = c.optString("musicId"),
                musickey = c.optString("musickey"),
                loginType = c.optInt("loginType", 0),
                openUdid = d.optString("openUdid"),
                androidId = d.optString("androidId"),
                osRelease = d.optString("osRelease"),
                sdk = d.optInt("sdk", 0),
                model = d.optString("model"),
                fingerprint = d.optString("fingerprint"),
                qimei = d.optString("qimei"),
                qimei36 = d.optString("qimei36"),
                sessionUid = d.optString("sessionUid"),
                sessionSid = d.optString("sessionSid"),
                channelId = d.optString("channelId", "10003505"),
            )
        } catch (t: Throwable) {
            log("FAIL", "凭证文件解析失败: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    // ---- 网络 ---------------------------------------------------------------

    private class VkeyResult(
        val httpStatus: Int,
        val globalCode: Int,
        val upstreamCode: Int,
        val sip: List<String>,
        val purl: String,
        val result: Int,
        val errtype: String,
        val filename: String,
        val pneedbuy: Int,
        val isbuy: Int,
        val uiAlert: Int,
    )

    /**
     * music.vkey.GetVkey / UrlGetVkey。与 folia 的 callMusicu 同形：
     * 一次 POST，body = { comm, req_0:{module,method,param} }，凭证在 Cookie 头里。
     * **没有签名参数** —— 这一条是原生移植成本的关键（见报告）。
     */
    private fun requestVkey(cred: Cred, songmid: String, mediaId: String, quality: String): VkeyResult {
        val (prefix, ext) = when (quality) {
            "320" -> "M800" to ".mp3"
            "flac" -> "F000" to ".flac"
            "m4a" -> "C400" to ".m4a"
            "ape" -> "A000" to ".ape"
            else -> "M500" to ".mp3"
        }
        val guid = UUID.randomUUID().toString().replace("-", "")

        val comm = JSONObject().apply {
            put("ct", 11)
            put("cv", 14090008)
            put("v", 14090008)
            put("chid", cred.channelId)
            put("tmeAppID", "qqmusic")
            put("QIMEI", cred.qimei)
            put("QIMEI36", cred.qimei36)
            put("OpenUDID", cred.openUdid)
            put("udid", cred.openUdid)
            put("OpenUDID2", cred.openUdid)
            put("aid", cred.androidId)
            put("os_ver", cred.osRelease)
            put("phonetype", cred.model)
            put("devicelevel", cred.sdk.toString())
            put("newdevicelevel", cred.sdk.toString())
            put("rom", cred.fingerprint)
            if (cred.sessionUid.isNotEmpty()) put("uid", cred.sessionUid)
            if (cred.sessionSid.isNotEmpty()) put("sid", cred.sessionSid)
            put("qq", cred.musicId)
            put("authst", cred.musickey)
            put("tmeLoginType", cred.loginType)
        }

        val param = JSONObject().apply {
            put("filename", org.json.JSONArray(listOf("$prefix$songmid$mediaId$ext")))
            put("guid", guid)
            put("songmid", org.json.JSONArray(listOf(songmid)))
            put("songtype", org.json.JSONArray(listOf(0)))
            put("uin", cred.musicId)
            put("ctx", 0)
        }

        val body = JSONObject().apply {
            put("comm", comm)
            put("req_0", JSONObject().apply {
                put("module", "music.vkey.GetVkey")
                put("method", "UrlGetVkey")
                put("param", param)
            })
        }.toString().toByteArray(Charsets.UTF_8)

        val cookie = "uin=${cred.musicId}; qqmusic_uin=${cred.musicId}; " +
                "qm_keyst=${cred.musickey}; qqmusic_key=${cred.musickey}"

        val conn = (URL("https://u.y.qq.com/cgi-bin/musicu.fcg").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "QQMusic 14090008(android ${cred.osRelease})")
            setRequestProperty("Cookie", cookie)
            setFixedLengthStreamingMode(body.size)
        }
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val text = (if (status in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()

        val root = JSONObject(text)
        val req0 = root.optJSONObject("req_0") ?: JSONObject()
        val data = req0.optJSONObject("data") ?: JSONObject()

        val sipArr = data.optJSONArray("sip")
        val sip = buildList {
            if (sipArr != null) for (i in 0 until sipArr.length()) {
                sipArr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) }
            }
        }
        val mi = data.optJSONArray("midurlinfo")?.optJSONObject(0) ?: JSONObject()

        return VkeyResult(
            httpStatus = status,
            globalCode = root.optInt("code", -1),
            upstreamCode = req0.optInt("code", -1),
            sip = sip,
            purl = mi.optString("purl"),
            result = mi.optInt("result", -1),
            errtype = mi.optString("errtype"),
            filename = mi.optString("filename"),
            pneedbuy = mi.optInt("pneedbuy", -1),
            isbuy = mi.optInt("isbuy", -1),
            uiAlert = mi.optInt("uiAlert", -1),
        )
    }

    private class RangeProbe(val status: Int, val contentRange: String, val totalBytes: Long)

    /** GET + `Range: bytes=0-0`，从 Content-Range 的 `/total` 读真实总字节数。 */
    private fun probeRange(url: String): RangeProbe {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("User-Agent", "QQMusic 14090008(android 11)")
            }
            val status = conn.responseCode
            val cr = conn.getHeaderField("Content-Range") ?: "-"
            conn.inputStream?.use { it.readBytes() } // 读掉那 1 字节，避免连接泄漏
            conn.disconnect()
            val total = cr.substringAfterLast('/').trim().toLongOrNull() ?: -1L
            RangeProbe(status, cr, total)
        } catch (t: Throwable) {
            RangeProbe(-1, "ERR ${t.javaClass.simpleName}: ${t.message}", -1L)
        }
    }

    companion object {
        private const val LOG_TAG = "Phase0"
    }
}
