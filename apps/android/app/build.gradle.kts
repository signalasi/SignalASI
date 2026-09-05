import java.security.MessageDigest
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.android.application")
    kotlin("android")
}

val runtimeJniRoot = rootProject.file("../../build/runtime/android-jni-libs")
val runtimeAssetRoot = providers.gradleProperty("galaxyssi.runtimeAssetRoot")
    .map(rootProject::file)
    .orElse(rootProject.file("../../build/runtime/android-assets"))
    .get()
val qnnCompatJniRoot = layout.buildDirectory.dir("generated/qnn-compat-jni")
val androidNdkVersion = "29.0.13113456"
val androidNdkHostTag = when {
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-x86_64"
    System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin-x86_64"
    else -> "linux-x86_64"
}
val requireEmbeddedRuntime = providers.gradleProperty("galaxyssi.requireEmbeddedRuntime")
    .map(String::toBoolean)
    .orElse(true)
val realtimeAsrCredentialBrokerUrl = providers.gradleProperty("galaxyssi.realtimeAsrCredentialBrokerUrl")
    .orElse("")
val bundledWhisperAsset = file("src/main/assets/ggml-tiny.bin")
val bundledWhisperAssets = fileTree("src/main/assets") {
    include("ggml-*.bin")
}
val whisperNativeSourceRoot = file("src/main/cpp")
val whisperNativeBuildFingerprint = MessageDigest.getInstance("SHA-256").let { digest ->
    listOf(
        "CMakeLists.txt",
        "WHISPER_CPP_VERSION.md",
        "whisper_jni_v2.cpp",
        "whispercpp/src/whisper.cpp",
        "whispercpp/ggml/src/ggml.c"
    ).map(whisperNativeSourceRoot::resolve).forEach { source ->
        check(source.isFile) { "Whisper native fingerprint source is missing: $source" }
        digest.update(source.relativeTo(whisperNativeSourceRoot).invariantSeparatorsPath.toByteArray(Charsets.UTF_8))
        source.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
    }
    digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
}
val bundledWhisperVerification = tasks.register("verifyBundledWhisperModel") {
    inputs.files(bundledWhisperAssets)
    val receipt = layout.buildDirectory.file("verification/whisper-tiny.sha256")
    outputs.file(receipt)
    doLast {
        val expectedSize = 77_691_713L
        val expectedSha256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        check(bundledWhisperAsset.isFile) { "Bundled Whisper Tiny model is missing" }
        check(bundledWhisperAsset.length() == expectedSize) {
            "Bundled Whisper Tiny size does not match the pinned catalog"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        bundledWhisperAsset.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte: Byte -> "%02x".format(byte) }
        check(actual == expectedSha256) { "Bundled Whisper Tiny SHA-256 does not match the pinned catalog" }
        val unexpected = file("src/main/assets").listFiles().orEmpty()
            .filter { it.name.startsWith("ggml-") && it.name.endsWith(".bin") && it.name != bundledWhisperAsset.name }
        check(unexpected.isEmpty()) {
            "Only Whisper Tiny may be bundled; found ${unexpected.joinToString { it.name }}"
        }
        receipt.get().asFile.apply {
            parentFile.mkdirs()
            writeText(actual)
        }
    }
}

