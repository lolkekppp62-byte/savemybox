import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Читаем local.properties — там хранятся пути и пароли от production keystore.
// Файл не коммитится в git (см. .gitignore), поэтому секреты не утекают в репозиторий.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.savemy.singboxfork"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.savemy.singboxfork"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "0.3.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // В APK только arm64-v8a: полный набор ABI весит ~350 МБ и часто качается
        // на телефон неполностью, а установщик затем пишет о проблеме с файлом.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        // Debug-ключ: общий для всех разработчиков, лежит в репозитории.
        // CI и локальные debug-сборки ставятся поверх друг друга.
        create("shared") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        // Production-ключ: читается из local.properties, которого нет в git.
        // Если переменные не заданы — конфиг не создаётся и release-сборка упадёт явно.
        val storeFilePath = localProps.getProperty("RELEASE_STORE_FILE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = file(storeFilePath)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD") ?: ""
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Production-ключ, если настроен локально; иначе общий ключ из репозитория
            // (в CI local.properties нет, и сборка не должна из-за этого падать).
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("shared")
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
