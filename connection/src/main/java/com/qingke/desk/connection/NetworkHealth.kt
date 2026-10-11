package com.qingke.desk.connection

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

enum class WifiBand {
    BAND_2_4GHZ,
    BAND_5GHZ,
    BAND_6GHZ,
    UNKNOWN,
}

/** 网络诊断页的数据源。 */
data class NetworkHealth(
    val ssid: String? = null,
    val band: WifiBand = WifiBand.UNKNOWN,
    val frequencyMhz: Int? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val hasInternet: Boolean = false,
    val permissionReady: Boolean = false,
    val updatedAtMs: Long = 0L,
) {

    /** 信号强度的分档描述，避免 UI 里散落 if-else。 */
    val signalLevel: SignalLevel
        get() = when {
            rssiDbm == null -> SignalLevel.UNKNOWN
            rssiDbm >= -55 -> SignalLevel.EXCELLENT
            rssiDbm >= -67 -> SignalLevel.GOOD
            rssiDbm >= -78 -> SignalLevel.FAIR
            else -> SignalLevel.WEAK
        }
}

enum class SignalLevel { EXCELLENT, GOOD, FAIR, WEAK, UNKNOWN }

interface NetworkHealthProvider {
    fun snapshot(): NetworkHealth
    fun observe(intervalMs: Long = 2_000L): Flow<NetworkHealth>
}

/**
 * 只读采集，不申请权限。权限缺失时 permissionReady=false，
 * 由 UI 提示用户去设置里开，避免静默失败。
 */
class AndroidNetworkHealthProvider(
    context: Context,
) : NetworkHealthProvider {

    private val appContext = context.applicationContext

    override fun snapshot(): NetworkHealth {
        val ready = hasWifiStatePermission()
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val info = if (ready) wifiManager?.connectionInfo else null
        val frequency = info?.frequency?.takeIf { it > 0 }
        val rssi = info?.rssi?.takeIf { it != Int.MAX_VALUE && it != -127 }
        val linkSpeed = info?.linkSpeed?.takeIf { it >= 0 }
        val rawSsid = info?.ssid?.trim('"')

        return NetworkHealth(
            ssid = rawSsid?.takeIf { it.isNotBlank() && it != UNKNOWN_SSID },
            band = frequency?.let(::bandOf) ?: WifiBand.UNKNOWN,
            frequencyMhz = frequency,
            rssiDbm = rssi,
            linkSpeedMbps = linkSpeed,
            hasInternet = hasValidatedInternet(),
            permissionReady = ready,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    override fun observe(intervalMs: Long): Flow<NetworkHealth> = flow {
        while (true) {
            emit(snapshot())
            delay(intervalMs)
        }
    }

    private fun hasWifiStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_WIFI_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasValidatedInternet(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val UNKNOWN_SSID = "<unknown ssid>"

        private fun bandOf(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
            in 2400..2500 -> WifiBand.BAND_2_4GHZ
            in 4900..5900 -> WifiBand.BAND_5GHZ
            in 5925..7125 -> WifiBand.BAND_6GHZ
            else -> WifiBand.UNKNOWN
        }
    }
}

/**
 * Wi-Fi 常连。投屏与天气都依赖长连接，锁由 :connection 在会话期间持有。
 *
 * TODO(adb) [待校正]：部分厂商 ROM 会忽略 WifiLock，
 *           需在真机上确认锁是否真的阻止了休眠断网。
 */
class WifiKeepAlive(context: Context) {

    private val appContext = context.applicationContext
    private var lock: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")
    fun acquire() {
        if (lock?.isHeld == true) return
        val manager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        // 低延迟优先；API 29 以下只能退到 FULL_HIGH_PERF。
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        lock = manager.createWifiLock(mode, LOCK_TAG).also { it.acquire() }
    }

    fun release() {
        runCatching {
            if (lock?.isHeld == true) lock?.release()
        }
        lock = null
    }

    private companion object {
        const val LOCK_TAG = "desk:projection"
    }
}
