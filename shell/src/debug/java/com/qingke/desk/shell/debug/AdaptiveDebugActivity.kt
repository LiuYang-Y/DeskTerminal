package com.qingke.desk.shell.debug

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.qingke.desk.core.data.ShellSettings
import com.qingke.desk.core.platform.DeviceProfile
import com.qingke.desk.core.platform.EffectGovernor
import com.qingke.desk.core.platform.EffectLevel
import com.qingke.desk.core.platform.EffectTier
import com.qingke.desk.core.ui.AdaptiveDimens
import com.qingke.desk.core.ui.DeskTheme
import com.qingke.desk.core.ui.LocalDeviceProfile
import com.qingke.desk.core.ui.LocalEffectTier
import com.qingke.desk.core.ui.fitSidebar
import com.qingke.desk.shell.LensSideBar
import com.qingke.desk.shell.LensSpec
import com.qingke.desk.shell.ShellIds
import com.qingke.desk.shell.defaultShellItems
import kotlin.math.min

/**
 * 自适应框架的调试页（**仅 debug 变体**，正式构建里不存在）。
 *
 * 用法：
 * ```
 * adb shell am start -n com.qingke.desk/com.qingke.desk.shell.debug.AdaptiveDebugActivity
 * ```
 *
 * ---------------------------------------------------------------------------
 * 它回答四件事，每一件都能被截图证伪
 *
 *   1. **这台机器到底被识别成什么** —— API、可用宽高 dp、刷新率、挖孔、WebView、内存。
 *      换机后第一件事就是打开这一页核对，而不是去猜"它大概是 361dp 吧"。
 *   2. **效果档位为什么是这个** —— 显示"设备最高档 / 已降几档 / 当前档"，
 *      以及掉帧率、热状态、省电模式。档位不对时能一眼看出是哪一路信号压下来的。
 *   3. **强制指定档位** —— 五档 + 取消强制，用于"同一块界面在各档下各截一张"。
 *   4. **强制指定尺寸** —— 不改系统设置（`wm size` 要 adb、要记得还原），
 *      而是在**应用内**把内容塞进一个指定 dp 的盒子，并把 [LocalDeviceProfile]
 *      换成模拟档案，于是所有 `AdaptiveDimens.*` 都按模拟尺寸算。
 *      用来快速看"更窄 / 更矮的机器上会不会挤坏"。
 *
 * ---------------------------------------------------------------------------
 * 为什么"模拟尺寸"要连 DeviceProfile 一起换
 *
 * 只把内容塞进小盒子是不够的：盒子里的组件会读 [LocalDeviceProfile] 去反推尺寸，
 * 如果档案还是真机的，它们会按**真机**的宽高算，塞进小盒子就必然溢出 ——
 * 那测出来的是"盒子太小"，不是"这台机器太小"。所以两者必须一起换。
 *
 * 模拟盒子的缩放只是为了**看得全**：模拟尺寸比屏幕大时整体等比缩小，
 * 保证边框与内容都在屏幕内，截图才有用。
 */
class AdaptiveDebugActivity : ComponentActivity() {

    private var profile by mutableStateOf(DeviceProfile.FALLBACK)
    private lateinit var governor: EffectGovernor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterDeskImmersiveMode()

        val initial = DeviceProfile.read(this)
        profile = initial
        governor = EffectGovernor(this, initial, lifecycleScope)
        governor.setLevel(ShellSettings.effectLevel(this, initial))
        governor.setOverride(ShellSettings.forcedEffectTier(this))

        // 挖孔要等 View attach 之后才读得到（API 28/29 只有 rootWindowInsets 这条路）。
        window.decorView.post {
            if (!isDestroyed) {
                val next = DeviceProfile.read(this, window.decorView)
                profile = next
                governor.updateProfile(next)
            }
        }

