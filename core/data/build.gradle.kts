plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qingke.desk.core.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.java.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.java.get())
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(project(":core:model"))
    implementation(project(":core:common"))

    // Daylight：天气响应里带回来的日出日落要喂给它（决定地图白天/夜间底色）。
    // 依赖方向是 data → platform，单向 —— :core:platform 不认识 :core:data。
    implementation(project(":core:platform"))
}
