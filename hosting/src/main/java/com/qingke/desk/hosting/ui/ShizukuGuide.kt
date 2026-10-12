package com.qingke.desk.hosting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.hosting.ShizukuGate
import com.qingke.desk.hosting.ShizukuStatus

/**
 * Shizuku 的状态展示与引导。
 *
 * 与托管页里那套 [BlockedNotice] 是**同一件事的两种出现场合**：
 * 那边是"要用的时候才发现不行"，这边是"还没用的时候就看得见行不行"。
 * 步骤文案两边保持一致，避免同一个问题给出两套说法。
 */

/** 状态点颜色。绿色只在"能用"时出现，其余一律警示色 —— 这是唯一需要用户看的信号。 */
@Composable
fun statusColor(status: ShizukuStatus): Color = when (status) {
    ShizukuStatus.READY -> Color(0xFF3DDC84)
    ShizukuStatus.NOT_INSTALLED -> MaterialTheme.colorScheme.outline
    ShizukuStatus.NOT_RUNNING, ShizukuStatus.NOT_AUTHORIZED -> MaterialTheme.colorScheme.error
}

/**
 * 状态的一句话说明。
 *
 * 公开给 :shell 用：设置面板里的「Shizuku」一行要显示同一句话，
 * 同一个状态在两处给出两种说法，用户会以为是两个不同的问题。
 */
@Composable
fun statusLabel(status: ShizukuStatus): String = when (status) {
    ShizukuStatus.READY -> "Shizuku 运行中"
    ShizukuStatus.NOT_RUNNING -> "Shizuku 未运行"
    ShizukuStatus.NOT_AUTHORIZED -> "Shizuku 未授权"
    ShizukuStatus.NOT_INSTALLED -> "未安装 Shizuku"
}

/**
 * 小圆点 + 一句话的紧凑状态指示。
 *
 * 2026-10-12：**不再挂在壳层的右上角悬浮区** —— 那个位置现在只留设置齿轮。
 * 角标压在卡片角上会跟天气卡、隐私卡、快捷卡叠字（真机截图
 * .verify/pager/v11-revoke-fullscreen.png），而它传递的信息（Shizuku 能不能用）
 * 不属于"每时每刻都要看见"的那一类：用到托管/投屏时才有意义。
 * 现在它只在设置面板里（见 [SettingsPane] 的 Shizuku 一行）与下面这个 debug 预览页出现。
 */
@Composable
fun ShizukuStatusChip(
    status: ShizukuStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .height(DeskDimens.statusChipHeight)
            .clip(RoundedCornerShape(DeskDimens.statusChipHeight / 2))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor(status)),
            )
            Text(
                text = statusLabel(status),
                fontSize = DeskDimens.statusChipText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * 引导页。点状态栏上的指示进到这里。
 *
 * 原则与 [BlockedNotice] 相同：每一步都是**用户能照着做的动作**，
 * 并且明说哪些动作只能由用户本人完成（授权弹窗、Shizuku 里的「启动」）。
 */
@Composable
fun ShizukuGuideScreen(
    status: ShizukuStatus,
    onRefresh: () -> Unit,
    onRequestPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (status) {
            ShizukuStatus.NOT_INSTALLED -> HostingNotice(
                title = "Shizuku 还没装",
                steps = listOf(
                    "在设备上安装 Shizuku（官方渠道或 F-Droid 均可）。",
                    "打开 Shizuku，按它的引导完成一次「无线调试 + 配对码」配对。",
                    "回到本应用，点下面的「重新检测」。",
                ),
                note = "不装 Shizuku 也能用时钟、日程、待办这些卡片；只有地图托管与投屏需要它。",
                secondaryAction = "返回首页",
                onSecondaryAction = onExit,
            )

            // 文案长度是被屏幕逼出来的：横屏只有 361 dp，卡片内容区约 259 dp，
            // 超出的部分会被裁（note 那一条会被切掉一半，很难看）。
            // 所以每条步骤都压到 1~2 行，note 压到 2 行以内。
            ShizukuStatus.NOT_RUNNING -> HostingNotice(
                title = "Shizuku 未运行",
                steps = listOf(
                    "到 设置 → 开发者选项 → 无线调试，把开关打开。",
                    "打开 Shizuku 应用点「启动」—— 首次要按提示用配对码配对一次。",
                    "回到本应用点「重新检测」。",
                ),
                note = "重启手机后无线调试会关闭、Shizuku 也会停，需要再来一次。",
                primaryAction = "打开 Shizuku",
                onPrimaryAction = onOpenShizuku,
                secondaryAction = "重新检测",
                onSecondaryAction = onRefresh,
            )

            ShizukuStatus.NOT_AUTHORIZED -> HostingNotice(
                title = "Shizuku 已运行，本应用还没被授权",
                steps = listOf(
                    "点「请求授权」后会弹出 Shizuku 的授权窗口。",
                    "请在弹窗里自己点「允许」—— 这一步只能由你确认，本应用不会、也无法代替你点击。",
                    "授权后本页会自动继续。",
                ),
                note = "重启手机后 Shizuku 会停，需要重新点一次「启动」（已授予的授权通常保留）。",
                primaryAction = "请求授权",
                onPrimaryAction = onRequestPermission,
                secondaryAction = "重新检测",
                onSecondaryAction = onRefresh,
            )

            ShizukuStatus.READY -> HostingNotice(
                title = "Shizuku 运行中",
                steps = listOf(
                    "不需要任何操作 —— 地图托管与投屏现在可以启动。",
                    "想确认服务还在，点「重新检测」再查一次。",
                ),
                note = "当前 Shizuku 身份：${ShizukuGate.describeIdentity()}。",
                primaryAction = "重新检测",
                onPrimaryAction = onRefresh,
                secondaryAction = "返回首页",
                onSecondaryAction = onExit,
            )
        }

        // 返回单独放右上角，不占 HostingNotice 的两个操作位：
        // 那两个位留给"当前状态真正该做的动作"，混进来会让用户分不清主次。
        GuideCloseChip(
            onClick = onExit,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        )
    }
}

@Composable
private fun GuideCloseChip(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .size(width = 64.dp, height = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color.Black.copy(alpha = 0.45f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = "返回", fontSize = 14.sp, color = Color.White)
        }
    }
}

/**
 * 重启手机后的一次性提示。
 *
 * 做成盖在首页上的一层，而不是吐司：吐司一闪而过，用户很可能没看清
 * 「要重新去 Shizuku 里点启动」这条最关键的动线。
 * 但只弹一次 —— 关掉之后本次开机不再出现。
 */
@Composable
fun ShizukuBootPromptCard(
    status: ShizukuStatus,
    onOpenGuide: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        HostingNotice(
            title = when (status) {
                ShizukuStatus.NOT_INSTALLED -> "需要 Shizuku 才能用地图和投屏"
                ShizukuStatus.NOT_AUTHORIZED -> "Shizuku 需要一次授权"
                else -> "Shizuku 未运行"
            },
            steps = listOf(
                "手机重启后 Shizuku 不会自己起来，这是它本身的机制，不是本应用的问题。",
                "到 设置 → 开发者选项 → 无线调试 打开开关，再打开 Shizuku 点「启动」。",
                "也可以先点「知道了」，其余卡片照常用。",
            ),
            note = "本次开机只提示这一次。",
            primaryAction = "查看引导",
            onPrimaryAction = onOpenGuide,
            secondaryAction = "知道了",
            onSecondaryAction = onDismiss,
        )
    }
}
