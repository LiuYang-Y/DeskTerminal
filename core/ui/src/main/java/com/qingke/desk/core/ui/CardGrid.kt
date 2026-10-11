package com.qingke.desk.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * 横屏卡片栅格。
 *
 * 不计算任何像素：单元格尺寸完全交给 Row/Column 的 weight 均分，
 * 因此换一台不同分辨率 / DPI 的机器布局自动适配，不需要改本文件。
 * 列数由调用方从 DeskDimens 取，命中"列数可配置"的要求。
 */
@Composable
fun CardGrid(
    itemCount: Int,
    modifier: Modifier = Modifier,
    columns: Int = DeskDimens.GRID_COLUMNS,
    /** 固定行数。0 表示由 itemCount 推导。空间不足时由调用方下调此值。 */
    rows: Int = 0,
    gap: Dp = DeskDimens.gridGap,
    contentPadding: PaddingValues = PaddingValues(DeskDimens.screenPadding),
    itemContent: @Composable (index: Int) -> Unit,
) {
    val safeColumns = columns.coerceAtLeast(1)
    val rowCount = rows.takeIf { it > 0 }
        ?: ceil(itemCount / safeColumns.toFloat()).toInt().coerceAtLeast(1)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        repeat(rowCount) { row ->
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                repeat(safeColumns) { column ->
                    val index = row * safeColumns + column
                    Box(modifier = Modifier.weight(1f)) {
                        if (index < itemCount) itemContent(index)
                    }
                }
            }
        }
    }
}
