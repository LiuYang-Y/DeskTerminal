package com.qingke.desk.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.qingke.desk.core.platform.DeviceProfile
import com.qingke.desk.core.platform.EffectTier

/**
 * 自适应框架的两个 CompositionLocal。
 *
 * ---------------------------------------------------------------------------
 * 为什么是 CompositionLocal 而不是把参数一路传下去
 *
 * [DeviceProfile] 和 [EffectTier] 是**横切**信息：首页、地图页、导航页、设置浮层、
 * 侧栏、以及以后的音乐页都要读，而这些组件之间并没有一条能顺手把参数带下去的调用链。
 * 逐层传参会让十几个 Composable 的签名多出两个与自身职责无关的参数。
 *
 * ---------------------------------------------------------------------------
 * 两个 Local 的**变更语义不同**，这是选 `static` 还是普通的关键：
 *
 *   · [LocalDeviceProfile] 用 `staticCompositionLocalOf`：
 *     一台机器上的设备档案**在整个进程生命周期内不变**（换屏幕走的是配置变更 →
 *     Activity 重建，整棵树本来就重组）。static 的实现不登记读取点，开销更低。
 *     默认值 [DeviceProfile.FALLBACK] 只在预览 / 忘了 provide 时出现，
 *     它的宽高是 0 —— **这是故意的**：宽高为 0 会让所有按可用空间反推的尺寸
 *     立刻夹到下限，问题在预览里就看得见，而不是悄悄用一个假尺寸蒙过去。
 *
 *   · [LocalEffectTier] 用 `compositionLocalOf`：
 *     它会随温度、掉帧、用户在设置里改档而**在运行时变**。普通的
 *     compositionLocalOf 只重组真正读了它的那一小片，正是我们要的 ——
 *     档位变化时只重建"画玻璃的那一层"，不去碰整棵内容树。
 */
// 类型必须写成 ProvidableCompositionLocal：infix `provides` 是它的**成员**，
// 不是 CompositionLocal 的扩展。写成 `CompositionLocal<...>` 会把这个类型擦掉，
// 调用方 `LocalDeviceProfile provides x` 就会报 Unresolved reference 'provides'
// （这个坑在 debug 变体才发现——主 Activity 里那两行是同一处根因）。
val LocalDeviceProfile: ProvidableCompositionLocal<DeviceProfile> =
    staticCompositionLocalOf { DeviceProfile.FALLBACK }

val LocalEffectTier: ProvidableCompositionLocal<EffectTier> =
    compositionLocalOf { EffectTier.HIGH }

/** 读当前设备档案。 */
val currentDeviceProfile: DeviceProfile
    @Composable
    @ReadOnlyComposable
    get() = LocalDeviceProfile.current

/** 读当前**实际生效**的效果档位。 */
val currentEffectTier: EffectTier
    @Composable
    @ReadOnlyComposable
    get() = LocalEffectTier.current

/**
 * 玻璃/折射类效果当前能不能用。
 *
 * 只有 [EffectTier.HIGH] 才有真折射（RuntimeShader），[EffectTier.MEDIUM] 有真模糊，
 * [EffectTier.LOW] 与 [EffectTier.OFF] 一律自绘。
 * 需要区分"有没有模糊"的调用方（比如决定要不要叠一层玻璃）用 [blurAvailable]。
 */
val EffectTier.refractionAvailable: Boolean get() = this == EffectTier.HIGH

val EffectTier.blurAvailable: Boolean get() = this == EffectTier.HIGH || this == EffectTier.MEDIUM

/** 这一档**要不要建玻璃层**。OFF 时不建 —— 建了不画仍然有合成开销。 */
val EffectTier.glassLayerEnabled: Boolean get() = this != EffectTier.OFF
