package com.qingke.desk.shell

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 边缘上滑逃生手势。
 *
 * 存在理由：终端一旦被设为 HOME，用户没有别的出口。投屏接管画面后
 * 如果同时失去本机手势，出问题只能靠 adb 卸载。所以这里必须留一条
 * 不依赖投屏对端的返回路径。
 *
 * 判定：起手点落在底部 [edgeZoneRatio] 比例区间内，且**累计**向上位移超过
 * [minSwipeRatio]，且纵向累计位移大于横向（避免误触横向滑动）。
 *
 * 注意是"累计"而不是"单次"：dragAmount 是每一帧的增量，真机上一帧只有几十像素，
 * 拿它跟屏高的 12%（约 150 px）比，条件永远不成立 —— 这是真机实测抓到的 bug，
 * 症状是"边缘上滑完全没反应"。
 *
 * 全部用比例表达，不写死 dp —— 同一套手势在 5 寸和 7 寸屏上体感一致。
 *
 * 已知限制：阶段一走 EXTERNAL_ACTIVITY 拉起外部 DiPlay 时，画面不在本进程，
 * 本手势收不到触摸事件，因此 enabled=false。此时的退出依赖对端或系统返回键。
 */
fun Modifier.edgeSwipeUp(
    enabled: Boolean,
    edgeZoneRatio: Float = 0.18f,
    minSwipeRatio: Float = 0.12f,
    onSwipeUp: () -> Unit,
): Modifier {
    if (!enabled) return this
    return this.then(
        Modifier.pointerInput(enabled, edgeZoneRatio, minSwipeRatio) {
            val edgeHeightPx = size.height * edgeZoneRatio
            val minSwipePx = size.height * minSwipeRatio
            var armed = false
            var accumulatedY = 0f
            var accumulatedX = 0f

            detectDragGestures(
                onDragStart = { offset ->
                    armed = offset.y >= size.height - edgeHeightPx
                    accumulatedY = 0f
                    accumulatedX = 0f
                },
                onDragEnd = { armed = false },
                onDragCancel = { armed = false },
                onDrag = { _, dragAmount ->
                    if (!armed) return@detectDragGestures
                    accumulatedY += dragAmount.y
                    accumulatedX += dragAmount.x
                    val upward = -accumulatedY
                    if (upward >= minSwipePx && upward > kotlin.math.abs(accumulatedX)) {
                        armed = false
                        onSwipeUp()
                    }
                },
            )
        },
    )
}
