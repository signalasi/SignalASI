plugins {
    id("com.android.application")
    kotlin("android")
}

val runtimeJniRoot = rootProject.file("../../build/runtime/android-jni-libs")
val runtimeAssetRoot = rootProject.file("../../build/runtime/android-assets")
val requireEmbeddedRuntime = providers.gradleProperty("signalasi.requireEmbeddedRuntime")
    .map(String::toBoolean)
    .orElse(false)

android {
    namespace = "com.signalasi.chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.signalasi.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 148
        versionName = "0.1.147"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    ndkVersion = "25.2.9519653"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        noCompress += listOf("bin", "img", "sarpack")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(runtimeJniRoot)
            assets.srcDir(runtimeAssetRoot)
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
            useLegacyPackaging = true
            // AGP's strip task can invalidate the 16 KB LOAD layout produced by patchelf.
            keepDebugSymbols += setOf("**/*.so")
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

val verifyEmbeddedRuntimeBundle = tasks.register<Exec>("verifyEmbeddedRuntimeBundle") {
    group = "verification"
    description = "Verifies the QEMU engine and bundled Linux/Python runtime packs."
    commandLine(
        "node",
        rootProject.file("../../tools/runtime/verify-android-default-runtime.mjs"),
        "--asset-root", runtimeAssetRoot,
        "--jni-root", runtimeJniRoot
    )
}

tasks.matching { task ->
    task.name == "preReleaseBuild" || (requireEmbeddedRuntime.get() && task.name == "preDebugBuild")
}.configureEach {
    dependsOn(verifyEmbeddedRuntimeBundle)
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
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
