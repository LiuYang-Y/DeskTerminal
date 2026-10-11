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

        val style = when (intent.getStringExtra(EXTRA_STYLE)) {
            "night" -> AMapStyle.NIGHT
            "satellite" -> AMapStyle.SATELLITE
            else -> AMapStyle.NORMAL
        }

        if (intent.getBooleanExtra(EXTRA_RESET_PRIVACY, false)) {
            // 只清记录；进程内的合规状态会在下次初始化时按新值重报。
            com.qingke.desk.map.AMapPrivacy.revoke(this)
            MapLog.i("probe: privacy reset to declined")
        }

        MapLog.i("probe: start style=${style.name}")

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
        const val EXTRA_RESET_PRIVACY = "resetprivacy"
    }
}
