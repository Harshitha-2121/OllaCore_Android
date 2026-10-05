import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Optional WebSocket signaling server for 1-to-1 WebRTC calls (android/signaling).
// Empty (default) = feature off, calls use the existing backend path.
// Configure via gradle property -PsignalingUrl=ws://host:port or in local.properties:
//   signalingUrl=ws://10.0.2.2:8080      (emulator -> host PC)
//   signalingUrl=ws://127.0.0.1:8080     (device via `adb reverse tcp:8080 tcp:8080`)
val signalingUrl: String = run {
    val fromProp = project.findProperty("signalingUrl") as String?
    val fromLocal = run {
        val f = rootProject.file("local.properties")
        if (f.exists()) {
            val props = Properties()
            f.inputStream().use { props.load(it) }
            props.getProperty("signalingUrl")
        } else null
    }
    (fromProp ?: fromLocal ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "com.ollacore.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ollacore.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // SIM_TOKEN only for androidTest via InstrumentationRegistry.getArguments()
        // Set in ~/.gradle/gradle.properties: android.testInstrumentationRunnerArguments.SIM_TOKEN=...
        // Never put SIM_TOKEN in main source, APK, or repo
        testInstrumentationRunnerArguments["SIM_URL"] = (project.findProperty("SIM_URL") as String? ?: "")
        testInstrumentationRunnerArguments["SIM_TOKEN"] = (project.findProperty("SIM_TOKEN") as String? ?: "")
        testInstrumentationRunnerArguments["APP_ID"] = (project.findProperty("APP_ID") as String? ?: "")
        buildConfigField("String", "SIGNALING_URL", "\"$signalingUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.espresso.core)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.firebase.messaging)
    implementation(libs.webrtc)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // UiDeepTest needs compose UI-test APIs, aligned to the same BOM (no new versions invented).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.androidx.ui.tooling)
}
