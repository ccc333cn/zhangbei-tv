import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
}
// AGP 9 内置 Kotlin，不再 apply org.jetbrains.kotlin.android。

android {
    namespace = "com.ayou.elderplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ayou.elderplayer"
        // 安卓 5.0 起。老电视（TCL 等）多是安卓 5～8
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 电视基本都是 ARM；x86 只在 Intel 模拟器上用得到，不打包，省 20MB。
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 家用自签：直接用 debug 签名，装机方便。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // standard：带「当桌面」入口（默认关着，见 README）；
    // nohome：去掉这个入口。有的 TCL 固件只让 TCL 自己签名的 App 声明 HOME，别人的直接拒装（报「证书不一致」）。
    flavorDimensions += "home"
    productFlavors {
        create("standard") {
            dimension = "home"
            isDefault = true
        }
        create("nohome") {
            dimension = "home"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    lint {
        abortOnError = false
    }
}

// 打出来的 APK 叫 zhangbeitv-standard-release.apk、zhangbeitv-nohome-release.apk 等
base {
    archivesName.set("zhangbeitv")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.nextlib.media3ext)

    testImplementation(libs.junit)
}
