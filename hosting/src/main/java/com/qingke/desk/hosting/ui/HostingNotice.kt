package com.qingke.desk.hosting.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 托管页的遮罩提示。
 *
 * 原则：**任何一个看不到画面的时刻，都必须同时看得到原因和下一步该做什么。**
 * 黑屏不给解释是这个模块最容易犯的错 —— 虚拟屏这条链路上有太多环节可能被系统拒绝，
 * 而拒绝是静默的（不抛异常，只是画面不出现）。
 */
@Composable
fun HostingNotice(
    title: String,
    /** 每一步都写成"用户能照着做的动作"，不写原理。 */
    steps: List<String>,
    /** 需要提醒但不属于操作步骤的话（例如重启后要重新激活）。 */
    note: String? = null,
    primaryAction: String? = null,
    onPrimaryAction: (() -> Unit)? = null,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hasActions = primaryAction != null || secondaryAction != null

    // 用 BoxWithConstraints 拿可用高度，是为了给卡片一个**明确的高度上限**。
    // 这辆车（横屏）只有 361 dp 高，而"三条步骤 + 一段提醒 + 两个按钮"很容易超出去；
    // 一旦超出，Column 会把最后一个孩子的可用高度算成 0 ——
    // 按钮就被挤成 0 高，肉眼看不见、元素树里也找不到（真机上实测过这个现象）。
    // 所以这里：卡片高度封顶 -> 文字部分自己滚 -> **按钮永远钉在卡片底部**。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        val maxCardHeight = maxHeight * 0.94f

        // 外边距取 12 而不是 20：这辆车横屏只有 361 dp 高，卡片内容区每多要 8 dp
        // 就得从步骤里挤出来，而步骤是被裁得最难看的一块。
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .heightIn(max = maxCardHeight)
                .padding(12.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // 只有"标题 + 步骤"参与滚动。
                // fill = false：内容不多时按内容收缩，卡片不会硬撑到上限。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    steps.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = "${index + 1}.",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text(
                                text = step,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                // note 与按钮一样**钉在滚动区之外**。
                // 起初它放在滚动区末尾，结果是：内容一超高，这条提醒就被卡片边缘
                // 齐腰切断 —— 只剩半截色块、字都看不见（真机上截到过这张图）。
                // 提醒本身是"必须被读到"的信息，不能让它成为第一个被牺牲的东西。
                if (!note.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                    ) {
                        Text(
                            text = note,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }

                if (hasActions) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (secondaryAction != null && onSecondaryAction != null) {
                            OutlinedButton(onClick = onSecondaryAction) {
                                Text(secondaryAction)
                            }
                        }
                        if (primaryAction != null && onPrimaryAction != null) {
                            Button(onClick = onPrimaryAction) {
                                Text(primaryAction)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 轻量的一行状态条（正在启动 / 正在重建）。
 * 它盖在画面上方但**不挡触摸** —— 只是短暂出现，用户不需要点它。
 */
@Composable
fun HostingStatusBar(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, fontSize = 14.sp, color = Color.White)
    }
}

/**
 * 托管进行中时右上角的操作按钮。
 *
 * 「返回」发的是**虚拟屏里的返回键**，让被托管的应用自己回退一步；
 * 「退出」才是把托管整个收掉回首页。两者不是一回事，别合并。
 */
@Composable
fun HostingControls(
    onBack: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HostingChip(text = "返回", onClick = onBack)
        HostingChip(text = "退出", onClick = onExit)
    }
}

@Composable
private fun HostingChip(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(width = 64.dp, height = 40.dp),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.45f),
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = text, fontSize = 14.sp, color = Color.White)
        }
    }
}
