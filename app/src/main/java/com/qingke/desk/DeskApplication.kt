package com.qingke.desk

import android.app.Application
import android.content.pm.ApplicationInfo
import com.qingke.desk.feature.media.music.online.QqLog
import com.qingke.desk.map.AMapBootstrap
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口。目前只持有 DI 图，不在这里做初始化副作用
 * （网络锁、前台服务等都要等连接真正建立时再拉起）。
 *
 * 例外是 [AMapBootstrap]：它必须在这里跑，而且**只做两件不碰渲染的事** ——
 * 打开 debug 的文件日志、把用户上次的隐私同意补报给高德 SDK。
 * 地图引擎本身仍然要等用户同意之后、由 MapPane 挂 MapView 时才初始化，
 * 提前到 Application 里做就等于绕过了隐私闸门。
 */
class DeskApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 调试构建：把未捕获异常落盘，绕开 ColorOS 对第三方 app logcat 的整体屏蔽
        // （一加/小米无 root 时 logcat 抓不到 Java 崩溃栈，run-as 拉 files/crash.txt 即可）。
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) installCrashToFile()
        // 文件日志 sink 必须**无条件、尽早**就绪：本机 logcat 对本 App 恒为空，所有诊断都靠
        // files/qq-login.log。此前 init 只挂在 QqLoginController 构造器上，任何不经登录页构造
        // 路径却调 QqLog 的代码（如歌词模块）都静默丢日志。放在这里后 sink 与是否登录/是否
        // 打开音乐页解耦。QqLog.init 幂等（sink==null 才写），QqLoginController 那处调用保留作双保险。
        QqLog.init(this)
        // bind() 内部会碰到 DeskGraph 对象，顺带完成图的初始化 —— 不需要再单独 touch 一次。
        DeskGraph.bind(this)
        AMapBootstrap.init(this)
    }

    private fun installCrashToFile() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val sw = StringWriter()
                sw.appendLine("Crash at $stamp on thread ${thread.name}")
                throwable.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").appendText(sw.toString())
            }
            default?.uncaughtException(thread, throwable)
        }
    }
}
