package com.qingke.desk.core.platform

/**
 * 全局效果档位。侧栏玻璃透镜、音乐页背景动效**以及以后任何"贵"的效果**都只看这一个枚举，
 * 各自不再自行判断 API 等级 —— 判断只有这一处，改门槛只改这一处。
 *
 * ---------------------------------------------------------------------------
 * 门槛是**读源码读出来的**，不是猜的（2026-10-13）
 *
 * 依据是本仓库 `.verify/src` 下那份 backdrop 2.0.0 的实际源码：
 *
 *   · `.verify/src/androidMain/com/kyant/backdrop/Platform.kt:7`
 *       `isRenderEffectSupported() = SDK_INT >= Build.VERSION_CODES.S`          → **31**
 *   · `.verify/src/androidMain/com/kyant/backdrop/Platform.kt:10`
 *       `isRuntimeShaderSupported() = SDK_INT >= Build.VERSION_CODES.TIRAMISU`  → **33**
 *
 * 两个门槛分别卡在哪：
 *
 *   · **真模糊**：`effects/Blur.kt:13` 第一行 `if (!isRenderEffectSupported()) return`；
 *     更根本的是 `DrawBackdropModifier.kt:363` 的 `updateEffects()` 同样第一行就 return ——
 *     也就是说 **API < 31 连"画玻璃背景层"这件事本身都不发生**，不是"模糊失效但层还在"。
 *   · **真折射**：`effects/Lens.kt:22` 第一行 `if (!isRuntimeShaderSupported()) return`。
 *     RuntimeShader / AGSL 是 Android 13（API 33）才有的能力，这是系统限制不是库限制。
 *
 * 两条都**静默 return**：不崩、不报错，效果凭空消失。所以判断必须由我们来补，
 * 不能指望库给出任何反馈。
 *
 * 另外两个"给了也白给"的边界（同源码）：
 *   · `blur()` 在 `radius <= 0f` 时 return；
 *   · `lens()` 在 `refractionHeight <= 0f || refractionAmount <= 0f` 时 return。
 *
 * ---------------------------------------------------------------------------
 * 第四档 OFF 的作用：不是"效果差一点"，是**一层玻璃层都不建**。
 * 低配机器上"建了层但什么都不画"仍然有合成的开销，OFF 要把这层也省掉。
 */
enum class EffectTier(
    val label: String,
    val detail: String,
    /** 这一档**至少**需要的 API 等级。0 = 无要求。 */
    val minApi: Int,
) {
    /** 真折射 + 真色散 + 真模糊。RuntimeShader，API 33+。 */
    HIGH("高", "真折射 + 真色散 + 真模糊（RuntimeShader / AGSL，需 Android 13 / API 33）", 33),

    /** 真模糊，无折射；彩色边缘用渐变描边近似。RenderEffect，API 31+。 */
    MEDIUM("中", "真模糊，无折射；彩色边缘用渐变描边近似（RenderEffect，需 Android 12 / API 31）", 31),

    /** 半透明纯色底 + 高光 + 滑动动画，无模糊无折射。纯自绘，无 API 要求。 */
    LOW("低", "半透明纯色底 + 高光 + 滑动/进度动画，全部自绘（无 API 要求）", 0),

    /** 关闭：不建玻璃层、不建背景动效层。 */
    OFF("关闭", "不绘制任何玻璃层与背景动效层", 0),
    ;

    /** 这一档在当前设备上"能力上"跑不跑得动（**不考虑**运行时温度与卡顿）。 */
    fun supportedBy(profile: DeviceProfile): Boolean = profile.apiLevel >= minApi

    companion object {
        /** 从高到低，降级时按这个顺序往下走。 */
        val DESCENDING: List<EffectTier> = listOf(HIGH, MEDIUM, LOW, OFF)
    }
}

/**
 * 用户在设置里选的等级。
 *
 * [AUTO] 是**偏好**，不是档位：它表示"由设备能力 + 运行时状态决定"，
 * 实际生效的永远是 [EffectTier]。这样"自动降级"和"用户手动锁定"是同一条链路上的
 * 两个输入，不需要两套代码。
 */
enum class EffectLevel(val label: String, val detail: String) {
    AUTO("自动", "按设备能力给最高可用档；运行中变热或卡顿再自动降档"),
    HIGH("高", "锁定高档；设备不支持时仍会退回它能跑的最高档"),
    MEDIUM("中", "锁定中档"),
    LOW("低", "锁定低档"),
    OFF("关闭", "关闭全部玻璃与背景动效"),
    ;

