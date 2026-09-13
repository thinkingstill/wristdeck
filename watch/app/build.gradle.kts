plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.wristdeck"
    // androidx.core 1.15+ 要求 compileSdk >= 35；targetSdk 仍保持 30 与设备对齐
    compileSdk = 36

    defaultConfig {
        applicationId = "io.wristdeck"
        minSdk = 30
        targetSdk = 30
        versionCode = 6
        versionName = "0.2.4"

        // 设备仅支持 armeabi-v7a，避免引入 64 位原生库
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
