// AGP 9 起内置 Kotlin，默认把 Kotlin Gradle 插件（KGP）锁在 2.2.10。
// 版本目录里写 kotlin = "2.4.x" 只作用于 org.jetbrains.kotlin.plugin.compose，
// 不会改变实际编译器版本。要真正升级 KGP，只能在这里用 buildscript classpath 向上覆盖。
// 注意：buildscript 块内读不到 version catalog，版本号必须手写，并与
// gradle/libs.versions.toml 中的 kotlin 保持一致。若要回退到 AGP 内置的 2.2.10，
// 删掉下面整个 buildscript 块即可。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
