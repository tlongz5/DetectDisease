// Repo nay chi chua thu muc src cua app (main/, test/, androidTest/),
// nen file build tro thang vao cac thu muc do thay vi cau truc app/src/main mac dinh.
plugins {
    id("com.android.application") version "8.13.1"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

android {
    namespace = "com.example.detectplantdiase"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.detectplantdiase"
        minSdk = 28  // ImageDecoder can Android 9 tro len
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Chi dong goi ONNX Runtime cho CPU dien thoai that: APK ~63 MB thay vi ~165 MB.
        // Muon chay tren may ao (emulator) thi them "x86_64" vao danh sach.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("main/AndroidManifest.xml")
            java.srcDirs("main/java")
            res.srcDirs("main/res")
            assets.srcDirs("main/assets")
        }
        getByName("test") { java.srcDirs("test/java") }
        getByName("androidTest") { java.srcDirs("androidTest/java") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
