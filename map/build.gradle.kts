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
        //
        // 换导航合包后这两个 ABI 的体积差被拉得很大，值得记一笔：
        //   libAMapSDK_NAVI_v11_3_100.so  arm64-v8a 约 68 MB / armeabi-v7a 约 47 MB，
        // 外加语音包（libnui / libneonui_shared）与 openssl。两个 ABI 全留 = APK 里
        // 装着近 130 MB 的 so，而基准机 PKR110 是 arm64。
        // 这里**先不动**：当前阶段的目标是"换包后行为不变"，裁剪 ABI 属于另一个
        // 独立变更（要么只留 arm64-v8a，要么交给 App Bundle 按 ABI 拆包），
        // 混在一起会让"回归通过/不通过"的结论说不清。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }

        manifestPlaceholders["amapApiKey"] = amapApiKey

        // 同一个值再导出一份到 BuildConfig：运行时要用它判断"Key 到底配没配"。
        // 没配的时候与其让用户盯着一块白屏猜，不如直接把原因写在地图上。
        // 值本身不是秘密（它会随 APK 一起发出去），秘密是"绑定关系"，
        // 而绑定关系由包名 + SHA1 决定，不在这个字符串里。
        buildConfigField("String", "AMAP_API_KEY", "\"${amapApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
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

    // 昼夜判定（白天/夜间地图样式）。它自己不联网，
    // 但留了"天气 API 的 sunrise/sunset 注入后优先"这条路。
    implementation(project(":core:platform"))

    // 高德 SDK —— **导航合包**（2026-10-12 由纯地图包整体替换而来）。
    //
    // 换包原因：要接导航，而导航 SDK 自带 3D 地图 SDK，与 3dmap-location-search
    // 同时引入会 duplicate class。官方"相关下载"页原文把导航包列为
    // 「定位 + 3D地图 + 导航 + 搜索」，所以这是**整体替换**，不是新增。
    //
    // 已核实（2026-10-12 查 repo1.maven.org 的 maven-metadata.xml）：
    //   com.amap.api:navi-3dmap-location-search
    //   11.3.100_3dmap11.3.100_loc11.3.000_sea9.8.1（2026-09-23 发布，共 14 个版本）
    //   命名 = 导航版本_3dmap地图版本_loc定位版本_sea搜索版本。
    //   packaging = jar，so 在 lib/<abi>/、地图资源在 assets/map_assets/、
    //   classes 在 com/ 下（已解压确认，见 .verify/navi/）。
    //
    // 对上层**零改动**是这次换包能这么快的前提：合包里
    //   · com/amap/api/maps/** 仍在（AMap / MapView / MapsInitializer 都在），
    //     所以 AMapCanvas 的地图代码与 AMapPrivacy 的合规闸门一行没改；
    //   · com/amap/api/navi/** 是新增能力，还没被任何代码引用。
    // 换包后真机回归结论（地图显示 / 隐私同意与撤回 / 日夜切换 / 浮层展开收起 /
    // 侧栏左右切换）见本次提交说明与 .verify/navi-regress/。
    implementation(libs.amap.navi)
}
