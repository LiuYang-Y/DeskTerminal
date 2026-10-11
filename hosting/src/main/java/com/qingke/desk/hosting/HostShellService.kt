package com.qingke.desk.hosting

import java.util.concurrent.TimeUnit

/**
 * Shizuku 用户服务：**它跑在 uid 2000（shell）下，不在本应用进程里**。
 *
 * 为什么必须多绕这一层，而不是在应用进程里直接 Runtime.exec：
 * 把第三方 App 拉到虚拟屏上（`am start --display`）、往指定屏注入触摸（`input -d`）
 * 都需要 CONTROL_DISPLAY / INTERNAL_SYSTEM_WINDOW / INJECT_EVENTS，
 * 这三张牌只有 shell 有。Q1 探针在真机上验证过：应用进程自己
 * `ActivityOptions.setLaunchDisplayId()` 会被系统直接拒绝（Permission Denial）。
 *
 * 注意这里**没有 Context、没有 Application**：Shizuku 是用 app_process 直接
 * 加载 APK 里的这个类并 newInstance 的，不是正常的应用进程。所以只能做纯 Java 的事。
 *
 * 命令一律走 `/system/bin/sh -c`，并且**写全绝对路径**（/system/bin/am、/system/bin/input）：
 * 这个进程的环境里 PATH 未必存在，靠 PATH 找命令会在某些 ROM 上莫名失败。
 */
class HostShellService : IHostShell.Stub() {

    override fun exec(command: String): Array<String> {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf(SH, "-c", command),
                arrayOf(
                    "PATH=$PATH_VALUE",
                    "ANDROID_DATA=/data",
                    "ANDROID_ROOT=/system",
                    "HOME=/data/local/tmp",
                ),
            )
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val outThread = Thread(
                Runnable {
                    runCatching { process.inputStream.bufferedReader().use { stdout.append(it.readText()) } }
                },
                "host-shell-stdout",
            )
            val errThread = Thread(
                Runnable {
                    runCatching { process.errorStream.bufferedReader().use { stderr.append(it.readText()) } }
                },
                "host-shell-stderr",
            )
            outThread.start()
            errThread.start()

            // 先等命令退出，再 join 读线程：反过来的话 stdout/stderr 的管道缓冲
            // 可能被写满而阻塞子进程，waitFor 就永远等不到。
            val exited = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                stderr.append("\n[hosting] command timed out after ${TIMEOUT_SECONDS}s")
            }
            outThread.join(1000L)
            errThread.join(1000L)

            val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
            arrayOf(exitCode.toString(), stdout.toString(), stderr.toString())
        } catch (t: Throwable) {
            arrayOf("-1", "", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    override fun destroy() {
        // 解绑时 Shizuku 调它：这里必须真的退出，否则 shell 进程会残留。
        System.exit(0)
    }

    override fun exit() {
        System.exit(0)
    }

    private companion object {
        const val SH = "/system/bin/sh"
        const val PATH_VALUE = "/system/bin:/system/xbin:/vendor/bin:/apex/com.android.runtime/bin"
        const val TIMEOUT_SECONDS = 20L
    }
}
