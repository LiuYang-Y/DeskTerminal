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
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(project(":shell"))
    implementation(project(":feature:home"))
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
