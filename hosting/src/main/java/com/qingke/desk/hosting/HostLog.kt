package com.qingke.desk.hosting

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * :hosting 的日志出口。
 *
 * **为什么除了 Logcat 还要写文件**：这台设备（OnePlus PKR110 / Android 16）的
 * `adb logcat -d | grep <tag>` 恒为空，应用日志一条都捞不出来（实验阶段反复确认过）。
 * 所以把日志同时落到应用的外部私有目录，靠 `adb pull` 取。
 *
 * **为什么只在 debug 构建落盘**：写文件是调试手段，不是功能。
 * [BuildConfig.DEBUG] 在 release 里恒为 false，整段分支会被编译期剔除，
 * 正式包不会在用户的存储里留下任何日志。
 *
 * 取日志：
 *   adb pull /sdcard/Android/data/com.qingke.desk/files/hosting/hosting.log
 */
object HostLog {

    private const val TAG = "DeskHosting"
    private const val DIR_NAME = "hosting"
    private const val FILE_NAME = "hosting.log"

    /** 单文件上限，超过就从头覆写。真机实验一轮几十 KB 足够，不搞滚动归档。 */
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

    fun d(message: String) {
        Log.d(TAG, message)
        append("D", message)
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
            if (file.exists() && file.length() > MAX_BYTES) {
                file.writeText(line)
            } else {
                file.appendText(line)
            }
        }
    }
}
