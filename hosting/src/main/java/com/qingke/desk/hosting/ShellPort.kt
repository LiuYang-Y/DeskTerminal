package com.qingke.desk.hosting

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku

/** 一条命令的结果。 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val isSuccess: Boolean get() = exitCode == 0

    /** 给人看的一行摘要，写日志用。 */
    fun summary(maxLength: Int = 400): String {
        val text = buildString {
            append("exit=").append(exitCode)
            if (stdout.isNotBlank()) append(" | out=").append(stdout.trim())
            if (stderr.isNotBlank()) append(" | err=").append(stderr.trim())
        }
        return if (text.length <= maxLength) text else text.take(maxLength) + "…"
    }
}

/**
 * 到 [HostShellService]（Shizuku 用户服务）的客户端通道。
 *
 * 绑定是异步的，而调用方（触摸注入、启动应用）都在协程里，
 * 所以这里把"绑定"包成一次可挂起的等待：第一次 exec 时按需绑定，
 * 之后复用同一个 binder，直到 [close]。
 *
 * `daemon = false`：本应用进程没了，用户服务也一起没，不留残留 shell 进程。
 */
class ShellPort(
    private val context: Context,
) {
    private val component = ComponentName(context.packageName, HostShellService::class.java.name)

    private val args: Shizuku.UserServiceArgs = Shizuku.UserServiceArgs(component)
        .daemon(false)
        // 进程名后缀，方便 `ps` 里一眼认出来是哪个服务
        .processNameSuffix("host-shell")
        .version(1)

    @Volatile
    private var shell: IHostShell? = null

    private var connection: ServiceConnection? = null

    /**
     * 执行一条 shell 命令。**必须在非主线程调用**（内部会切到 IO）。
     *
     * 绑定失败、超时、服务端抛异常都不会抛出到调用方 —— 一律转成
     * exitCode != 0 的 [ShellResult]，由调用方决定要不要重试。
     */
    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        val target = try {
            ensureBound()
        } catch (t: Throwable) {
            HostLog.e("bind host shell failed", t)
            return@withContext ShellResult(-1, "", "bind failed: ${t.message}")
        }

        val raw = try {
            target.exec(command)
        } catch (t: Throwable) {
            HostLog.e("exec failed: $command", t)
            return@withContext ShellResult(-1, "", "exec failed: ${t.javaClass.simpleName}: ${t.message}")
        }

        if (raw.size < 3) {
            return@withContext ShellResult(-1, "", "malformed result: ${raw.contentToString()}")
        }
        ShellResult(
            exitCode = raw[0].toIntOrNull() ?: -1,
            stdout = raw[1],
            stderr = raw[2],
        )
    }

    private suspend fun ensureBound(): IHostShell {
        shell?.let { cached ->
            if (cached.asBinder().isBinderAlive && cached.asBinder().pingBinder()) return cached
        }

        // ServiceConnection 的回调要在有 Looper 的线程上投递，所以绑定动作放主线程做，
        // 结果用 Deferred 接回来，调用方仍停在 IO 上等。
        return withContext(Dispatchers.Main.immediate) {
            val deferred = CompletableDeferred<IHostShell>()
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    val bound = runCatching { IHostShell.Stub.asInterface(service) }.getOrNull()
                    if (bound != null) {
                        shell = bound
                        HostLog.i("host shell connected")
                        deferred.complete(bound)
                    } else {
                        HostLog.e("host shell binder unusable")
                        deferred.completeExceptionally(IllegalStateException("binder unusable"))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    HostLog.w("host shell disconnected")
                    shell = null
                }
            }
            connection = conn
            Shizuku.bindUserService(args, conn)
            withTimeout(BIND_TIMEOUT_MS) { deferred.await() }
        }
    }

    /** 断开并让远程进程退出。 */
    fun close() {
        val conn = connection ?: return
        connection = null
        shell = null
        runCatching { Shizuku.unbindUserService(args, conn, true) }
            .onFailure { HostLog.w("unbind host shell: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
    }
}
