package com.qingke.desk.core.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 桌面终端的中性底色。蓝为主、紫作点缀，卡片本身用彩色区分。
private val DeskLight = lightColorScheme(
    primary = Color(0xFF3D5AFE),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE4E9FF),
    onPrimaryContainer = Color(0xFF1B2A6B),
    secondary = Color(0xFF7C5CFF),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F6FA),
    onBackground = Color(0xFF1A1C23),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C23),
    surfaceVariant = Color(0xFFEDEFF5),
    onSurfaceVariant = Color(0xFF5B6070),
    outline = Color(0xFFD3D7E3),
)

private val DeskDark = darkColorScheme(
    primary = Color(0xFF8FA0FF),
    onPrimary = Color(0xFF101A45),
    primaryContainer = Color(0xFF26315E),
    onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFB9A6FF),
    onSecondary = Color(0xFF221A4D),
    background = Color(0xFF101217),
    onBackground = Color(0xFFE6E8EF),
    surface = Color(0xFF171A21),
    onSurface = Color(0xFFE6E8EF),
    surfaceVariant = Color(0xFF232733),
    onSurfaceVariant = Color(0xFFA9B0C2),
    outline = Color(0xFF333846),
)

/**
 * 桌面终端主题。
 *
 * 默认暗色，不跟随系统 —— 基准机是 120Hz AMOLED 且 24 小时常亮摆放，
 * 白底在夜间既刺眼、也加速有机发光层老化。两套色板都保留，
 * 想改回浅色（蓝白）只要传 [useDarkTheme] = false。
 *
 * TODO(B7)：后续可按时段自动切换（白天浅色 / 夜间暗色），与分时段亮度共用同一套时间判定。
 */
@Composable
fun DeskTheme(
    useDarkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (useDarkTheme) DeskDark else DeskLight,
        content = content,
    )
}
