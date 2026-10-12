package com.qingke.desk.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.data.SidebarSide
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.hosting.ShizukuStatus
import com.qingke.desk.hosting.ui.statusLabel

/**
 * 设置浮层：齿轮入口 + 展开后的面板。
 *
 * ---------------------------------------------------------------------------
 * 为什么是"浮层 + 齿轮"，而不是长按侧栏
 *
 * 侧栏左右切换原来挂在**侧栏长按**上，真机上的问题是：侧栏空白处只有 6dp 内边距
 * 和 6dp 项间缝，手指压根按不准；挂在条目上又被条目的单击抢走（所以最后是"容器 + 条目"
 * 两处都挂），结果就是**想单击切屏时经常切成换边** —— 一次误触整个布局跳到另一边，
 * 代价太大。
 *
 * 现在改成显式入口：右上角常驻一颗齿轮，点开是一块浮层，里面才是开关。误触的后果
 * 从"布局跳边"降级成"多出一个可以点掉的浮层"。
 *
 * 齿轮放右上角而不是塞进侧栏，有两个理由：
 *  1. 侧栏竖排 5 项 × 64dp 已经把 361dp 的屏高占满（6 项需要 384dp，放不下，
 *     见 [SideBar] 顶部的算术），没有第六项的位置；
 *  2. 它是**全局**东西，不该跟着"当前在哪一屏"换来换去。
 *
 * ---------------------------------------------------------------------------
 * 悬浮区只有齿轮这一个元素（2026-10-12 定的）
 *
 * 原来齿轮下面还挂着一颗 Shizuku 状态角标。它压在卡片角上会跟天气卡、全屏隐私卡、
 * 「回家/去公司」快捷卡叠字（真机截图 .verify/pager/v11-revoke-fullscreen.png），
 * 而它传递的信息（Shizuku 能不能用）属于"用到托管/投屏时才关心"，不是常驻信息。
 * 现在状态收进本面板的「Shizuku」一行，悬浮区只剩齿轮 ——
 * 那三处叠字是同一个原因，一起解决。
 *
 * ---------------------------------------------------------------------------
 * 面板里的每一项都是"用户在这儿就能按到的开关"
 *
 * 刻意不放**假开关**：通知使用权、定位、Shizuku 授权这些的生效位置在系统设置里，
 * 我们能做的只是把系统界面推到前台（见 [com.qingke.desk.feature.media.MediaPermission]），
 * 在浮层里放一个点了会跳到别处的开关只会误导人。所以：
 *  · 侧栏位置、屏幕常亮 —— 真开关，就地生效；
 *  · 地图隐私 —— 撤回是真动作（同意在隐私卡里），"已停用"只是文字，不是能按的按钮；
 *  · Shizuku —— 状态是**只读**的，右侧按钮去的是引导页（那里才有一步步的动作）。
 */
@Composable
fun SettingsPane(
    sidebarSide: SidebarSide,
    keepScreenOn: Boolean,
    amapAgreed: Boolean,
    shizukuStatus: ShizukuStatus,
    onSidebarSideChange: (SidebarSide) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onRevokeAmapConsent: () -> Unit,
    onOpenShizukuGuide: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 高度上限 = 屏幕高 - 上下两条留白。
    //
    // 面板现在与齿轮**并列**（见 DeskRoot 里的定位），所以不再减齿轮那一行 ——
    // 这也是四项设置能一屏放下的原因（之前减掉 60 dp，最后一项被挤出屏幕）。
    //
    // 这个上限不是"设计成这么高"，而是**兜底**：横屏只有 411 dp，一旦系统字号被调大、
    // 或者以后加第五项，没有上限就会从屏幕下沿漏出去（实测过，见
    // .verify/settings/15-pane-3items.png）。有了它，内容再高也只是面板内部滚动，
    // 不会顶穿屏幕。高度用屏幕高反推而不是写死，换机器不用改。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxPaneHeight = screenHeightDp -
        DeskDimens.statusChipTopPadding -
        DeskDimens.statusChipSidePadding

    Card(
        modifier = modifier
            .width(DeskDimens.settingsPaneWidth)
            .heightIn(max = maxPaneHeight),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
    ) {
        // 内边距与行距都按"横屏 361 dp 要装下齿轮 + 三项设置"反推过：
        // 把"完成"挪到标题行省下 48 dp，这里再压一档，真机验证见 .verify/settings/。
        Column(
            modifier = Modifier
                .padding(DeskDimens.settingsPanePadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // "完成"放在标题这一行，而不是底部单独一行。
            //
            // 原因很实在：这台机器横屏只有 361 dp 高。三项设置 + 各自两行说明 +
            // 底部一个按钮，实测会把按钮挤出屏幕下沿（真机截图 15-pane-3items.png）。
            // 挪到标题行右侧等于白捡 48 dp 高度，而且出口就在眼睛最先看到的地方。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "设置",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text("完成", fontSize = 14.sp)
                }
            }

            SettingsItem(
                title = "侧栏位置",
                help = "卡片与状态角标跟着镜像。",
            ) {
                SidePicker(
                    current = sidebarSide,
                    onPick = onSidebarSideChange,
                )
            }

            Spacer(modifier = Modifier.height(DeskDimens.settingsItemGap))

            SettingsItem(
                title = "屏幕常亮",
                help = "关闭后跟随系统熄灭，更省电。",
            ) {
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = onKeepScreenOnChange,
                )
            }

            Spacer(modifier = Modifier.height(DeskDimens.settingsItemGap))

            // 地图隐私的撤回入口。放在这里的理由是"每个对外发数据的东西都得有一个
            // 用户能按到的关掉它的地方"（README 的"如何关闭"一节承诺了这件事）。
            SettingsItem(
                title = "地图隐私",
                help = if (amapAgreed) {
                    "已同意高德隐私政策。"
                } else {
                    "尚未同意，地图未启用。"
                },
            ) {
                if (amapAgreed) {
                    TextButton(onClick = onRevokeAmapConsent) {
                        Text("撤回同意", fontSize = 14.sp)
                    }
                } else {
                    Text(
                        text = "已停用",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(DeskDimens.settingsItemGap))

            // Shizuku 状态。2026-10-12 从右上角悬浮角标搬到这里（理由见类注释）。
            //
            // 右侧的按钮**显示状态本身**（"未运行" / "运行中"），而不是写"查看"：
            // 用户点开面板是为了看它现在行不行，状态放在按钮上等于一眼看到答案，
            // 想处理再点进去。引导页里才是那一步步的动作。
            SettingsItem(
                title = "Shizuku",
                help = shizukuHelp(shizukuStatus),
            ) {
                TextButton(onClick = onOpenShizukuGuide) {
                    Text(text = statusLabel(shizukuStatus), fontSize = 13.sp)
                }
            }
        }
    }
}