    /** 这一档想锁定的 [EffectTier]；[AUTO] 没有锁定值，返回 null。 */
    fun lockedTier(): EffectTier? = when (this) {
        AUTO -> null
        HIGH -> EffectTier.HIGH
        MEDIUM -> EffectTier.MEDIUM
        LOW -> EffectTier.LOW
        OFF -> EffectTier.OFF
    }
}

/**
 * 由"用户选择 + 设备能力"算出**基准档位**（不含运行时降级）。
 *
 * 三条规则，按顺序：
 *  1. [EffectLevel.OFF] 永远是 [EffectTier.OFF] —— 用户说关就关，不看设备。
 *  2. AUTO 取设备能跑的**最高**档（[EffectTier.DESCENDING] 里第一个 [EffectTier.supportedBy]）。
 *  3. 锁定了某一档但设备跑不动时，往下退到它能跑的最高档，
 *     退到底就是 [EffectTier.LOW]（LOW 无 API 要求，永远能跑）——
 *     **不会**退化成 OFF：用户只是锁了个档位，不是要求关掉。
 */
fun resolveBaseTier(level: EffectLevel, profile: DeviceProfile): EffectTier {
    if (level == EffectLevel.OFF) return EffectTier.OFF

    val wanted = level.lockedTier()
    if (wanted != null) {
        if (wanted.supportedBy(profile)) return wanted
        // 锁的档跑不动 → 往下找同族里第一个跑得动的，但绝不越过 LOW 到 OFF。
        val fallback = EffectTier.DESCENDING
            .dropWhile { it != wanted }
            .firstOrNull { it.supportedBy(profile) && it != EffectTier.OFF }
        return fallback ?: EffectTier.LOW
    }

    // AUTO：给设备能跑的最高档。
    return EffectTier.DESCENDING.firstOrNull { it.supportedBy(profile) } ?: EffectTier.LOW
}

/**
 * 在基准档位上应用运行时降级步数。
 *
 * @param penalty 降级步数，0 表示不降。每 +1 往下降一档；降到 [EffectTier.OFF] 就停
 *                （OFF 已经是最后一级，不会绕回 HIGH）。
 */
fun EffectTier.degradedBy(penalty: Int): EffectTier {
    if (penalty <= 0) return this
    val index = EffectTier.DESCENDING.indexOf(this)
    if (index < 0) return this
    return EffectTier.DESCENDING[(index + penalty).coerceAtMost(EffectTier.DESCENDING.lastIndex)]
}

/**
 * **音乐舞台**的生效档位（2026-10-15 用户口径：与玻璃层解耦）。
 *
 * ---------------------------------------------------------------------------
 * 为什么要有这一份（[minApi] 只该卡玻璃库）
 *
 * [EffectTier] 的 `minApi` 门槛（HIGH=33 / MEDIUM=31）是照 `kyant-backdrop` 2.0.0
 * 的源码读出来的 —— 它卡的是**侧栏玻璃**（RenderEffect / RuntimeShader）。
 * 但同一个枚举同时驱动着**音乐舞台**：3D 凹凸声波圆环（`Brush.radialGradient`）
 * 与歌词的 `BlurMaskFilter` 真高斯模糊在 **API 26+ 就全部原生支持**，
 * 没有任何 API 31/33 依赖。
 *
 * 后果是误伤：小米 11 Ultra（骁龙 888 / 12GB）明明是旗舰，却因 API 30 被
 * [resolveBaseTier] 锁在 LOW，AUTO 档下音乐页的波纹特效被无辜砍掉。
 * 用户口径明确：**两台在役机器（API 30 / API 36）的音乐特效必须 100% 一致**。
 *
 * 规则：
 *   · [EffectLevel.AUTO] → 基线直接取 [EffectTier.HIGH]，只叠加运行时段数
 *     （温度/掉帧降级照旧生效 —— 那是热与卡的硬约束，不是能力误判）；
 *   · 用户手动锁定 HIGH/MEDIUM/LOW/OFF → 就用锁定值，**不受 minApi 限制**
 *     （音乐舞台跑得动；玻璃层那边仍走 [resolveBaseTier] 自己的降级，互不影响）；
 *   · 调试强制档位（override）优先级最高。
 */
fun resolveMusicTier(level: EffectLevel, penalty: Int, override: EffectTier? = null): EffectTier {
    if (override != null) return override
    val base = when (level) {
        EffectLevel.AUTO -> EffectTier.HIGH
        else -> level.lockedTier() ?: EffectTier.HIGH
    }
    return base.degradedBy(penalty)
}
