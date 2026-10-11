package com.qingke.desk.map.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.qingke.desk.core.platform.Daylight
import com.qingke.desk.map.AMapStyle
import com.qingke.desk.map.MapLog
import com.qingke.desk.map.MapPane

/**
 * 地图探针页（**仅 debug 变体**，正式构建里不存在）。
 *
 * 存在的理由：首页怎么摆地图还没定，但"SDK 出不出图、手势能不能用、
 * 息屏回来还在不在"跟摆放无关，先单独验一遍，比把变量混在一起试要省事得多。
 *
 * 用法（Key 必须先写进 local.properties）：
 *   adb shell am start -n com.qingke.desk/com.qingke.desk.map.debug.MapPreviewActivity \
 *       --es style night --ez resetprivacy true
 */
class MapPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // null = 按日出日落自动（MapPane 的默认行为）。探针页要的是"钉死某一套"，
        // 所以这里默认 NORMAL，只有显式 --es style auto 才走自动那条路。
        val style = when (intent.getStringExtra(EXTRA_STYLE)) {
            "auto" -> null
            "night" -> AMapStyle.NIGHT
            "satellite" -> AMapStyle.SATELLITE
            else -> AMapStyle.NORMAL
        }

        if (intent.getBooleanExtra(EXTRA_RESET_PRIVACY, false)) {
            // 只清记录；进程内的合规状态会在下次初始化时按新值重报。
            com.qingke.desk.map.AMapPrivacy.revoke(this)
            MapLog.i("probe: privacy reset to declined")
        }

        MapLog.i("probe: start style=${style?.name ?: "auto (by sunrise/sunset)"}")

        // 外部注入日出日落 —— 提前把"以后接天气 API"那条路铺好，也方便当场调自动化：
        //   adb shell am start ... --es style auto --es sunrise 06:20 --es sunset 17:40
        // 把 sunset 设成"两分钟后"就能不等天黑看到自动翻转。
        val sunrise = intent.getStringExtra(EXTRA_SUNRISE)
        val sunset = intent.getStringExtra(EXTRA_SUNSET)
        if (sunrise != null && sunset != null) {
            Daylight.publish(sunriseClock = sunrise, sunsetClock = sunset)
            MapLog.i("probe: external daylight $sunrise / $sunset")
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0E0E12)),
                ) {
                    MapPane(
                        modifier = Modifier.fillMaxSize(),
                        style = style,
                        showMyLocation = true,
                    )
                }
            }
        }
    }

    private companion object {
        const val EXTRA_STYLE = "style"
        const val EXTRA_SUNRISE = "sunrise"
        const val EXTRA_SUNSET = "sunset"
        const val EXTRA_RESET_PRIVACY = "resetprivacy"
    }
}
