package com.qingke.desk

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.qingke.desk.core.data.ShellSettings
import com.qingke.desk.core.model.DisplayInfo
import com.qingke.desk.core.platform.DeviceProfile
import com.qingke.desk.core.platform.DisplayController
import com.qingke.desk.core.platform.EffectGovernor
import com.qingke.desk.core.platform.WindowDisplayController
import com.qingke.desk.core.ui.LocalDeviceProfile
import com.qingke.desk.core.ui.LocalEffectTier
import com.qingke.desk.shell.DeskRoot

/**
 * HOME Activity。单 Activity + 全 Compose。
 *
 * 四件事：把窗口交给常亮开关、读设备档案、开效果档位的运行时裁决、把根交给 :shell。
 * 业务逻辑一律不在这里。
 *
 * 2026-10-12：常亮不再是"onCreate 里无条件开"，而是由 :shell 把设置里的当前值
 * 回调过来（[DeskRoot] 的 onKeepScreenOnChange），用户在设置浮层里一改就生效。
 * 屏保与窗口亮度调整整个删掉了 —— **本应用不碰用户的亮度**。
 *
 * 2026-10-13：**设备档案与效果档位从这里下发**（自适应框架）。
 * 之前全工程的尺寸都是"按某一台机器量出来的"，换机就崩。现在设备信息在运行时读，
 * 通过 [LocalDeviceProfile] / [LocalEffectTier] 下发，所有布局与效果只读这两个 Local。
 */
class MainActivity : ComponentActivity() {

    private lateinit var displayController: DisplayController

    /**
     * 当前设备档案。用 Compose 的 state 持有而不是 lateinit var：
     * 它会在"窗口 attach 之后"（那时才拿得到挖孔）与"配置变更时"各更新一次，
     * 每次都要让整棵树重组。
     */
    private var profile by mutableStateOf(DeviceProfile.FALLBACK)

    private lateinit var governor: EffectGovernor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayController = WindowDisplayController(window)

        enterDeskImmersiveMode()

        DeskGraph.displayInfo = readDisplayInfo()

        profile = DeviceProfile.read(this)

        governor = EffectGovernor(this, profile, lifecycleScope).apply {
            // 用户在这台机器上选的等级（按设备指纹存，换机自动回 AUTO）。
            // 注意 `profile` 必须显式限定：这里是 `apply {}` 作用域，
            // 裸 `profile` 会优先解析到 governor 自己的 StateFlow<DeviceProfile>。
            setLevel(ShellSettings.effectLevel(this@MainActivity, this@MainActivity.profile))
            // 调试页设的强制档位；没设过就是 null。
            setOverride(ShellSettings.forcedEffectTier(this@MainActivity))
        }

        // 挖孔要等 View attach 之后才读得到（API 28/29 只有 `rootWindowInsets` 这一条路）。
        // onCreate 里 decorView 还没挂上，post 一次补读。
        window.decorView.post {
            if (!isDestroyed) {
                profile = DeviceProfile.read(this, window.decorView)
                governor.updateProfile(profile)
            }
        }

        // HOME 应用不应该被返回键退出，否则一按就掉回系统桌面。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            val tier by governor.tier.collectAsStateWithLifecycle()
            val level by governor.level.collectAsStateWithLifecycle()

            CompositionLocalProvider(
                LocalDeviceProfile provides profile,
                LocalEffectTier provides tier,
            ) {
                DeskRoot(
                    orchestrator = DeskGraph.orchestrator,
                    repository = DeskGraph.homeRepository,
                    displayInfo = DeskGraph.displayInfo,
                    onMessage = ::showMessage,
                    // 常亮的**当前值**由 :shell 从 ShellSettings 读出后在这里回调一次，
                    // 之后每次用户在设置浮层里改开关都会再回调一次 —— 改完立刻生效。
                    // 不在 onCreate 里写死 keepScreenOn(true)：那样设置里的开关就只是个摆设。
                    onKeepScreenOnChange = displayController::keepScreenOn,
                    effectLevel = level,
                    effectTier = tier,
                    effectStatusLine = governor.statusLine(),
                    onEffectLevelChange = { next ->
                        governor.setLevel(
                            ShellSettings.setEffectLevel(this@MainActivity, profile, next),
                        )
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 采样只在可见时跑：屏幕灭了还每 6 秒唤醒一次 Choreographer 是白耗电，
        // 而且采样不到帧会把"没数据"误当成"要等"（measureFrameBurst 返回 null 会跳过，
        // 但仍然有一次 post 的开销）。
        governor.start()
    }

    override fun onStop() {
        governor.stop()
        super.onStop()
    }

    /**
     * 配置变更。
     *
     * manifest 里声明了 `configChanges=orientation|screenSize|...|density`，
     * 所以**这个 Activity 不会被重建**，改分辨率（`wm size` / `wm density`）、
     * 转屏、换语言都只会回调到这里。设备档案必须在这里重读 —— 不重读的话
     * `wm size` 之后布局还按旧尺寸算，等于测了个寂寞。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val next = DeviceProfile.read(this, window.decorView)
        profile = next
        governor.updateProfile(next)
        DeskGraph.displayInfo = readDisplayInfo()
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * 沉浸式全屏。桌面终端摆桌上，状态栏和导航栏是纯干扰，也没人会去下拉通知栏。
     *
     * 挖孔位置**不在这里读死**：早期注释写着"基准机横屏时左侧有 137px 挖孔"，
     * 那是某一台机器的事实，换一台（或同一台倒转 180°）就不成立。
     * 现在统一由 [DeviceProfile.cutout] 在运行时读，
     * 这里只负责让窗口延伸到挖孔下面（`shortEdges`），具体避让交给 Compose 侧。
     */
    private fun enterDeskImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        // API 29+ 系统默认会在透明的系统栏后面补一层对比度遮罩，
        // 效果是屏幕底部多出一条看不出边界的暗带。桌面终端不需要它。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /**
     * 读取屏幕尺寸。
     *
     * 因为上面开了沉浸式 + shortEdges，这里取的就是**整屏物理尺寸**，不再扣系统栏
     * （早期注释写"已按 Xiaomi M2102K1C 的 3200x1440@560dpi 校正"，那是一台机器的
     * 数字，换机即失效；现在哪一档都不假设，一律读运行时值）。
     * 内容避让改由 Compose 侧的 displayCutout 插图负责，两件事分开。
     */
    private fun readDisplayInfo(): DisplayInfo {
        val measured = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }

        val configuration: Configuration = resources.configuration
        val night = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

        // 诊断用：onCreate 时窗口可能还没转成横屏，这条日志能看出实际拿到的是什么。
        // 注意：投屏 Surface 尺寸**不**依赖这里的值（见 ProjectionScreen）。
        android.util.Log.i(
            "DeskDisplay",
            "displayInfo ${measured.first}x${measured.second}px, " +
                "densityDpi=${configuration.densityDpi}, night=$night",
        )

        return DisplayInfo(
            widthPx = measured.first,
            heightPx = measured.second,
            densityDpi = configuration.densityDpi,
            nightMode = night,
        )
    }
}