        setContent {
            val tier by governor.tier.collectAsStateWithLifecycle()
            val level by governor.level.collectAsStateWithLifecycle()
            val penalty by governor.penalty.collectAsStateWithLifecycle()
            val thermal by governor.thermal.collectAsStateWithLifecycle()
            val jank by governor.jankRatio.collectAsStateWithLifecycle()
            val fps by governor.fps.collectAsStateWithLifecycle()
            val powerSave by governor.powerSave.collectAsStateWithLifecycle()
            val reason by governor.lastReason.collectAsStateWithLifecycle()

            DeskTheme(useDarkTheme = true) {
                AdaptiveDebugScreen(
                    context = this@AdaptiveDebugActivity,
                    profile = profile,
                    tier = tier,
                    level = level,
                    penalty = penalty,
                    thermal = thermal,
                    jankRatio = jank,
                    fps = fps,
                    powerSave = powerSave,
                    lastReason = reason,
                    baseTier = governor.baseTier.value,
                    governor = governor,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        governor.start()
    }

    override fun onStop() {
        governor.stop()
        super.onStop()
    }

    private fun enterDeskImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

/** 几组预设尺寸，覆盖"更宽 / 更窄 / 更矮"。 */
private val PRESET_SIZES: List<Pair<Int, Int>> = listOf(
    914 to 411,
    794 to 361,
    720 to 340,
    640 to 320,
    560 to 300,
    480 to 280,
)

@Composable
private fun AdaptiveDebugScreen(
    context: Context,
    profile: DeviceProfile,
    tier: EffectTier,
    baseTier: EffectTier,
    level: EffectLevel,
    penalty: Int,
    thermal: Int,
    jankRatio: Float,
    fps: Float,
    powerSave: Boolean,
    lastReason: String,
    governor: EffectGovernor,
) {
    var simulated by remember(profile.fingerprint) {
        val w = ShellSettings.simulatedWidthDp(context)
        val h = ShellSettings.simulatedHeightDp(context)
        mutableStateOf(if (w != null && h != null) w to h else null)
    }
    var forcedTier by remember {
        mutableStateOf(ShellSettings.forcedEffectTier(context))
    }

    val sim = simulated
    val effectiveProfile = if (sim != null) {
        // 模拟尺寸时**连像素一起改**：dp 与 px 是同一个事实的两种单位，
        // 只改一边会让调试页自相矛盾（显示 875×356dp 而 px 还是 3200×1440）。
        val density = if (profile.densityDpi > 0) profile.densityDpi / 160f else 1f
        profile.copy(
            widthDp = sim.first,
            heightDp = sim.second,
            widthPx = (sim.first * density).toInt(),
            heightPx = (sim.second * density).toInt(),
        )
    } else {
        profile
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text("自适应框架 · 调试页", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))

        // ---- 1. 设备档案 ----
        Section("设备档案") {
            Kv("机型", profile.shortLabel)
            Kv("API", "${profile.apiLevel}")
            Kv(
                "可用宽高",
                "${effectiveProfile.widthDp} × ${effectiveProfile.heightDp} dp" +
                    if (sim != null) "（模拟）" else "",
            )
            // 像素与密度一起显示：dp 对不对，一眼能和 `wm size` / `wm density` 对上。
            // 这块曾经出过一次真问题——Configuration.screenWidthDp 少报 39×55 dp，
            // 只有把 px 与密度并排放才看得出来。
            Kv(
                "窗口像素",
                "${effectiveProfile.widthPx} × ${effectiveProfile.heightPx} px" +
                    " @ ${profile.densityDpi} dpi（1dp = " +
                    "${(effectiveProfile.widthPx.toFloat() / effectiveProfile.widthDp).let {
                        if (it.isFinite()) (it * 100).toInt() / 100f else 0f
                    }} px）",
            )
            Kv(
                "刷新率",
                if (profile.refreshRateHz > 0f) "${profile.refreshRateHz} Hz" else "读不到（按 60 判）",
            )
            Kv(
                "挖孔",
                if (profile.cutout.known) {
                    "左${profile.cutout.leftDp} 上${profile.cutout.topDp} " +
                        "右${profile.cutout.rightDp} 下${profile.cutout.bottomDp} dp"
                } else {
                    "读不到"
                },
            )
            Kv("WebView", profile.webViewVersion ?: "未启用")
            Kv("内存", if (profile.totalMemoryMb > 0L) "${profile.totalMemoryMb} MB" else "读不到")
            Kv("指纹", profile.fingerprint)
            Kv("档位设置来源", ShellSettings.effectLevelOwner(context, profile) ?: "本机未设过")
        }

        // ---- 2. 档位 ----
        Section("效果档位") {
            Kv("当前档位", "${tier.label} —— ${tier.detail}")
            Kv("基线档位（设备最高）", baseTier.label)
            Kv("运行时降级", "$penalty 档" + if (penalty > 0) "（已触发）" else "")
            Kv("用户选择", level.label)
            Kv("强制档位", forcedTier?.label ?: "无")
            Kv("最近一次变更", lastReason)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                EffectTier.entries.forEach { t ->
                    Button(
                        onClick = {
                            forcedTier = t
                            ShellSettings.setForcedEffectTier(context, t)
                            governor.setOverride(t)
                        },
                        modifier = Modifier.height(30.dp),
                        contentPadding = ButtonDefaults.ContentPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (forcedTier == t) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) { Text(t.label, fontSize = 11.sp) }
                }
                Button(
                    onClick = {
                        forcedTier = null
                        ShellSettings.setForcedEffectTier(context, null)
                        governor.setOverride(null)
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text("取消强制", fontSize = 11.sp) }
            }
        }

        // ---- 3. 运行时信号 ----
        Section("运行时信号") {
            Kv("帧率", if (fps < 0f) "测量中…" else "${fps.toInt()} fps")
            Kv("掉帧率", if (jankRatio < 0f) "测量中…" else "${(jankRatio * 100).toInt()}%")
            Kv("热状态", governor.thermalLabel(thermal))
            Kv("省电模式", if (powerSave) "开" else "关")
            Text(
                "降档：连续 ${EffectGovernor.DOWN_AFTER_BAD_WINDOWS} 个坏窗口（掉帧 ≥ " +
                    "${(EffectGovernor.JANK_BAD * 100).toInt()}%）｜升档：连续 " +
                    "${EffectGovernor.UP_AFTER_GOOD_WINDOWS} 个好窗口（掉帧 < " +
                    "${(EffectGovernor.JANK_GOOD * 100).toInt()}%）｜冷却 " +
                    "${EffectGovernor.COOLDOWN_MS / 1000}s",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
        }

        // ---- 4. 模拟尺寸 ----
        Section("模拟尺寸（应用内，不动系统设置）") {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PRESET_SIZES.forEach { preset ->
                    Button(
                        onClick = {
                            simulated = preset
                            ShellSettings.setSimulatedSize(context, preset.first, preset.second)
                        },
                        modifier = Modifier.height(30.dp),
                        contentPadding = ButtonDefaults.ContentPadding,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (sim == preset) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ),
                    ) { Text("${preset.first}×${preset.second}", fontSize = 11.sp) }
                }
                Button(
                    onClick = {
                        simulated = null
                        ShellSettings.setSimulatedSize(context, null, null)
                    },
                    modifier = Modifier.height(30.dp),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) { Text("还原", fontSize = 11.sp) }
            }
            Spacer(Modifier.height(8.dp))

            val screen = LocalConfiguration.current
            val scale = minOf(
                1f,
                minOf(
                    (screen.screenWidthDp - 24).toFloat() / effectiveProfile.widthDp,
                    (screen.screenHeightDp * 0.62f) / effectiveProfile.heightDp,
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height((effectiveProfile.heightDp * scale).dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            width = (effectiveProfile.widthDp * scale).dp,
                            height = (effectiveProfile.heightDp * scale).dp,
                        )
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .border(width = (2f / scale).dp, color = MaterialTheme.colorScheme.primary),
                ) {
                    // **关键**：连 DeviceProfile 一起换成模拟值 ——
                    // 只缩小盒子而不换档案的话，里面的组件仍按真机尺寸反推，必然溢出。
                    CompositionLocalProvider(
                        LocalDeviceProfile provides effectiveProfile,
                        LocalEffectTier provides (forcedTier ?: tier),
                    ) {
                        SimulatedShell()
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            // 内容区宽 = 档案里的 widthDp（已扣系统栏与挖孔）− 侧栏那一列。
            val contentWidth = (
                effectiveProfile.widthDp.toFloat() - 88f
                ).coerceAtLeast(240f)
            Text(
                "按当前模拟尺寸算：设置浮层 " +
                    "${AdaptiveDimens.settingsPaneWidth(contentWidth).value.toInt()}dp｜" +
                    "栅格 ${AdaptiveDimens.gridColumns(contentWidth)} 列｜" +
                    "预览卡上限 " +
                    "${AdaptiveDimens.previewMaxHeight(effectiveProfile.heightDp.toFloat()).value.toInt()}dp",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

/** 模拟盒子里画什么：真实的侧栏 + 一块内容区占位。 */
@Composable
private fun SimulatedShell() {
    val items = remember { defaultShellItems() }
    var selectedId by remember { mutableStateOf(ShellIds.HOME) }
    val profile = LocalDeviceProfile.current
    val fit = remember(profile.heightDp, items.size) {
        fitSidebar(
            // 档案里的 heightDp 已是内容区高（系统栏与挖孔都扣过），不再减第二次。
            availableHeightDp = profile.heightDp.coerceAtLeast(1).toFloat(),
            itemCount = items.size,
        )
    }

    Row(modifier = Modifier.fillMaxSize().background(Color(0xFF14161C))) {
        LensSideBar(
            items = items,
            selectedId = selectedId,
            onSelect = { selectedId = it.id },
            backdrop = null,
            lensEnabled = LensSpec.ENABLED,
            modifier = Modifier.align(Alignment.CenterVertically),
        )
        Spacer(Modifier.width(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp),
        ) {
            Text(
                "条目 ${fit.itemSize.value.toInt()}dp｜间距 ${fit.gap.value.toInt()}dp｜" +
                    "${fit.level.label}｜放得下=${fit.fitting}",
                fontSize = 9.sp,
                color = Color.White,
            )
            if (!fit.fitting) {
                Text(
                    "⚠ 触控下限排不下 ${items.size} 项，实际显示前 ${fit.suggestedMaxItems} 项" +
                        "（超出 ${fit.overflow.value.toInt()}dp）",
                    fontSize = 9.sp,
                    color = Color(0xFFFFB4A2),
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = 0.06f)),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(8.dp),
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun Kv(key: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            key,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.width(8.dp))
        Text(value, fontSize = 11.sp, maxLines = 1)
    }
}
