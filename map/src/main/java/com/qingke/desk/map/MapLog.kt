package com.qingke.desk.map

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * :map 的日志出口。与 :hosting 的 HostLog 同款做法，理由也一样：
 *
 * 这台设备（OnePlus PKR110 / Android 16）的 `adb logcat -d | grep <tag>` 恒为空，
 * 应用日志一条都捞不出来。所以落到文件里，取法**两条都留**：
 *
 *   adb exec-out run-as com.qingke.desk cat files/map/map.log     ← 内部目录（首选）
 *   adb pull /sdcard/Android/data/com.qingke.desk/files/map/map.log  ← 外部目录（可能被拒）
 *
 * 为什么首选内部目录（2026-10-12 踩）：`/sdcard/Android/data/com.qingke.desk` 的属主是
 * `u0_a242:ext_data_rw`、模式 `drwxrws---`，**shell 不在这个组里**，于是
 * `adb shell cat` 与 `adb pull` 全是 Permission denied（以前能 pull 的时候是设备策略不同）。
 * 内部目录用 `run-as` 读 —— debug 包（debuggable）才允许，正好与本日志"只在 debug 落盘"
 * 的前提一致。
 *
 * 只在 debug 构建落盘（[BuildConfig.DEBUG] 在 release 恒为 false，整段被剔除）。
 */
object MapLog {

    private const val TAG = "DeskMap"
    private const val DIR_NAME = "map"
    private const val FILE_NAME = "map.log"
    private const val MAX_BYTES = 512 * 1024

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    @Volatile
    private var logFile: File? = null

    /** 内部目录的镜像。shell 读不了 /sdcard 时靠它取证。 */
    @Volatile
    private var mirrorFile: File? = null

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        logFile = runCatching {
            File(File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }, FILE_NAME)
        }.onFailure {
            Log.w(TAG, "external log unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()

        mirrorFile = runCatching {
            File(File(context.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)
        }.onFailure {
            Log.w(TAG, "internal log unavailable: ${it.javaClass.simpleName}")
        }.getOrNull()
    }

    fun i(message: String) {
        Log.i(TAG, message)
        append("I", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        append("W", message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
        append("E", message + (throwable?.let { " | ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    private fun append(level: String, message: String) {
        if (!BuildConfig.DEBUG) return
        val line = "${timeFormat.format(Date())} $level $message\n"
        listOfNotNull(logFile, mirrorFile).forEach { file ->
            runCatching {
                if (file.exists() && file.length() > MAX_BYTES) file.writeText(line) else file.appendText(line)
            }
        }
    }
}