val whisperCpuBackendIsolationVerification = tasks.register("verifyWhisperCpuBackendIsolation") {
    val nativeEntryPoint = file("src/main/cpp/whisper_jni_v2.cpp")
    val nativeBuild = file("src/main/cpp/CMakeLists.txt")
    inputs.files(nativeEntryPoint, nativeBuild)
    doLast {
        val source = nativeEntryPoint.readText()
        check("ggml_backend_load_all" !in source && "dladdr(" !in source) {
            "The CPU Whisper runtime must not scan APK native libraries; device-specific backends use dedicated runtimes"
        }
        check(Regex("foreach\\(GGML_TARGET\\s+ggml\\s+ggml-base\\s+ggml-cpu\\)").containsMatchIn(nativeBuild.readText())) {
            "The Android CPU Whisper tensor kernels must retain release-grade compiler optimization"
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(bundledWhisperVerification, whisperCpuBackendIsolationVerification)
}

val stageCurrentNdkSharedRuntime = tasks.register<Copy>("stageCurrentNdkSharedRuntime") {
    val ndkSharedRuntime = file(
        "${android.sdkDirectory}/ndk/$androidNdkVersion/toolchains/llvm/prebuilt/$androidNdkHostTag/" +
            "sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
    )
    from(ndkSharedRuntime)
    into(qnnCompatJniRoot.map { it.dir("arm64-v8a") })
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(stageCurrentNdkSharedRuntime)
}

tasks.matching { task ->
    task.name.startsWith("merge") && task.name.endsWith("NativeLibs")
}.configureEach {
    doLast {
        val nativeOutputs = outputs.files.files.filter { it.exists() }
        check(nativeOutputs.isNotEmpty()) { "Android native merge produced no auditable output" }
        providers.exec {
            commandLine(
                listOf(
                    "node",
                    rootProject.file("../../tools/dev/normalize-android-native-page-size.mjs").absolutePath
                ) + nativeOutputs.map { it.absolutePath }
            )
        }.result.get().assertNormalExitValue()
    }
}

android {
    namespace = "com.galaxyssi.chat"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.galaxyssi.chat"
        minSdk = 26
        targetSdk = 34
        versionCode = 862
        versionName = "1.0.16"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "WHISPER_NATIVE_VERSION", "\"v1.9.1-f049fff95a08\"")
        buildConfigField("String", "WHISPER_NATIVE_BUILD_FINGERPRINT", "\"$whisperNativeBuildFingerprint\"")
        buildConfigField("String", "QNN_RUNTIME_VERSION", "\"2.47.0\"")
        buildConfigField(
            "String",
            "REALTIME_ASR_CREDENTIAL_BROKER_URL",
            "\"${realtimeAsrCredentialBrokerUrl.get().replace("\\", "\\\\").replace("\"", "\\\"")}\""
        )

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17")
            }
        }
    }

    ndkVersion = androidNdkVersion
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    androidResources {
        noCompress += listOf("bin", "img", "onnx", "sarpack")
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(runtimeJniRoot)
            jniLibs.srcDir(qnnCompatJniRoot)
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

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("boolean", "SENSITIVE_DIAGNOSTICS_ENABLED", "true")
        }
        getByName("release") {
            isDebuggable = false
            buildConfigField("boolean", "SENSITIVE_DIAGNOSTICS_ENABLED", "false")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // AGP's strip task can invalidate the 16 KB LOAD layout produced by patchelf.
            keepDebugSymbols += setOf("**/*.so")
            excludes += setOf(
                "**/libsignal_jni_testing.so"
            )
            pickFirsts += setOf(
                "**/libomp.so",
                "**/libQnnHtp.so",
                "**/libQnnHtpPrepare.so",
                "**/libQnnHtpV79Skel.so",
                "**/libQnnHtpV79Stub.so",
                "**/libQnnHtpV81Skel.so",
                "**/libQnnHtpV81Stub.so",
                "**/libQnnSystem.so"
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
    task.name == "preReleaseBuild" || (requireEmbeddedRuntime.get() && task.name == "packageDebug")
}.configureEach {
    dependsOn(verifyEmbeddedRuntimeBundle)
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showExceptions = true
        showCauses = true
        showStackTraces = true
    }
}

dependencies {
    implementation(project(":llama-runtime"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.signal:libsignal-android:0.86.5")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.6.0.202603022253-r")
    implementation("org.jsoup:jsoup:1.23.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("xyz.rementia:openwakeword:0.1.3") {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
        exclude(group = "org.apache.commons", module = "commons-math3")
    }
    implementation(files("libs/onnxruntime-android-1.24.3.aar"))
    implementation(files("libs/commons-math3-3.6.1.jar"))
    implementation("com.qualcomm.qti:qnn-runtime:2.47.0")
    implementation("com.qualcomm.qti:onnxruntime-android-qnn:2.3.0")
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.47.0")
    implementation("com.qualcomm.qti:geniex-android:0.3.18")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("androidx.documentfile:documentfile:1.1.0")
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
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
