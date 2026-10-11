package com.qingke.desk.core.platform

import kotlin.math.PI
import kotlin.math.sin

/**
 * OLED 防烧屏：分时段亮度 + 固定元素缓慢微位移。
 *
 * 两者都由挂钟时间纯函数推导，不持有状态、不需要定时器，
 * 因此配置重建（旋转、主题切换）不会让漂移位置跳变。
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

    /**
     * 分时段窗口亮度。夜里压到最低档，白天给足。
     *
     * TODO(adb) [待校正]：具体档位需要按这台机器的面板亮度和摆放位置实测，
     *           现在是拍脑袋的保守值。
     */
    fun scheduledBrightness(hourOfDay: Int): Float = when (hourOfDay) {
        in 0..6 -> 0.20f
        in 7..8 -> 0.50f
        in 9..17 -> 0.85f
        in 18..21 -> 0.60f
        else -> 0.30f
    }
}
