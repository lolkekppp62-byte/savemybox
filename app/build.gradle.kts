plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
android {
    namespace = "com.savemy.singboxfork"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.savemy.singboxfork"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "0.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // В APK только arm64-v8a: полный набор ABI весит ~350 МБ и часто качается
        // на телефон неполностью, а установщик затем пишет о проблеме с файлом.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Общий ключ для всех сборок, включая CI. Без него Gradle создаёт на каждом раннере
    // новый debug-ключ, и очередной APK не ставится поверх предыдущего ("Не удалось
    // установить приложение"). Ключ дебажный, не для публикации в магазинах.
    signingConfigs {
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        // Выпуск собирается тем же ключом, что и отладочная сборка: обновление ставится
        // поверх, а в APK нет флага debuggable — на него Play Protect жалуется заметно реже.
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlinOptions {
    jvmTarget = "17"
}
    packaging { jniLibs.useLegacyPackaging = false }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(files("libs/libbox.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // QR-код подписки: камера через готовый CaptureActivity и разбор изображения из галереи.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
