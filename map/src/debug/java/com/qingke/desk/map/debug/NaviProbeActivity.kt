package com.qingke.desk.map.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amap.api.navi.AMapNavi
import com.amap.api.navi.NaviSetting
import com.amap.api.navi.SimpleNaviListener
import com.amap.api.navi.enums.PathPlanningErrCode
import com.amap.api.navi.enums.PathPlanningStrategy
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.NaviLatLng
import com.qingke.desk.map.AMapPrivacy
import com.qingke.desk.map.MapLog

/**
 * 导航探针页（**仅 debug 变体**，正式构建里不存在）。
 *
 * 存在理由只有一个：**回答"这把 Key 到底有没有导航权限"**。
 * 官方文档没写"导航要不要在控制台单独开通"，用户口径是不猜、直接在真机上试一次
 * 路线规划，把 SDK 返回的错误原文拿去控制台处理。
 *
 * 所以这个页面刻意**不做界面、不做交互、不接 AmapNaviPage**，
 * 只做「合规闸门 → 取 AMapNavi 实例 → 算一条自驾路线 → 把回调原样打出来」。
 * 成败都显示在屏幕上（本机 `adb logcat` 恒为空，只能靠看屏幕 + 落盘日志）。
 *
 * 用法：
 *   adb shell am start -n com.qingke.desk/com.qingke.desk.map.debug.NaviProbeActivity
 * 可选参数（默认就是钟楼 → 咸阳机场）：
 *   --ef slat 34.2616 --ef slng 108.9455 --ef elat 34.4471 --ef elng 108.7516
 */
class NaviProbeActivity : ComponentActivity() {

    private val lines = mutableStateListOf<String>()
    private var navi: AMapNavi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sLat = intent.getDoubleExtra(EXTRA_SLAT, 34.2616)
        val sLng = intent.getDoubleExtra(EXTRA_SLNG, 108.9455)
        val eLat = intent.getDoubleExtra(EXTRA_ELAT, 34.4471)
        val eLng = intent.getDoubleExtra(EXTRA_ELNG, 108.7516)

        setContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0B0D12))
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                lines.forEach {
                    Text(
                        text = it,
                        color = if (it.startsWith("!")) Color(0xFFFF7A7A) else Color(0xFFD7DCE5),
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        probe(sLat, sLng, eLat, eLng)
    }

    private fun say(text: String) {
        lines.add(text)
        MapLog.i("naviprobe: $text")
    }

    private fun probe(sLat: Double, sLng: Double, eLat: Double, eLng: Double) {
        say("NaviProbe @ ${System.currentTimeMillis()}")
        say("key.configured=${com.qingke.desk.map.BuildConfig.AMAP_API_KEY.isNotBlank()}")

        // ---- 1. 合规闸门 --------------------------------------------------
        // 官方原文："必须在 AMapNavi.getInstance(context) 实例化之前调用"。
        // 这里读的是应用里那份同意状态，不无脑写 true —— 探针也不该绕过用户的选择。
        val agreed = AMapPrivacy.isAgreed(this)
        say("privacy.agreed=$agreed")
        if (!agreed) {
            say("! 未同意隐私政策，探针停止。先在应用设置里同意，或 --ez 走一遍应用。")
            return
        }
        runCatching {
            NaviSetting.updatePrivacyShow(this, true, true)
            NaviSetting.updatePrivacyAgree(this, true)
        }.onFailure { say("! NaviSetting 调用异常 ${it.javaClass.simpleName}: ${it.message}") }
        say("privacy pushed")

        // ---- 2. 取 AMapNavi 实例 ------------------------------------------
        // 官方要求捕获异常并做空判断 —— 这里正是要拿"取不到"这件事当结论用。
        val instance = try {
            AMapNavi.getInstance(applicationContext)
        } catch (e: Exception) {
            say("! AMapNavi.getInstance 抛异常 ${e.javaClass.simpleName}: ${e.message}")
            null
        }
        if (instance == null) {
            say("! 实例为 null，无法继续。")
            return
        }
        navi = instance
        say("AMapNavi instance OK")

        instance.setUseInnerVoice(true)
        instance.addAMapNaviListener(listener)

        // ---- 3. 算一条真实路线 --------------------------------------------
        val start = listOf(NaviLatLng(sLat, sLng))
        val end = listOf(NaviLatLng(eLat, eLng))
        say("route $sLat,$sLng -> $eLat,$eLng")
        val accepted = try {
            instance.calculateDriveRoute(
                start,
                end,
                emptyList(),
                PathPlanningStrategy.DRIVING_DEFAULT,
            )
        } catch (e: Exception) {
            say("! calculateDriveRoute 抛异常 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        say("calculateDriveRoute accepted=$accepted")
        if (!accepted) {
            say("! 调用即被拒绝（同步返回 false）。")
        }

        // 兜底：15 秒内一个回调都没来，也别让人干等着。
        window.decorView.postDelayed({
            if (!gotCallback) {
                say("! 15s 内没有任何计算路线回调 —— 多半卡在网络或鉴权。")
            }
        }, 15_000L)
    }

    private var gotCallback = false

    /**
     * 用 [SimpleNaviListener] 而不是直接实现 `AMapNaviListener`：
     * 后者有 36 个方法，这里只关心其中几个。`SimpleNaviListener` 是高德自带的空实现
     * （`MyNaviListener extends AMapNaviListener`），继承它是最省事且不遗漏的实现方式。
     */
    private val listener = object : SimpleNaviListener() {

        override fun onInitNaviSuccess() {
            gotCallback = true
            say("onInitNaviSuccess")
        }

        override fun onInitNaviFailure() {
            gotCallback = true
            say("! onInitNaviFailure（导航初始化失败，通常就是鉴权/配额）")
        }

        override fun onCalculateRouteSuccess(result: AMapCalcRouteResult) {
            gotCallback = true
            val ids = result.routeid?.joinToString(",") ?: "-"
            say("onCalculateRouteSuccess code=${result.errorCode} routeIds=[$ids]")
            // 能把路线拿出来 = Key 有导航权限，这一步的结论就是"能用"。
            // getNaviPaths() 是 HashMap<routeId, AMapNaviPath>，要取 values。
            val path = navi?.naviPaths?.values?.firstOrNull()
            if (path != null) {
                say("path: ${path.allLength}m / ${path.allTime}s / lights=${path.lightList?.size}")
                say(">>> 结论：Key 具备导航路线规划能力。")
            } else {
                say(">>> 成功回调已到，但 naviPaths 为空（不常见）。")
            }
        }

        override fun onCalculateRouteFailure(result: AMapCalcRouteResult) {
            gotCallback = true
            say("! onCalculateRouteFailure code=${result.errorCode}")
            say("!   name=${errorName(result.errorCode)}")
            say("!   desc=${result.errorDescription}")
            say("!   detail=${result.errorDetail}")
        }

        // 下面三个是 SDK 标了 @Deprecated 的旧版回调（11.x 主要走带结果对象的重载）。
        // 故意保留：万一这个版本仍然只走旧回调，漏了它就等于"没有任何结论"。
        // 抑制的只是"覆盖已废弃成员"这条告警本身，不是废弃行为。
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCalculateRouteFailure(errorCode: Int) {
            gotCallback = true
            say("! onCalculateRouteFailure(int) code=$errorCode name=${errorName(errorCode)}")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCalculateRouteSuccess(ints: IntArray?) {
            gotCallback = true
            say("onCalculateRouteSuccess(int[]) ids=${ints?.joinToString(",") ?: "-"}")
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onGetNavigationText(text: String?) {
            MapLog.i("naviprobe: tts=$text")
        }
    }

    /**
     * 把错误码翻译成常量名。
     *
     * 不硬编码数字：直接引用 SDK 的常量去比对，SDK 改值也不会错。
     * 这些名字本身就是给用户拿去搜索/对照控制台用的（官方错误码表按名字列）。
     */
    private fun errorName(code: Int): String {
        val table = listOf(
            "INVALID_USER_KEY" to PathPlanningErrCode.INVALID_USER_KEY,
            "INVALID_USER_SCODE" to PathPlanningErrCode.INVALID_USER_SCODE,
            "USERKEY_PLAT_NOMATCH" to PathPlanningErrCode.USERKEY_PLAT_NOMATCH,
            "INSUFFICIENT_PRIVILEGES" to PathPlanningErrCode.INSUFFICIENT_PRIVILEGES,
            "OUT_OF_SERVICE" to PathPlanningErrCode.OUT_OF_SERVICE,
            "OVER_QUOTA" to PathPlanningErrCode.OVER_QUOTA,
            "ACCESS_TOO_FREQUENT" to PathPlanningErrCode.ACCESS_TOO_FREQUENT,
            "SERVICE_NOT_EXIST" to PathPlanningErrCode.SERVICE_NOT_EXIST,
            "SERVICE_RESPONSE_ERROR" to PathPlanningErrCode.SERVICE_RESPONSE_ERROR,
            "ERROR_CONNECTION" to PathPlanningErrCode.ERROR_CONNECTION,
            "ERROR_STARTPOINT" to PathPlanningErrCode.ERROR_STARTPOINT,
            "ERROR_ENDPOINT" to PathPlanningErrCode.ERROR_ENDPOINT,
            "ERROR_NOROADFORSTARTPOINT" to PathPlanningErrCode.ERROR_NOROADFORSTARTPOINT,
            "ERROR_NOROADFORENDPOINT" to PathPlanningErrCode.ERROR_NOROADFORENDPOINT,
            "INVALID_PARAMS" to PathPlanningErrCode.INVALID_PARAMS,
            "MISMATCH_WITH_NAVIGATION" to PathPlanningErrCode.MISMATCH_WITH_NAVIGATION,
            "NEWER_PLANNING_TASK_WORKING" to PathPlanningErrCode.NEWER_PLANNING_TASK_WORKING,
            "UNKNOWN_ERROR" to PathPlanningErrCode.UNKNOWN_ERROR,
        )
        return table.firstOrNull { it.second == code }?.first ?: "未在 PathPlanningErrCode 里找到"
    }

    override fun onDestroy() {
        super.onDestroy()
        // 资源必须显式释放：AMapNavi 是进程级单例，不 destroy 下次进来会拿到半死状态。
        // 注意 destroy() 在 11.3.100 里是**静态方法**（javap 核实：public static
        // synchronized void destroy()），不是实例方法，写成 navi?.destroy() 编译不过。
        runCatching {
            navi?.removeAMapNaviListener(listener)
            navi?.stopNavi()
            AMapNavi.destroy()
        }
        MapLog.i("naviprobe: destroyed")
    }

    private companion object {
        const val EXTRA_SLAT = "slat"
        const val EXTRA_SLNG = "slng"
        const val EXTRA_ELAT = "elat"
        const val EXTRA_ELNG = "elng"
    }
}
