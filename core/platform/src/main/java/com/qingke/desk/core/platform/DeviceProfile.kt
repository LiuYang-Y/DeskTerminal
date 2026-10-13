package com.qingke.desk.core.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.getSystemService
import kotlin.math.roundToInt

/**
 * 挖孔（刘海 / 打孔）的避让量。
 *
 * 单位是 **dp**，四个方向各自独立 —— 手机倒转 180° 时挖孔会翻到另一侧，
 * 所以这里**不区分"长边挖孔还是短边挖孔"**，只记"这一边要让开多少"。
 *
 * [known] 为 false 表示这台机器（或这个 API 等级）读不到挖孔信息，
 * 调用方应当**当成 0 处理**而不是当成"没有挖孔"：读不到 ≠ 没有。
 */
data class CutoutInfo(
    val leftDp: Int = 0,
    val topDp: Int = 0,
    val rightDp: Int = 0,
    val bottomDp: Int = 0,
    val known: Boolean = false,
) {
    /** 横向一共要让开多少（左侧 + 右侧）。 */
    val horizontalDp: Int get() = leftDp + rightDp

    /** 纵向一共要让开多少。 */
    val verticalDp: Int get() = topDp + bottomDp

    companion object {
        val UNKNOWN = CutoutInfo(known = false)
    }
}

/**
 * 一台设备的档案。
 *
 * ---------------------------------------------------------------------------
 * 为什么要有这个文件（2026-10-13）
 *
 * 之前全工程的尺寸都是"按某一台机器量出来的"：注释里写着 361 dp、786 dp、
 * 3200×1440，常量里写着 332 dp、380 dp。换一台机器（哪怕是同一品牌的不同型号）
 * 这些数字立刻失效——实测换机当天就靠它们现形了两个 bug（地图页右侧 257 dp 纯黑、
 * 导航页"开始导航"被顶出屏幕）。
 *
 * 这个文件的作用是把"设备能给我多少空间 / 设备跑得动什么效果"变成**运行时读出来
 * 的数据**，而不是写在注释里的常量。所有布局与效果档位都只准读这里，
 * 不准再引用任何一台具体机器的型号或分辨率。
 *
 * ---------------------------------------------------------------------------
 * 每一项怎么读、以及读不到时怎么办（**读不到一律降级，不许猜**）：
 *
 *   · [apiLevel]      `Build.VERSION.SDK_INT`，永远可读。
 *   · [widthPx]/[heightPx] **窗口范围**的像素尺寸（可用空间的**上界**，见下）。
 *   · [widthDp]/[heightDp] 上面两个值 ÷ `resources.displayMetrics.density`。
 *   · [refreshRateHz] `Display.getRefreshRate()`（API 23+）。读不到或 ≤0 → [REFRESH_RATE_UNKNOWN]。
 *                     只影响"卡顿"的判定基准，不影响布局，取不到就按 60 判。
 *   · [cutout]        API 28+ 从 `View.rootWindowInsets.displayCutout` 读（要在 View attach 之后）；
 *                     API 30+ 也可从 `WindowManager.currentWindowMetrics` 读。
 *                     两者都拿不到 → [CutoutInfo.UNKNOWN]。
 *   · [webViewVersion] `WebView.getCurrentWebViewPackage()`（API 26+）。系统没启用 WebView
 *                     实现时返回 null —— 那是真的没有，不是读不到。
 *   · [totalMemoryMb] `ActivityManager.MemoryInfo.totalMem`（API 16+）。
 *
 * 注意 [widthDp] / [heightDp] 是**应用内容区**的尺寸：系统栏与挖孔都已经扣掉了
 * （理由与实测数据见 [readContentSize]）。所以布局层**不要再减一次挖孔** ——
 * 那是这个类已经做过的事。
 *
 * ---------------------------------------------------------------------------
 * 尺寸为什么不用 `Configuration.screenWidthDp`（2026-10-13 真机实测，别改回去）
 *
 * 第一版就是用它，结果在基准机（小米 11 Ultra，物理 1440×3200 @560dpi）上报出
 * **875 × 356 dp**，而 Compose 真正能画的范围是 **875 × 395 dp**（= `uiautomator dump`
 * 的内容根节点 `[0,0][3063,1384]`）。差的 39 dp 是状态栏 —— 沉浸式下它已经隐藏、
 * 空间也已经还给我们了，`Configuration` 却仍然扣着它。
 *
 * 第二版改成直接读 `WindowMetrics.bounds`，报 **914 × 411 dp** —— 又**多报**了一圈：
 * 那是显示尺寸，不含底部 16 dp 的手势区与左侧 39 dp 的挖孔。多报的后果不是"看着挤"，
 * 而是"排到画不到的地方"：`wm size` 模拟 800×360 dp 时侧栏按 360 dp 均分 5 项，
 * 而实际只有 344 dp，第 5 项「手机投屏」被屏幕底边切掉一截（`step 6` 截图实证）。
 *
 * 第二版改成读 `WindowMetrics.bounds`，报 **914 × 411 dp** —— 那是**窗口范围**，
 * 而 Compose 真正能画的是 **875 × 395 dp**（少掉的 39 dp 是挖孔、16 dp 是底部手势条）。
 * 多报的后果不是"看着挤"，而是"排到画不到的地方"：`wm size` 模拟 800×360 dp 时，
 * 侧栏按 360 dp 均分 5 项，实际只有 344 dp，第 5 项「手机投屏」被屏幕底边切掉一截。
 *
 * 现在 [widthDp]/[heightDp] 的定位是**上界**（除 [readContentSize] 里的两个来源外不做减法），
 * 需要"真实可用高度"的地方用 `BoxWithConstraints` 量容器 —— 只有量出来的才不会错。
 */
