package com.qingke.desk.core.platform

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Wi-Fi 开关与连接状态。
 *
 * [enabled] 是"无线射频是否打开"，[connected] 是"是否已经连上某个网络"，
 * 两者必须分开：阶段一的接收端走 Wi-Fi Direct，**只要求射频打开，不要求先连路由器**。
 * 只看 connected 会在"开了 Wi-Fi 但没连 AP"的情况下误判成不可用。
 *
 * [undetermined] 表示这一轮探测没能问出确定答案（被厂商限制读取），
 * 此时 [enabled] 会刻意置 true —— 探测失败不应该挡住用户，宁可放行。
 */
data class WifiStatus(
    val enabled: Boolean,
    val connected: Boolean,
    val ssid: String? = null,
    val undetermined: Boolean = false,
)

/**
 * Wi-Fi 状态读取。
 *
 * 三条读取路径按优先级退化，任一环节被厂商限制就退到下一条：
 *   1. WifiManager.isWifiEnabled —— 直接、准确，少数 ROM 会抛 SecurityException；
 *   2. Settings.Global.WIFI_ON   —— 全局设置，读不出来时返回 0 兜底；
 *   3. 都失败 -> undetermined=true，enabled 置 true（**失败不拦人**）。
 *
 * 为什么不用 ConnectivityManager 判断"是否可用"：它只能告诉我 Wi-Fi 网络是否已连接，
 * 射频关着时它一律 false，和"没连上 AP"长得一模一样，无法区分。
 */
object WifiGate {

    private const val TAG = "DeskWifi"

    fun query(context: Context): WifiStatus {
        val app = context.applicationContext
        val enabled = readEnabled(app)
        val connection = readConnection(app)
        Log.i(TAG, "enabled=${enabled?.first} connected=${connection.first} via=${enabled?.second}")

        if (enabled == null) {
            return WifiStatus(enabled = true, connected = connection.first, ssid = connection.second, undetermined = true)
        }
        return WifiStatus(
            enabled = enabled.first,
            connected = connection.first,
            ssid = connection.second,
        )
    }

    /** null 表示三种路径都没问出来。 */
    private fun readEnabled(context: Context): Pair<Boolean, String>? {
        runCatching {
            val manager = context.getSystemService(WifiManager::class.java)
            if (manager != null) {
                @Suppress("DEPRECATION")
                return manager.isWifiEnabled to "WifiManager"
            }
        }.onFailure { throwable ->
            Log.w(TAG, "WifiManager blocked: ${throwable.javaClass.simpleName}")
        }

        runCatching {
            val value = Settings.Global.getInt(context.contentResolver, Settings.Global.WIFI_ON)
            // 括号不能省：infix 的 to 优先级高于 !=，不写括号会变成 value != (0 to "...")
            return (value != 0) to "Settings.Global"
        }.onFailure { throwable ->
            Log.w(TAG, "Settings.Global blocked: ${throwable.javaClass.simpleName}")
        }

        return null
    }

    private fun readConnection(context: Context): Pair<Boolean, String?> {
        val manager = runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
            ?: return false to null
        val network = runCatching { manager.activeNetwork }.getOrNull() ?: return false to null
        val caps = runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
            ?: return false to null
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        return wifi to if (wifi) readSsid(context) else null
    }

    /**
     * SSID 有两个坑，都得防：
     *   1. Android 16 起 NetworkCapabilities 上的 ssid/wifiSsid 已移除，只能从 WifiInfo 读；
     *   2. 没授予定位权限时系统给的占位串是 `<unknown ssid>`，不是真名字。
     * 拿不到就返回 null —— 它只用于提示页上的一行装饰文字，不参与任何判断。
     */
    private fun readSsid(context: Context): String? {
        val manager = runCatching { context.getSystemService(WifiManager::class.java) }.getOrNull()
            ?: return null
        val info = runCatching { manager.connectionInfo }.getOrNull() ?: return null
        val raw = runCatching { info.ssid }.getOrNull()?.trim('"')
        return raw?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
    }
}

/**
 * 提示页上"去开启 / 去启用"这类跳转用的 Intent。
 *
 * 只负责**构造**，不负责 start —— 是否真的跳出去、失败了怎么办，属于界面层的决定。
 * 每种补救都给两条路径：[Panel] 是系统提供的浮层面板（API 29+，不用离开本应用），
 * [旧式设置页] 是兼容性兜底。返回的列表按"优先尝试"排序，调用方碰到
 * ActivityNotFoundException 就试下一个。
 */
object RemedyIntents {

    fun wifiSettings(): List<Intent> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Intent(Settings.Panel.ACTION_WIFI))
        }
        add(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun appDetails(packageName: String): List<Intent> = listOf(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(android.net.Uri.parse("package:$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
