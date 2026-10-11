package com.qingke.desk.core.model

/**
 * 运行期屏幕信息。所有需要物理像素的地方（投屏 Surface、视频分辨率上限）
 * 都从这里取值，不允许在布局代码里出现常量像素。
 *
 * TODO(adb) [待校正]：由 MainActivity 在 attach 后用
 *           WindowMetrics / DisplayMetrics 填充真机值。
 */
data class DisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val nightMode: Boolean = false,
) {

    /** 按 DeskDimens 的上限裁剪后的视频目标尺寸。 */
    fun clippedProjectionSize(
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): Pair<Int, Int> {
        // 未指定真机分辨率时退回上限值，宁可先超采也不要退化成 1x1。
        val width = if (widthPx > 0) widthPx.coerceAtMost(maxWidthPx) else maxWidthPx
        val height = if (heightPx > 0) heightPx.coerceAtMost(maxHeightPx) else maxHeightPx
        return width to height
    }

    companion object {
        val Unspecified = DisplayInfo(
            widthPx = 0,
            heightPx = 0,
            densityDpi = 0,
        )
    }
}
