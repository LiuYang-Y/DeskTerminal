plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qingke.desk.feature.media"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    buildFeatures {
        compose = true
    }

    // 音乐页的布局推导（MusicLayout）与歌词解析（LyricsParser / LrcTextNormalizer）
    // 都是**纯函数**，最适合用 JVM 单元测试盯着 —— 尤其"歌词块与 HUD 不重叠"这条
    // 不变式：它只在这两台机器上靠眼睛验过，换一台尺寸不同的机器就会重新变成未知。
    // 打开 returnDefaultValues 是因为 LyricsParser 里会调 android.util.Log
    // （单元测试下变成返回 0 的空实现，而不是抛 "not mocked"）。
    testOptions {
        unitTests.isReturnDefaultValues = true
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
    // rememberLauncherForActivityResult：本地歌词导入走 SAF 文件选择器。
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)

    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    // 效果档位（EffectTier）：音乐页的背景动效分四档，读的是 LocalEffectTier。
    // 那个 CompositionLocal 的类型是 :core:platform 的 EffectTier，
    // 而 :core:ui 是用 implementation 引它的（类型不传递），所以这里要显式声明。
    implementation(project(":core:platform"))

    // 歌词解析（纯 JVM/KMP）。理由与已核实的坑见 gradle/libs.versions.toml。
    implementation(libs.accompanist.lyrics.core)

    // OkHttp：**只为 WebSocket**。QQ 扫码登录的 qq 通道是 MQTT over WebSocket
    // （wss://mu.y.qq.com/ws/handshake，已实测标准握手且不校验 Origin），
    // 而 Android 的 java.net.http.WebSocket 要 API 34 —— 本项目 minSdk 26。
    // musicu 的普通 POST 也顺带用它，但那一块用 HttpURLConnection 也可以。
    implementation(libs.okhttp)
}
