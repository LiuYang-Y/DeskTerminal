plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qingke.desk.core.ui"
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

    // 液态玻璃效果。用 api：它暴露的 LayerBackdrop 类型会出现在 :shell 的签名里。
    //
    // 必须排除 org.jetbrains.compose.* —— 这个库是 Compose Multiplatform 产物，
    // POM 里挂的坐标是 **JB Compose 桌面/JVM** 那套（ Maven Central 上只有 .jar，没有
    // Android 变体）。而它的 Android AAR（backdrop-android）是照 androidx.compose 编译的
    // （已解压 classes.jar 验证：里面全是 androidx/compose/... 引用）。
    // 也就是说：真正在跑的是 androidx 那套，JB 那套坐标只是"同名占位"，
    // 不排除就会把两套 Compose 同时拉进编译/运行时，轻则 duplicate class，重则运行期错乱。
    api(libs.kyant.backdrop) {
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.animation")
    }
}
