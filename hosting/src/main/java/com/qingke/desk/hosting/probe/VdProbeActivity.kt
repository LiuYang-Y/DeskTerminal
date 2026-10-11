package com.qingke.desk.hosting.probe

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * 「普通应用能不能创建出承载第三方 App 窗口的虚拟屏」—— 真机实验探针。
 *
 * 这是 :hosting 立项前必须先回答的问题，不能只引文档：
 * 文档只说 createVirtualDisplay 本身"不需要权限"，但没说清楚**别人家的窗口能不能放上来**。
 * 这两件事在 Android 上是分开判定的（建屏 / 往屏上放别人的 Activity），
 * 只有真跑一遍才知道哪一步被拒、被拒时抛的是什么。
 *
 * 实验分两步：
 *   A. 在本进程（普通应用 uid）里用不同 flags 组合建屏，记录成功 / 被拒；
 *   B. 对每个建出来的 displayId，分别试
 *      ① 应用自身 startActivity + setLaunchDisplayId（应用侧跨屏拉起）
 *      ② adb 的 am start --display（shell 侧跨屏拉起，Shizuku 走的就是这条路）
 *      看第三方 App 到底落到哪个屏上。
 *
 * 结果同时写 logcat（tag = DeskVDProbe）和外部存储，方便 adb 直接取。
 * 实验结束（本文件）即删除，不进正式代码。
 */
class VdProbeActivity : Activity() {

    private val displays = LinkedHashMap<String, VirtualDisplay>()
    private var reader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = getSystemService(DisplayManager::class.java)
        val ir = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3)
        reader = ir
        val surface: Surface = ir.surface

        say("=== VD PROBE start: uid=${android.os.Process.myUid()} ${WIDTH}x${HEIGHT}@$DPI ===")

        for ((case, flags) in CASES) {
            val result = runCatching {
                val vd = dm.createVirtualDisplay("probe-$case", WIDTH, HEIGHT, DPI, surface, flags)
                displays[case] = vd
                "OK displayId=${vd.display.displayId}"
            }.getOrElse { t ->
                "${t.javaClass.simpleName}: ${t.message}"
            }
            say("CREATE  $case  flags=$flags(${flags.toString(2)})  -> $result")
        }

        // 应用侧跨屏拉起第三方 App。不会真的指望它成功 ——
        // 目的是拿到"被拒时抛什么"，用来判定这是权限问题还是别的限制。
        for ((case, vd) in displays) {
            val id = vd.display.displayId
            val result = runCatching {
                val intent = Intent()
                    .setClassName(TARGET_PKG, TARGET_ACT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(id)
                startActivity(intent, options.toBundle())
                "startActivity returned"
            }.getOrElse { t ->
                "${t.javaClass.simpleName}: ${t.message}"
            }
            say("APPLAUNCH $case displayId=$id -> $result")
        }

        say("=== PROBE_READY: ${displays.map { "${it.key}=${it.value.display.displayId}" }} ===")
        flush()

        // 每 5 秒复述一次当前 displayId 与"屏是否还活着"。
        // 第一次跑就吃过亏：Activity 被重建导致 VirtualDisplay 全部换了一批 id，
        // 而外部按旧 id 发 am start --display 时，语义已经不是原来那块屏了。
        val tick = object : Runnable {
            override fun run() {
                val alive = displays.mapValues { (_, vd) -> runCatching { vd.display.displayId }.getOrDefault(-1) }
                Log.i(TAG, "TICK ${alive.entries.joinToString { "${it.key}=${it.value}" }}")
                handler.postDelayed(this, 5_000L)
            }
        }
        handler.postDelayed(tick, 5_000L)

        // 给外部留 10 分钟窗口跑 am start --display 与截图对照。
        handler.postDelayed({ finish() }, 600_000L)
    }

    override fun onDestroy() {
        displays.values.forEach { runCatching { it.release() } }
        displays.clear()
        runCatching { reader?.close() }
        super.onDestroy()
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        lines.add(line)
        if (lines.size % 4 == 0) flush()
    }

    private fun flush() {
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            File(dir, "vdprobe.txt").writeText(lines.joinToString("\n"))
        }
    }

    companion object {
        private const val TAG = "DeskVDProbe"
        private const val WIDTH = 1920
        private const val HEIGHT = 1080
        private const val DPI = 320
        private const val TARGET_PKG = "com.autonavi.minimap"
        private const val TARGET_ACT = "com.autonavi.map.activity.SplashActivity"

        // DisplayManager.VIRTUAL_DISPLAY_FLAG_* 的字面量。
        // PUBLIC 是 API 34 公开的（1<<3）；TRUSTED（1<<4）至今是隐藏常量，
        // 公开 SDK 里没有名字，只能写字面量 —— 这也正是它"普通应用拿不到"的表象之一。
        private const val FLAG_AUTO_MIRROR = 1 shl 0
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 1
        private const val FLAG_PRESENTATION = 1 shl 2
        private const val FLAG_PUBLIC = 1 shl 3
        private const val FLAG_TRUSTED = 1 shl 4

        private val CASES = listOf(
            "flags0" to 0,
            "autoMirror" to FLAG_AUTO_MIRROR,
            "ownContentOnly" to FLAG_OWN_CONTENT_ONLY,
            "presentation" to FLAG_PRESENTATION,
            "public" to FLAG_PUBLIC,
            "trusted" to FLAG_TRUSTED,
            "public|trusted" to (FLAG_PUBLIC or FLAG_TRUSTED),
            "public|presentation" to (FLAG_PUBLIC or FLAG_PRESENTATION),
        )
    }
}
