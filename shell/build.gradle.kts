plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.qingke.desk.shell"
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
        // 只为一件事：ValidationDriver 要靠 BuildConfig.DEBUG 门控
        // （release 包里那个广播接收器根本不注册）。
        buildConfig = true
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

    implementation(project(":core:ui"))
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:platform"))
    implementation(project(":connection"))
    implementation(project(":projection:api"))
    implementation(project(":feature:home"))
    implementation(project(":feature:media"))
    // 托管页（:hosting）由壳层直接渲染到底栏之上的主区域，所以 :shell 要能看到它。
    implementation(project(":hosting"))
    // :map 提供地图布局里铺满整屏的那一层（高德 MapView 挂在 Compose 里）。
    // 它取代了"用 :hosting 的虚拟屏把高德 App 整个搬过来"的旧做法，
    // :hosting 保留不删（虚拟屏能力本身还有别的用处）。
    implementation(project(":map"))
}
