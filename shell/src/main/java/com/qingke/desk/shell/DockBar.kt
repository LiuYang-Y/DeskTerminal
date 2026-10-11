package com.qingke.desk.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.qingke.desk.core.ui.DeskDimens
import com.qingke.desk.core.ui.deskGlassSurface

data class DockItem(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 首页底栏。每个条目固定 [DeskDimens.dockItemSize] 见方，
 * 保证"大触控区"—— 手臂距离外也能一次点中。
 *
 * 布局上它是**浮在内容之上**的一层（见 [HomeScreen]），而不是 Column 里的最后一行：
 * 液态玻璃必须有东西可"玻"，背后若是纯底色就只剩一层半透明矩形，看不出效果。
 *
 * [glassBackdrop] 为 null 时整层完全透明 —— 这是关闭玻璃效果的形态，
 * 两种情况共用同一套布局，方便同一台机器直接对比。
 *
 * TODO(step-1): 条目来源后续改为可配置（用户自定义常用应用 / 深链）。
 */
@Composable
fun DockBar(
    items: List<DockItem>,
    selectedId: String?,
    onSelect: (DockItem) -> Unit,
    glassBackdrop: LayerBackdrop? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(
                start = DeskDimens.screenPadding,
                end = DeskDimens.screenPadding,
                bottom = DeskDimens.dockBarBottomMargin,
            )
            // 注意 Modifier 顺序：先外留白再上玻璃，玻璃的边界才是"内容实际占据的那块"，
            // 而不是整屏宽；否则会变成一条贴到屏幕边缘的长条。
            .deskGlassSurface(
                backdrop = glassBackdrop,
                shape = { RoundedCornerShape(DeskDimens.dockCorner) },
            )
            .padding(horizontal = DeskDimens.dockSpacing, vertical = DeskDimens.dockSpacing / 2),
        horizontalArrangement = Arrangement.spacedBy(DeskDimens.dockSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            DockButton(
                item = item,
                selected = item.id == selectedId,
                onClick = { onSelect(it) },
            )
        }
    }
}

@Composable
private fun DockButton(
    item: DockItem,
    selected: Boolean,
    onClick: (DockItem) -> Unit,
) {
    val tint = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .size(DeskDimens.dockItemSize)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick(item) },
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            Color.Transparent
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(DeskDimens.dockIconSize),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.label,
                fontSize = DeskDimens.dockLabel,
                color = tint,
                maxLines = 1,
            )
        }
    }
}
