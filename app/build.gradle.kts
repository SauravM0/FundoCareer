import java.io.File

// ------------------------------------------------------------------
// Local config loader: reads key=value pairs from project-root/.env
// and backend/.env (backend file takes priority over root).
// Priority: env var > backend/.env > root/.env > built-in default
// ------------------------------------------------------------------
fun loadDotEnv(projectRoot: File): Map<String, String> {
    val props = mutableMapOf<String, String>()
    listOf(File(projectRoot, ".env"), File(projectRoot, "backend/.env")).forEach { envFile ->
        if (envFile.exists()) {
            envFile.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank() && !trimmed.startsWith("#")) {
                    val parts = trimmed.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim().removeSurrounding("'").removeSurrounding("\"")
                        props[key] = value
                    }
                }
            }
        }
    }
    return props
}

val dotEnv = loadDotEnv(project.projectDir.parentFile)

fun resolveConfig(envName: String, dotEnvKey: String, fallback: String): String =
    System.getenv(envName) ?: dotEnv[dotEnvKey] ?: fallback

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fundocareer.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fundocareer.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "2.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    signingConfigs {
        create("debugKey") {
            storeFile = file(System.getenv("ANDROID_DEBUG_KEYSTORE") ?: (System.getProperty("user.home") + "/.android/debug.keystore"))
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debugKey")
            // URL resolution priority (debug only):
            //   1. Environment variable (FUNDO_DEBUG_API_URL / FUNDO_DEBUG_WEB_URL)
            //   2. project-root/.env   (ANDROID_API_URL / ANDROID_FRONTEND_URL)
            //   3. Built-in defaults below
            val debugApiUrl = resolveConfig("FUNDO_DEBUG_API_URL", "ANDROID_API_URL", "http://10.0.2.2:5000")
            val debugWebUrl = resolveConfig("FUNDO_DEBUG_WEB_URL", "ANDROID_FRONTEND_URL", "https://www.fundocareer.com")
            buildConfigField("String", "API_BASE_URL", "\"$debugApiUrl\"")
            buildConfigField("String", "FRONTEND_URL", "\"$debugWebUrl\"")
            buildConfigField("String", "ENV_NAME", "\"debug\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debugKey")
            buildConfigField("String", "API_BASE_URL", "\"https://www.fundocareer.com\"")
            buildConfigField("String", "FRONTEND_URL", "\"https://www.fundocareer.com\"")
            buildConfigField("String", "ENV_NAME", "\"production\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.material)
    implementation(libs.play.services.auth)

    implementation(libs.capacitor.android)
    implementation(libs.capacitor.splash.screen)
    implementation(libs.capacitor.app)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.androidx.work.runtime.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
