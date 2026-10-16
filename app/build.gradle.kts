plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qingke.desk"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.qingke.desk"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"

        // 多 DEX：minSdk=26 由 AGP/D8 自动分包（本工程 20 个 classes*.dex）。显式开着是为
        // 防止将来有人以为"单 dex 够用"去掉它 —— 主 dex 已 ~65K method，单 dex 直接溢出。
        // ⚠️ 启动崩 NoClassDefFoundError 曾被误判为"ColorOS 次级 DEX 懒加载"（错），真因是
        // **增量 dex 缓存损坏丢了 class_def**：clean + assembleDebug --no-build-cache 才修好，
        // 见 .workbuddy/memory/2026-10-15.md。别再加任何 DEX 预热/延时轮询。
        multiDexEnabled = true

        // androidTest 用：Compose UI 测试要在**进程内**注入手势。
        // 这台基准机是 production 构建，`adb shell input` 一律被拒
        // （INJECT_EVENTS permission），所以"地图卡上横滑能不能翻页""点地图卡能不能
        // 进全屏页"这两条只能这样验 —— Compose 测试走自己的命中测试与事件分发，
        // 不经过系统 InputManager。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // lifecycleScope：效果档位的采样协程挂在 Activity 生命周期上
    // （见 MainActivity 的 governor.start()/stop()）。
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Phase 0（路线 B）原生播放探针：**只进 debug 变体**。
    // 探针类住在 src/debug/，它的 Activity 也只在 src/debug/AndroidManifest.xml 里声明 ——
    // 所以 release APK 里既没有这个类，也不会解析到 media3（依赖同样只挂在 debug 上）。
    // 这是本项目「探针代码不进正式构建」那条硬约束的落地方式。
    debugImplementation(libs.androidx.media3.exoplayer)

    implementation(project(":shell"))
    implementation(project(":feature:home"))
    // DeskApplication.onCreate 里要尽早调 QqLog.init（文件日志 sink）。QqLog 住在 :feature:media，
    // 而 :shell 是用 implementation（非 api）引它的 —— 传递不可见，故这里必须显式声明。
    implementation(project(":feature:media"))
    implementation(project(":connection"))
    implementation(project(":projection:api"))
    implementation(project(":projection:carplay"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:model"))
    implementation(project(":core:platform"))
    implementation(project(":core:common"))
    implementation(project(":projection:auto"))
    implementation(project(":hosting"))
    // 高德地图 SDK 的封装。**只提供地图画面**，摆在哪里由 :shell 决定 ——
    // 见 map/README 式的说明在各文件头注释里（尤其是 AMapCanvas 的三条约束）。
    implementation(project(":map"))

    // ---- 仪器化测试（androidTest）：真实手势验证 ---------------------------------
    //
    // 只给测试用，不进 APK。ui-test-junit4 提供 performTouchInput / performClick，
    // ui-test-manifest 给测试进程提供一个空 Activity 的清单（createAndroidComposeRule
    // 不带自己的 Activity 时需要）。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