data class DeviceProfile(
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
    /** 内容区像素宽（已扣系统栏与挖孔）。调试页会显示它，用来核对 dp 换算。 */
    val widthPx: Int,
    /** 内容区像素高（已扣系统栏与挖孔）。 */
    val heightPx: Int,
    /** 内容区宽（dp），= [widthPx] ÷ 密度。 */
    val widthDp: Int,
    /** 内容区高（dp），= [heightPx] ÷ 密度。 */
    val heightDp: Int,
    val densityDpi: Int,
    val refreshRateHz: Float,
    val cutout: CutoutInfo,
    val webViewPackage: String?,
    val webViewVersion: String?,
    val totalMemoryMb: Long,
) {

    /**
     * 设备指纹：**只**由"换机就会变"的字段组成。
     *
     * 用它做设置的持久化 key（见 `ShellSettings.effectLevel`），这样
     * "在这台机器上选的档位"不会跟着换机跑到另一台尺寸完全不同的机器上 ——
     * 换机后默认回到 AUTO，由新机器的能力自己决定。
     *
     * 刻意**不含** [widthDp] / [heightDp]：用 `wm size` 改分辨率会变，
     * 但那不是换机，不该把用户的档位选择冲掉。
     * 也刻意不含 [refreshRateHz]：它会随刷新率设置变动，同样不是换机。
     */
    val fingerprint: String
        get() = "api$apiLevel/$manufacturer/$model/mem${totalMemoryMb / 512}"

    /** 调试页用的一行摘要。 */
    val shortLabel: String
        get() = "$manufacturer $model (API $apiLevel)"

    /** 屏幕是不是"矮"到需要砍掉侧栏文字标签（见 [SidebarFit]）。 */
    val isShort: Boolean get() = heightDp < COMPACT_HEIGHT_DP

    /** 屏幕是不是"窄"到浮层要收缩。 */
    val isNarrow: Boolean get() = widthDp < COMPACT_WIDTH_DP

    companion object {

        /** 读不到刷新率时的兜底。按 60 判卡顿，宁可判宽也不判严。 */
        const val REFRESH_RATE_UNKNOWN: Float = 0f

        /** 判"矮屏"的阈值：低于这个高度，侧栏 5 项 × 64dp 放不下。 */
        const val COMPACT_HEIGHT_DP: Int = 380

        /** 判"窄屏"的阈值：低于这个宽度，设置浮层的固定宽度会碰到侧栏。 */
        const val COMPACT_WIDTH_DP: Int = 700

        /** 没有任何信息时的兜底档案。只在拿不到 Context 的预览里用，真机不会走到。 */
        val FALLBACK = DeviceProfile(
            apiLevel = Build.VERSION.SDK_INT,
            manufacturer = "?",
            model = "?",
            widthPx = 0,
            heightPx = 0,
            widthDp = 0,
            heightDp = 0,
            densityDpi = 0,
            refreshRateHz = REFRESH_RATE_UNKNOWN,
            cutout = CutoutInfo.UNKNOWN,
            webViewPackage = null,
            webViewVersion = null,
            totalMemoryMb = 0,
        )

        /**
         * 读出当前设备的档案。
         *
         * @param view 一个**已经 attach 到窗口**的 View（通常是 `window.decorView`）。
         *             给了它才能在 API 28/29 上读到挖孔；为 null 时 API 30 以下读不到挖孔。
         *             传 null 不会崩，只是 [cutout] 变成 [CutoutInfo.UNKNOWN]。
         */
        fun read(context: Context, view: View? = null): DeviceProfile {
            val configuration = context.resources.configuration
            val density = context.resources.displayMetrics.density

            val size = readContentSize(context, density)
            val widthPx = size?.first ?: 0
            val heightPx = size?.second ?: 0

            return DeviceProfile(
                apiLevel = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER ?: "?",
                model = Build.MODEL ?: "?",
                widthPx = widthPx,
                heightPx = heightPx,
                widthDp = if (widthPx > 0 && density > 0f) {
                    (widthPx / density).roundToInt()
                } else {
                    configuration.screenWidthDp
                },
                heightDp = if (heightPx > 0 && density > 0f) {
                    (heightPx / density).roundToInt()
                } else {
                    configuration.screenHeightDp
                },
                densityDpi = configuration.densityDpi,
                refreshRateHz = context.readRefreshRate(),
                cutout = readCutout(context, view, configuration.densityDpi),
                webViewPackage = readWebView()?.packageName,
                webViewVersion = readWebView()?.versionName,
                totalMemoryMb = readTotalMemoryMb(context),
            )
        }

        /**
         * 应用**内容区**的像素尺寸。读不到返回 null（由调用方退回 Configuration）。
         *
         * 取 `currentWindowMetrics.bounds`（API 30+）：这是**窗口范围**，也就是
         * "这台设备最多能给你多少"，不含含糊 —— 但它**不是**"能画多少"。
         *
         * 两者在基准机上差多少（`uiautomator dump` 的内容根节点 `[0,0][3063,1384]`）：
         * 左边少 137 px = 39 dp（挖孔），底部少 56 px = 16 dp（手势条）。
         * 也就是说沉浸式下系统仍然留了两条不还给你，而**这两条的大小随 ROM 变**
         * （见下面"为什么不用 inset 去减"），所以本类不猜它，交给布局量：
         * 需要"真实可用高度"的地方用 `BoxWithConstraints` 量自己的容器。
         *
         * ~~第 2 步用哪种 inset~~ —— **试过，放弃**（2026-10-13，过程留着当证据）：
         *
         *   | 取法 | 基准机结果 | 与内容根节点（3063×1384 px）比 |
         *   |---|---|---|
         *   | `bounds` 原样 | 914 × 411 dp | 多报（把画不到的一圈算进来了）|
         *   | `systemBars`（ignoring visibility）| 875 × 356 dp | 少报：把整条**已隐藏**的导航栏也扣了 |
         *   | `mandatorySystemGestures` | 875 × 356 dp | 同上 —— 这台机器上两者报的是同一个数 |
         *
         * 更糟的是：一旦用 `wm size` 改了屏幕（`794×361` 那组），插图**全部变成 0**，
         * 于是"可用宽高"又变回整屏 —— 同一个 API 在同一个应用里给出两套互相矛盾的答案。
         * 插图的来源是不可靠的，**不要再往这个方向调**。
         *
         * 所以现在只做一件事：给 **窗口范围** 这个不含糊的数，并在文档里写明它是
         * **可用空间的上界**；真正能画多少，由布局用 `BoxWithConstraints` 自己量
         * （侧栏就是这么修的，见 `LensSideBar` 的 `availableHeightDp` 参数）。
         *
         * API 26–29 没有 `WindowMetrics`：退回 `Display.getRealSize()`（屏幕尺寸，
         * 与窗口尺寸在沉浸式全屏下一致）—— 同样是上界。
         */
        private fun readContentSize(context: Context, density: Float): Pair<Int, Int>? {
            if (density <= 0f) return null
            val windowManager = context.getSystemService<WindowManager>()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager?.currentWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return bounds.width() to bounds.height()
                }
            }

            // API 26–29：拿屏幕尺寸代替（沉浸式全屏下与窗口一致）。
            @Suppress("DEPRECATION")
            val display = windowManager?.defaultDisplay
            if (display != null) {
                val point = android.graphics.Point()
                val ok = runCatching {
                    @Suppress("DEPRECATION")
                    display.getRealSize(point)
                }.isSuccess
                if (ok && point.x > 0 && point.y > 0) return point.x to point.y
            }

            val configuration = context.resources.configuration
            val w = configuration.screenWidthDp
            val h = configuration.screenHeightDp
            if (w <= 0 || h <= 0) return null
            return (w * density).roundToInt() to (h * density).roundToInt()
        }

        private fun Context.readRefreshRate(): Float {
            // API 23 之前没有 getRefreshRate；API 30 之前要用 defaultDisplay。
            val display: Display? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                getSystemService<WindowManager>()?.defaultDisplay
            } else {
                @Suppress("DEPRECATION")
                getSystemService<WindowManager>()?.defaultDisplay
            }
            val rate = display?.refreshRate ?: REFRESH_RATE_UNKNOWN
            // 有些 ROM 会报 0 或 NaN。0 用作"未知"的哨兵，所以这里统一归零。
            return if (rate.isFinite() && rate > 0f) rate else REFRESH_RATE_UNKNOWN
        }

        private fun readCutout(context: Context, view: View?, densityDpi: Int): CutoutInfo {
            if (densityDpi <= 0) return CutoutInfo.UNKNOWN

            val insets = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // 优先走 View：API 28 起 rootWindowInsets 就有 displayCutout，
                // 且它是**窗口实际生效**的那份，比 WindowMetrics 更贴近真实排版。
                view?.rootWindowInsets?.displayCutout
                    ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.getSystemService<WindowManager>()
                            ?.currentWindowMetrics
                            ?.windowInsets
                            ?.displayCutout
                    } else {
                        null
                    }
            } else {
                null
            } ?: return CutoutInfo.UNKNOWN

            val scale = DisplayMetrics.DENSITY_DEFAULT.toFloat() / densityDpi
            return CutoutInfo(
                leftDp = (insets.safeInsetLeft * scale).roundToInt(),
                topDp = (insets.safeInsetTop * scale).roundToInt(),
                rightDp = (insets.safeInsetRight * scale).roundToInt(),
                bottomDp = (insets.safeInsetBottom * scale).roundToInt(),
                known = true,
            )
        }

        private fun readWebView(): android.content.pm.PackageInfo? = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.webkit.WebView.getCurrentWebViewPackage()
            } else {
                null
            }
        }.getOrNull()

        private fun readTotalMemoryMb(context: Context): Long {
            val manager = context.getSystemService<ActivityManager>() ?: return 0
            val info = ActivityManager.MemoryInfo()
            return runCatching {
                manager.getMemoryInfo(info)
                info.totalMem / (1024 * 1024)
            }.getOrDefault(0)
        }
    }
}
