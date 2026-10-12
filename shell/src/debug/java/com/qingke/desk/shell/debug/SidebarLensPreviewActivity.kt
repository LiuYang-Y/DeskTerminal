package com.qingke.desk.shell.debug

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.data.SidebarSide
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.DeskTheme
import com.qingke.desk.core.ui.deskGlassSource
import com.qingke.desk.core.ui.rememberDeskGlassBackdrop
import com.qingke.desk.shell.ShellIds
import com.qingke.desk.shell.defaultShellItems

/**
 * 玻璃透镜侧栏的**原型预览页**（仅 debug 变体，正式构建里不存在）。
 *
 * 用法：
 * ```
 * adb shell am start -n com.qingke.desk/com.qingke.desk.shell.debug.SidebarLensPreviewActivity
 * ```
 *
 * 这一页回答四件事，每一件都能被截图证伪：
 *   1. 镜片是否真的"比单个图标略大"、镜内图标是否被放大且染色、镜外是否保持原色；
 *   2. 按下/拖动时镜片是否再放大，抬手是否回弹并吸附到最近一项；
 *   3. **左右镜像**是否只差一个横向偏移（两侧的纵向轨迹应当完全一致）；
 *   4. 关掉透镜开关后，是否回到改动前的样子（64dp 玻璃指示块 + 选中项在列内染强调色）。
 *
 * 页面里同时有"能力探测"读数：本机 API 30，`lens()` 与 `blur()` 都会被库静默跳过，
 * 所以这一页**看不到真折射** —— 能看到的放大/染色/描边都是原型自己画的，
 * 与 API 等级无关。真折射要在 API 33+ 的机器上验。
 */
class SidebarLensPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DeskTheme(useDarkTheme = true) {
                LensPreviewScreen()
            }
        }
    }
}

@Composable
private fun LensPreviewScreen() {
    val items = remember { defaultShellItems() }
    var selectedId by remember { mutableStateOf(ShellIds.HOME) }
    var lensEnabled by remember { mutableStateOf(LensSpec.ENABLED) }

    // 玻璃的"背景是什么"。内容区录进这块层，两个侧栏都只读它。
    val backdrop = rememberDeskGlassBackdrop(background = Color(0xFF0E1117))

    Box(modifier = Modifier.fillMaxSize()) {
        // 被录制的那一层：预览背景。**两个侧栏都不在它里面** —— 成了自采样就会崩。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .deskGlassSource(backdrop),
        ) {
            PreviewBackdrop()
        }

        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .width(DeskDimens.sideBarWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                LensSideBar(
                    items = items,
                    selectedId = selectedId,
                    onSelect = { selectedId = it.id },
                    side = SidebarSide.LEFT,
                    backdrop = backdrop,
                    lensEnabled = lensEnabled,
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(24.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "侧栏玻璃透镜 · 原型",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    CapabilityRow()
                    Text(
                        text = "选中：${items.firstOrNull { it.id == selectedId }?.label ?: "-"}",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(onClick = { lensEnabled = !lensEnabled }) {
                        Text(if (lensEnabled) "透镜：开（点一下关掉看回归）" else "透镜：关（点一下开启）")
                    }
                    Text(
                        text = "点侧栏图标可切换选中；按住并上下拖动试跟手与回弹。" +
                            "两侧侧栏互为镜像，纵向行为应完全一致。",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .width(DeskDimens.sideBarWidth)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center,
            ) {
                LensSideBar(
                    items = items,
                    selectedId = selectedId,
                    onSelect = { selectedId = it.id },
                    side = SidebarSide.RIGHT,
                    backdrop = backdrop,
                    lensEnabled = lensEnabled,
                )
            }
        }
    }
}

/** 能力探测读数。直接照库里的判定抄，不自己发明门槛。 */
@Composable
private fun CapabilityRow() {
    val runtimeShader = Build.VERSION.SDK_INT >= 33
    val renderEffect = Build.VERSION.SDK_INT >= 31
    val tier = remember { resolveLensTier() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CapabilityLine(
            name = "RuntimeShader（真折射 + 色散）",
            available = runtimeShader,
            need = "需 API 33",
        )
        CapabilityLine(
            name = "RenderEffect（真模糊）",
            available = renderEffect,
            need = "需 API 31",
        )
        Text(
            text = "当前档位：${tier.label} —— ${tier.detail}",
            fontSize = 12.sp,
            color = Color(0xFF9FB4D8),
        )
        Text(
            text = if (runtimeShader) {
                "本机走最高档：镜片里能看到背景被弯折、边缘有色散。"
            } else {
                "本机降级：lens()/blur() 会被库直接跳过（静默 return）。" +
                    "镜片的放大、染色、彩色描边都来自原型自绘，与 API 等级无关。"
            },
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun CapabilityLine(name: String, available: Boolean, need: String) {
    Text(
        text = "${if (available) "可用" else "不可用"} · $name（$need）",
        fontSize = 12.sp,
        color = if (available) Color(0xFF6BE39B) else Color(0xFFFF9E7A),
    )
}

/**
 * 预览背景。刻意做得有明暗和竖向分界：
 * 玻璃边缘的折射/蒙版一旦生效，这些分界会弯折或变淡 —— 效果好不好看它更清楚。
 */
@Composable
private fun PreviewBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF1B2430),
                        Color(0xFF2B3A4A),
                        Color(0xFF141A22),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .background(
                            Color.White.copy(alpha = 0.04f + i * 0.03f),
                        ),
                )
            }
        }
    }
}
