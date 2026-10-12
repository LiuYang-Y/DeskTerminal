package com.qingke.desk

import android.content.Context
import com.qingke.desk.connection.ConnectionOrchestrator
import com.qingke.desk.connection.ProjectionSessionRegistryImpl
import com.qingke.desk.core.data.HomeRepository
import com.qingke.desk.core.data.LocalHomeRepository
import com.qingke.desk.core.data.OpenMeteoWeatherRepository
import com.qingke.desk.core.model.DisplayInfo
import com.qingke.desk.projection.api.ProjectionSessionFactory
import com.qingke.desk.projection.api.ProjectionSessionRegistry
import com.qingke.desk.projection.auto.AndroidAutoSessionFactory
import com.qingke.desk.projection.carplay.ExternalDiPlaySessionFactory
import com.qingke.desk.projection.carplay.MockCarPlaySessionFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * DI 根。放在 :app —— 只有它能同时看到所有 module，
 * 放 :core:common 会让 core 与 connection 互相依赖。
 *
 * 目前是手写的对象图，不引入 Hilt/KSP，省一层版本耦合。
 * 等模块数再多、需要按 feature 注入时再迁到 Hilt，迁移只需改本文件。
 */
object DeskGraph {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var appContext: Context

    /** 由 DeskApplication 在 onCreate 里调用。拿 applicationContext，不持有 Activity。 */
    fun bind(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    /**
     * 首页卡片数据源：时钟读系统时间（东八区），天气走 Open-Meteo（西安，非商业用途）。
     *
     * 不需要 [appContext] —— 天气只用 `HttpURLConnection` + 平台自带的 JSON 解析，
     * 没有 SDK 初始化那一套，所以也就不必 lazy。
     */
    val homeRepository: HomeRepository = LocalHomeRepository(OpenMeteoWeatherRepository())

    /**
     * 同一 kind 有多个实现时，**列表顺序就是优先级**：registry.find() 取第一个同 kind 的。
     *
     * 阶段一把外部 DiPlay 排在最前：真机有 DiPlay 就走真线，没有就显示"未安装"提示页。
     * 想回到纯自绘的自测形态（不开 DiPlay 也能跑），把 MockCarPlaySessionFactory 挪到第一行。
     */
    private val projectionFactories: List<ProjectionSessionFactory> by lazy {
        listOf(
            ExternalDiPlaySessionFactory(appContext),
            MockCarPlaySessionFactory(), // 自绘假画面：Surface 链路 / 逃生手势自测用
            AndroidAutoSessionFactory,   // 预留点位，checkAvailability() 恒 Unavailable
        )
    }

    val registry: ProjectionSessionRegistry by lazy {
        ProjectionSessionRegistryImpl(projectionFactories)
    }

    /**
     * 运行期屏幕信息，由 MainActivity 在 attach 后写入。
     * TODO(adb): 改成一次性写入真机值后，把这里的默认值删掉。
     */
    @Volatile
    var displayInfo: DisplayInfo = DisplayInfo.Unspecified

    val orchestrator: ConnectionOrchestrator by lazy {
        ConnectionOrchestrator(
            registry = registry,
            displayInfoProvider = { displayInfo },
            scope = scope,
        )
    }
}
