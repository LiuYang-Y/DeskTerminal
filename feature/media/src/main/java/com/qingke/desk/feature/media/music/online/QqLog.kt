package com.qingke.desk.feature.media.music.online

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 登录链路的文件日志。
 *
 * ---------------------------------------------------------------------------
 * 为什么不用 `android.util.Log` 就走
 *
 * 在役的小米 11 Ultra 上 **logcat 对本应用的输出恒为空**（Phase 0 已实测，
 * 探针当时就是因此改走文件日志）。登录是一条跨"网络 → MQTT → Compose 状态"的长链路，
 * 没有日志时每一步都只能靠截图反推 —— 那是在用眼睛调试一个异步状态机。
 *
 * 接口**故意与 `android.util.Log` 同形**（`i(tag, msg)` / `w(tag, msg)`）：
 * 各文件只需要把 `import android.util.Log` 换成 `import …QqLog`，调用点一行不动。
 *
 * ---------------------------------------------------------------------------
 * 红线
 *
 * 这里**只允许**出现：阶段名、上游返回码、字段长度、掩码后的凭证（[QqCredential.mask]）。
 * `musickey` / `refresh_key` / 完整 Cookie 的值**一个字符都不许进来** ——
 * 这个文件在 `filesDir` 下不假，但它是排查时第一个被 `adb pull` 走的东西。
 */
object QqLog {

    @Volatile private var sink: File? = null

    /** 在拿到 [Context] 的最早时刻调用一次（`QqLoginController` 的构造器里）。 */
    fun init(context: Context) {
        if (sink == null) sink = File(context.filesDir, "qq-login.log")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg)

    fun w(tag: String, msg: String) = write("W", tag, msg)

    private fun write(level: String, tag: String, msg: String) {
        // logcat 同时保留：换了能看 logcat 的设备（一加）它还能用。
        Log.i(tag, msg)
        val file = sink ?: return
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(this) {
            runCatching { file.appendText("$stamp $level/$tag: $msg\n") }
        }
    }
}
