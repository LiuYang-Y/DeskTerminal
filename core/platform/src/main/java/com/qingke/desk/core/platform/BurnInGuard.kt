package com.qingke.desk.core.platform

import kotlin.math.PI
import kotlin.math.sin

/**
 * OLED 防烧屏：固定元素缓慢微位移。
 *
 * 由挂钟时间纯函数推导，不持有状态、不需要定时器，
 * 因此配置重建（旋转、主题切换）不会让漂移位置跳变。
 *
 * 2026-10-12 之前的两次亮度尝试都已删除，**本应用从此不碰用户的亮度**：
 *   1. `scheduledBrightness(hourOfDay)`（按小时分档设窗口亮度）：写在 onResume 里无条件执行，
 *      结果是每次进入应用都把用户自己调好的亮度拉走一档（真机投诉"进来就变暗"）；
 *   2. 空闲屏保时压到 0.08：屏保连同 `IdleGuard` 一起删了，这一档也就没了。
 * 漂移是唯一留下来的防烧屏手段 —— 它只挪内容，不动亮度。
 */
object BurnInGuard {

    /**
     * 当前时刻应施加的漂移偏移，单位 dp。
     *
     * 走李萨如曲线（x 一个周期、y 两个周期）：轨迹闭合、不重复走同一条线，
     * 比单纯左右平移更难在屏幕基材上留下固定印记。
     */
    fun driftOffsetDp(
        nowMs: Long,
        rangeDp: Float,
        periodMinutes: Long = 10L,
    ): Pair<Float, Float> {
        if (rangeDp <= 0f || periodMinutes <= 0L) return 0f to 0f
        val periodMs = periodMinutes * 60_000L
        val t = ((nowMs % periodMs).toDouble() / periodMs) * 2.0 * PI
        val x = (sin(t) * rangeDp).toFloat()
        val y = (sin(2.0 * t) * rangeDp * 0.6f).toFloat()
        return x to y
    }
}
