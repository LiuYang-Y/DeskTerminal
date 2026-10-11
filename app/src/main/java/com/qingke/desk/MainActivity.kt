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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.qingke.desk.core.model.DisplayInfo
import com.qingke.desk.core.platform.BurnInGuard
import com.qingke.desk.core.platform.DisplayController
import com.qingke.desk.core.platform.WindowDisplayController
import com.qingke.desk.shell.DeskRoot
import java.util.Calendar

/**
 * HOME Activity。单 Activity + 全 Compose。
 *
 * 三件事：常亮、读取屏幕信息、把根交给 :shell。
 * 业务逻辑一律不在这里。
 */
class MainActivity : ComponentActivity() {

    private lateinit var displayController: DisplayController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        displayController = WindowDisplayController(window)
        displayController.keepScreenOn(true)

        enterDeskImmersiveMode()

        DeskGraph.displayInfo = readDisplayInfo()

        // HOME 应用不应该被返回键退出，否则一按就掉回系统桌面。
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })

        setContent {
            DeskRoot(
                orchestrator = DeskGraph.orchestrator,
                repository = DeskGraph.homeRepository,
                displayInfo = DeskGraph.displayInfo,
                onMessage = ::showMessage,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 分时段亮度：每次回前台按当前小时重算一次。
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        displayController.setWindowBrightness(BurnInGuard.scheduledBrightness(hour))
    }

    private fun showMessage(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    /**
     * 沉浸式全屏。桌面终端摆桌上，状态栏和导航栏是纯干扰，也没人会去下拉通知栏。
     *
     * 基准机横屏时左侧有 137px 挖孔（ITYPE_LEFT_DISPLAY_CUTOUT）。
     * 用 shortEdges 让窗口延伸到挖孔下面：底色铺满不出现黑带，
     * 具体内容再由 :shell 里的 WindowInsets.displayCutout 插图挡开。
     *
     * 系统栏用 transient 模式 —— 用户从边缘上滑仍能临时唤出，留了条后路。
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
     * 已按基准机（Xiaomi M2102K1C，横屏 3200 x 1440 @ 560dpi）校正：
     * 因为上面开了沉浸式 + shortEdges，这里取的就是**整屏物理尺寸**，
     * 不再扣系统栏 —— 扣了反而算小，投屏分辨率上限会被无谓压低。
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
