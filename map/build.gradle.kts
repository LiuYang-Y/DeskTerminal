import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 高德 Key 从 local.properties 读，**不入库**。
 *
 * 为什么走 manifestPlaceholders 而不是写死在 AndroidManifest 里：
 * 高德要求 1 个 Key 绑 1 个应用（包名 + 调试/发布两个 SHA1），
 * 写死进仓库等于把 Key 连同"绑定关系"一起公开，谁都拿得到。
 *
 * 申请时在控制台（https://lbs.amap.com/dev/key/app）填三样：
 *   · Package name           → com.qingke.desk
 *   · 调试版安全码 SHA1      → keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
 *   · 发布版安全码 SHA1      → 用你自己的 release keystore 上同一条命令
 * 注意 package 必须与 app/build.gradle.kts 的 applicationId **完全一致**，
 * 不一致时高德返回 INVALID_USER_SCODE（官方文档明确列出这个坑）。
 */
val amapApiKey: String = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } }.getProperty("amap.api.key") }
    .orEmpty()

if (amapApiKey.isBlank()) {
    logger.warn(
        "[map] local.properties 里没有 amap.api.key —— 编译不会失败，" +
            "但装到机器上地图一定白屏。Key 申请方式见 map/build.gradle.kts 顶部注释。",
    )
}

android {
    namespace = "com.qingke.desk.map"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        // 高德 SDK 只提供 armeabi-v7a / arm64-v8a 两个 ABI 的 so，
        // 不裁的话合并阶段会因为缺 x86 变体报错（模拟器上跑不了，真机没问题）。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        manifestPlaceholders["amapApiKey"] = amapApiKey
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 混淆规则给下游模块用：高德的类几乎全靠反射/so 回调，混淆掉就是白屏或崩溃。
    defaultConfig.consumerProguardFiles("proguard-rules.pro")
}

dependencies {
    // 只留真正用到的：这个模块不画自家的设计语言（那是 :core:ui 的事），
    // 也不依赖任何业务模型 —— 它的输出就是一块地图画面，越少耦合越容易挪位置。
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    // 运行时权限申请（定位小蓝点）要用 rememberLauncherForActivityResult
    implementation(libs.androidx.activity.compose)
    // LocalLifecycleOwner：把 MapView 的生命周期接到宿主上
    implementation(libs.androidx.lifecycle.runtime.compose)

    // 高德地图 SDK。
    //
    // 坐标与版本已核实（2026-10-11 查 Maven Central 与官方文档）：
    //   · 官方 Android Studio 配置文档给的写法是
    //       implementation 'com.amap.api:3dmap-location-search:latest.integration'
    //     并明确"3D地图 so 及 jar 已经包含定位和搜索功能，无需单独引用"。
    //   · Maven Central 上这个 artifact 的最新固定版本是
    //       10.1.300_loc6.4.9_sea9.7.4（10.1.300 地图 / 6.4.9 定位 / 9.7.4 搜索，
    //       发布于 2025-05-26），共 7 个版本。
    //   · 注意官方"相关下载"页（2026-09-24 更新）列的是 3D地图 V11.3.100，
    //     那是**离线 SDK 包**的版本号，Maven 上并没有 11.x。要用 11.x 只能下 zip
    //     手动放进 libs；这里仍然选 Maven 版，理由是可复现、不需要往仓库里塞二进制。
    //   · 这个 artifact 是 jar 不是 aar：so 在 lib/<abi>/、地图资源在 assets/map_assets/、
    //     classes 在 com/ 下。已解压确认（.verify/amap/），合并进 APK 的情况在构建后另验。
    implementation(libs.amap.map)
}
