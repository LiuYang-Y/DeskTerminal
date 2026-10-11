// 注意：本模块不写 Compose，因此不应用 org.jetbrains.kotlin.plugin.compose。
// 一旦应用了该插件而 classpath 上没有 Compose Runtime，编译会直接失败：
// "The Compose Compiler requires the Compose Runtime to be on the class path".
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.qingke.desk.connection"
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

    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:platform"))
    implementation(project(":projection:api"))
}
