import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.mikepenz.aboutlibraries.plugin") version "11.2.3"
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

android {
    namespace = "sh.bentley.transponder"
    compileSdk {
        version = release(36)
    }

    signingConfigs {
        create("release") {
            storeFile = System.getenv("KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEYSTORE_PASSWORD") // PKCS12 uses same password
        }
    }

    defaultConfig {
        applicationId = "sh.bentley.transponder"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "0.8.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAPTILER_API_KEY", "\"${localProperties.getProperty("MAPTILER_API_KEY", "")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "USE_MOCK_FRIENDS", "true")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("Boolean", "USE_MOCK_FRIENDS", "false")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.mikepenz:aboutlibraries-core:11.2.3")
    // CameraX for QR scanning (Apache 2.0)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // WorkManager for background sync
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Rust cross-compilation for Android using cargo-ndk
// Only build arm64-v8a by default for faster builds; set BUILD_ALL_ABIS=true for release
val buildAllAbis = System.getenv("BUILD_ALL_ABIS")?.toBoolean() ?: false
val targetAbis = if (buildAllAbis) {
    listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
} else {
    listOf("arm64-v8a")
}

tasks.register("buildRustLibrary") {
    description = "Build Rust transponder-core library for Android"

    val crateDir = rootProject.projectDir.parentFile.resolve("core")
    val jniLibsDir = projectDir.resolve("src/main/jniLibs")

    inputs.dir(crateDir.resolve("src"))
    inputs.file(crateDir.resolve("Cargo.toml"))
    outputs.dir(jniLibsDir)

    doLast {
        println("Building transponder-core for Android targets: $targetAbis")

        exec {
            workingDir = rootProject.projectDir.parentFile
            val targetArgs = targetAbis.flatMap { listOf("-t", it) }
            commandLine(
                listOf("cargo", "ndk") + targetArgs +
                listOf("-o", jniLibsDir.absolutePath, "build", "--release", "-p", "transponder-core")
            )
        }

        println("Built native libraries in $jniLibsDir")
    }
}

tasks.register("generateBindings") {
    description = "Generate Kotlin bindings from Rust transponder-core"

    val crateDir = rootProject.projectDir.parentFile.resolve("core")
    val bindingsDir = projectDir.resolve("src/main/java")

    inputs.dir(crateDir.resolve("src"))
    inputs.file(crateDir.resolve("Cargo.toml"))
    outputs.file(bindingsDir.resolve("uniffi/transponder_core/transponder_core.kt"))

    doLast {
        println("Building host library for binding generation...")

        // Build release library for host to generate bindings from
        exec {
            workingDir = rootProject.projectDir.parentFile
            commandLine("cargo", "build", "--release", "-p", "transponder-core")
        }

        println("Generating Kotlin bindings...")

        // Detect library extension based on host OS
        val libExt = when {
            System.getProperty("os.name").lowercase().contains("mac") -> "dylib"
            System.getProperty("os.name").lowercase().contains("win") -> "dll"
            else -> "so"
        }

        exec {
            workingDir = rootProject.projectDir.parentFile
            commandLine(
                "cargo", "run",
                "--manifest-path", "core/Cargo.toml",
                "--bin", "uniffi-bindgen",
                "generate",
                "--library", "target/release/libtransponder_core.$libExt",
                "--language", "kotlin",
                "--out-dir", "android/app/src/main/java"
            )
        }

        println("Kotlin bindings generated at $bindingsDir/uniffi/transponder_core/")
    }
}

tasks.named("preBuild") {
    dependsOn("buildRustLibrary", "generateBindings")
}