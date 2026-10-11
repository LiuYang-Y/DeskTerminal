package com.qingke.desk.core.platform

import kotlin.math.PI
import kotlin.math.sin

/**
 * OLED 防烧屏：固定元素缓慢微位移。
 *
 * 由挂钟时间纯函数推导，不持有状态、不需要定时器，
 * 因此配置重建（旋转、主题切换）不会让漂移位置跳变。
 *
 * 2026-10-12 删掉了原来的 `scheduledBrightness(hourOfDay)`（按小时分档设窗口亮度）：
 * 它是在 onResume 里无条件执行的，结果是**每次进入应用都把用户自己的亮度拉走一档**
 * （真机投诉"进来就变暗"）。亮度现在只跟屏保走 —— 见 MainActivity.applyScreensaverBrightness。
 * 以后若还想要分时段亮度，那应该是一个用户显式打开的设置项，而不是无条件的覆盖。
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
