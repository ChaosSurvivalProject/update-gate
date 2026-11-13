plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

android {
    namespace = "com.updategate.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.updategate.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 142
        versionName = "2.3.0"
        // 多 ABI 拆分：每个 ABI 产出独立 APK（与 manifest assets.abi 对应）
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // 按 ABI 拆分 APK（release 产物如 app-arm64-v8a-release.apk）
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "2.0.21"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")

    // 生命周期 / 前台生命周期感知
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    // WorkManager（约束：NetworkType.CONNECTED、非计量网络可配）
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // 网络（OkHttp）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 序列化（manifest 解析）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 前台服务通知
    implementation("androidx.core:core:1.13.1")

    testImplementation("junit:junit:4.13.2")
}