/**
 * Shizuku 那一项的说明：只写"这个状态意味着什么"，不重复状态本身
 * （状态已经在右侧按钮上）。
 *
 * 一律把"不装/不跑也能正常用"说清楚 —— 它是投屏与托管的**前置条件**，
 * 不是本应用的主功能，读起来不该像"应用坏了"。
 */
private fun shizukuHelp(status: ShizukuStatus): String = when (status) {
    ShizukuStatus.READY -> "投屏与托管可用。"
    ShizukuStatus.NOT_INSTALLED -> "只有投屏与托管需要它。"
    ShizukuStatus.NOT_RUNNING -> "重启手机后需重新启动一次。"
    ShizukuStatus.NOT_AUTHORIZED -> "点右侧按钮完成授权。"
}

/**
 * 齿轮入口。常驻在右上角悬浮区的最上一行。
 *
 * 浮层展开时图标换成主色 —— 让它同时充当"当前是打开状态"的指示，
 * 不必另画一个展开箭头（常亮设备不该为一个状态多占像素）。
 */
@Composable
fun SettingsToggleChip(
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (open) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
    } else {
        Color(0x00000000)
    }
    val tint = if (open) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
    }

    Card(
        onClick = onClick,
        modifier = modifier.size(DeskDimens.settingsChipSize),
        shape = RoundedCornerShape(DeskDimens.settingsChipSize / 2),
        colors = CardDefaults.cardColors(containerColor = background),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = if (open) "收起设置" else "打开设置",
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 一行设置的标题 + 说明 + 右侧控件。说明文字解释"关掉会怎样"，不写正确的废话。 */
@Composable
private fun SettingsItem(
    title: String,
    help: String,
    modifier: Modifier = Modifier,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = help,
                fontSize = 12.sp,
                // **只允许一行**：横屏纵向预算是硬约束 —— 面板从齿轮下面开始，
                // 到屏幕下沿只剩不到 300 dp，而这里已经排了四项。
                // 允许换行时，只要有一条说明折成两行，最后一项（Shizuku）就会被顶出屏幕
                // （真机截图 .verify/pager/w1-settings.png 就是这么被截掉的）。
                // 约束反过来定：文案必须写得进一行，写不进就改文案，不是给面板加高。
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        control()
    }
}

/**
 * 侧栏左/右二选一。
 *
 * 用两个按钮而不是一个 Switch：Switch 的语义是"开 / 关"，而这里是"A 或 B"，
 * 用开关表达"右边 = 开"会让人每次都要在脑子里翻译一遍。
 */
@Composable
private fun SidePicker(
    current: SidebarSide,
    onPick: (SidebarSide) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SidebarSide.entries.forEach { side ->
            val selected = side == current
            if (selected) {
                Button(
                    onClick = { onPick(side) },
                    modifier = Modifier.height(DeskDimens.settingsControlHeight),
                    contentPadding = ButtonDefaults.ContentPadding,
                ) {
                    Text(if (side == SidebarSide.LEFT) "左侧" else "右侧", fontSize = 14.sp)
                }
            } else {
                TextButton(
                    onClick = { onPick(side) },
                    modifier = Modifier.height(DeskDimens.settingsControlHeight),
                ) {
                    Text(
                        text = if (side == SidebarSide.LEFT) "左侧" else "右侧",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}
