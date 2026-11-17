plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.dichmanhinh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.dichmanhinh.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "3.0.0"

        // Giảm dung lượng: chỉ giữ giao diện vi/en và 2 kiến trúc phổ biến của điện thoại.
        resourceConfigurations += listOf("vi", "en")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ký bằng khóa debug để cài được ngay. Muốn phát hành thật: thay bằng keystore riêng.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    lint { abortOnError = false }
    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/DEPENDENCIES", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // OCR ĐÓNG GÓI SẴN (chạy ngoại tuyến ngay): chữ Latin (Anh, Việt, …) và chữ Hán (Trung).
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // OCR TẢI THÊM KHI CẦN qua Google Play Services (không nằm trong APK -> nhẹ hơn nhiều):
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-japanese:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-korean:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")
    implementation("com.google.android.gms:play-services-base:18.5.0")

    // Dịch ngoại tuyến (gói ngôn ngữ tải theo yêu cầu) + nhận diện ngôn ngữ
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")

    // Viết tay (mô hình tải theo ngôn ngữ khi dùng lần đầu)
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")

    // Camera dịch trực tiếp
    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")
}
