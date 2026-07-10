plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.signalasi.chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.signalasi.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            excludes += setOf(
                "**/libsignal_jni_testing.so"
            )
        }
        resources {
            excludes += setOf(
                "**/*.dll",
                "**/*.dylib",
                "**/*testing*.dll",
                "**/*testing*.dylib"
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.signal:libsignal-android:0.86.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("xyz.rementia:openwakeword:0.1.3") {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
        exclude(group = "org.apache.commons", module = "commons-math3")
    }
    implementation(files("libs/onnxruntime-android-1.18.0.aar"))
    implementation(files("libs/commons-math3-3.6.1.jar"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
}
