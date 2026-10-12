package com.qingke.desk.shell

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange


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
 * ## 只用 [androidx.compose.foundation.gestures.detectDragGestures] 是不够的（2026-10-13 修）
 *
 * 旧实现用 `detectDragGestures`，它在**整屏**范围内一旦判定成拖动就**无条件 consume**，
 * 而且它下面的兄弟节点正是 `AMapNaviView`（导航地图）。真机取证（`.verify/reg/
 * W-tree.log`，8 个来回拖动）：
 *
 * ```
 * touch probe: edge onDrag=460      ← Compose 这层收到 460 次
 * touch probe: naviView MOVE=20     ← 导航地图只收到 20 次
 * ```
 *
 * 也就是**九成以上的拖动事件根本没走到地图**，而地图恰好又靠连续的 MOVE 来跟手。
 * 用户看到的就是"滑屏卡顿、拖不动"。
 *
 * 所以这里不再用 `detectDragGestures`，改成手写循环，把"要不要抢先消费"这件事
 * 自己握住：**只有起手点真的落在底部 [edgeZoneRatio] 区间内才 consume**，
 * 其余区域的拖动一个字节都不抢，原样落给下层地图。
 *
 * 这条规则是可证伪的：若它成立，同样的拖动下 `naviView MOVE` 应该从 20 涨到
 * 与 `edge onDrag` 同一量级。仍然停在几十就说明拦截发生在别处，得另找。
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

            awaitPointerEventScope {
                while (true) {
                    // requireUnconsumed=false：起手时下层可能已经把 DOWN 用掉了，
                    // 我们只想"看一眼起点在哪"，不该因此拒绝武装。
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val armed = down.position.y >= size.height - edgeHeightPx
                    var accumulatedY = 0f
                    var accumulatedX = 0f
                    var fired = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break

                        // **不武装就什么都不做**：不 consume、不累计，
                        // 让事件原样继续传给下层的地图。
                        // fired 之后同样放手，避免手势已经触发还继续吃事件。
                        if (!armed || fired) continue

                        change.consume()
                        val delta = change.positionChange()
                        accumulatedY += delta.y
                        accumulatedX += delta.x
                        val upward = -accumulatedY
                        if (upward >= minSwipePx && upward > kotlin.math.abs(accumulatedX)) {
                            fired = true
                            onSwipeUp()
                        }
                    }
                }
            }
        },
    )
}
