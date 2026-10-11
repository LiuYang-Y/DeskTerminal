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
 * 应用日志一条都捞不出来。所以同时落到应用外部私有目录，靠 `adb pull` 取：
 *
 *   adb pull /sdcard/Android/data/com.qingke.desk/files/map/map.log
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

    fun init(context: Context) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val dir = File(context.getExternalFilesDir(null), DIR_NAME).apply { mkdirs() }
            logFile = File(dir, FILE_NAME)
        }.onFailure {
            Log.w(TAG, "log file unavailable: ${it.javaClass.simpleName}")
            logFile = null
        }
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
        val file = logFile ?: return
        runCatching {
            val line = "${timeFormat.format(Date())} $level $message\n"
            if (file.exists() && file.length() > MAX_BYTES) file.writeText(line) else file.appendText(line)
        }
    }
}
