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

    // QQ 扫码登录的 MQTT over WebSocket（:feature:media 的 online/ 子树）。
    implementation(libs.okhttp)

    // ExoPlayer：**路线 B 的播放实现**（ExoPlayerPlaybackSource）。2026-10-14 之前它只以
    // debugImplementation 挂在 :app 上服务 Phase 0 探针；第 2 步起它是产品代码，随 main 源码集
    // 进正式构建（media3 只在 Google Maven，仓库声明里必须有 google()）。
    //
    // 只引 exoplayer 一个 artifact：AudioProcessor 的注入路径（DefaultRenderersFactory →
    // DefaultAudioSink.setAudioProcessors）在 media3-exoplayer 里就齐了，不需要 session /
    // ui / dashboard 那一串。第 3 步的 TeeAudioProcessor 同样只依赖这一个包。
    implementation(libs.androidx.media3.exoplayer)
}
