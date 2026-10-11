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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.data.SidebarSide
import com.qingke.desk.core.ui.DeskDimens

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
 *  2. 这里是 Shizuku 角标已经在用的悬浮区，两者上下相邻、各占各的一行 ——
 *     角标只在状态异常时出现，齿轮常驻，不会互相遮挡。
 *
 * ---------------------------------------------------------------------------
 * 面板里现在只有两项，都是"显示/硬件"层面的开关
 *
 * 刻意不把业务开关（通知使用权、定位、Shizuku 授权）搬进来：那些开关的**生效位置在
 * 系统设置里**，我们能做的只是把系统界面推到前台（见 [com.qingke.desk.feature.media.MediaPermission]），
 * 在浮层里放一个点了会跳转到别处的假开关只会误导人。
 */
@Composable
fun SettingsPane(
    sidebarSide: SidebarSide,
    keepScreenOn: Boolean,
    amapAgreed: Boolean,
    onSidebarSideChange: (SidebarSide) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onRevokeAmapConsent: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 高度上限 = 屏幕高 - 顶部留白 - 齿轮 - 间距 - 底部留白。
    //
    // 这个上限不是"设计成这么高"，而是**兜底**：横屏只有 361 dp，三项设置刚好塞满，
    // 一旦系统字号被调大、或者以后加第四项，没有上限就会直接从屏幕下沿漏出去
    // （实测过，见 .verify/settings/15-pane-3items.png）。有了它，内容再高也只是
    // 面板内部滚动，不会顶穿屏幕。高度用屏幕高反推而不是写死，换机器不用改。
    val screenHeightDp = LocalConfiguration.current.screenHeightDp.dp
    val maxPaneHeight = screenHeightDp -
        DeskDimens.statusChipTopPadding -
        DeskDimens.settingsChipSize -
        DeskDimens.statusChipGap -
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
                help = "卡片浮层与状态角标会跟着移到另一侧。",
            ) {
                SidePicker(
                    current = sidebarSide,
                    onPick = onSidebarSideChange,
                )
            }

            Spacer(modifier = Modifier.height(DeskDimens.settingsItemGap))

            SettingsItem(
                title = "屏幕常亮",
                help = "关闭后跟随系统熄灭：省电、几乎不烧屏，但看时间要先点亮。",
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
                    "已同意高德隐私政策，地图可显示。"
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
        }
    }
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
                // 说明最多两行：横屏纵向预算是硬约束，长文案会把整块面板顶出屏幕。
                maxLines = 2,
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
