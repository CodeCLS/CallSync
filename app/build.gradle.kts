import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Secrets live in local.properties (gitignored) or environment variables.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String, default: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: default

android {
    namespace = "com.callsync.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.callsync.app"
        minSdk = 34          // FOREGROUND_SERVICE_MICROPHONE requires API 34; target is Android 15+ (API 35)
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // ---- Keys read from local.properties / env (see README notes). ----
        // Deepgram prerecorded endpoint; swap for a local Whisper URL if desired.
        // nova-3 + language=multi transcribes mixed German/English accurately and
        // handles code-switching within a call (far better than nova-2's single
        // detect_language guess).
        buildConfigField("String", "TRANSCRIBE_URL",
            "\"${secret("TRANSCRIBE_URL", "https://api.deepgram.com/v1/listen?model=nova-3&language=multi&smart_format=true&punctuate=true")}\"")
        buildConfigField("String", "TRANSCRIBE_API_KEY", "\"${secret("TRANSCRIBE_API_KEY")}\"")
        buildConfigField("String", "NOTION_TOKEN", "\"${secret("NOTION_TOKEN")}\"")
        buildConfigField("String", "NOTION_DATABASE_ID", "\"${secret("NOTION_DATABASE_ID")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.hiddenapibypass)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
