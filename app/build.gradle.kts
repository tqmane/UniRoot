@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val appName = "Uni-Root"
val appVersionName = "5.0.0"
val dirtyfragDiagnosticOnly = providers.gradleProperty("dirtyfragDiagnosticOnly")
    .orNull.equals("true", ignoreCase = true)

android {
    if (dirtyfragDiagnosticOnly) ndkVersion = "30.0.16248370"
    namespace = "com.uniroot.app"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }
    defaultConfig {
        applicationId = "com.example.universalsystemporter"
        minSdk = 31
        targetSdk = 37
        versionCode = 300
        versionName = appVersionName
        buildConfigField("boolean", "DIRTYFRAG_DIAGNOSTIC_ONLY", dirtyfragDiagnosticOnly.toString())
        if (dirtyfragDiagnosticOnly) {
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            if (dirtyfragDiagnosticOnly) applicationIdSuffix = ".diagnostic"
        }
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    if (dirtyfragDiagnosticOnly) {
        externalNativeBuild {
            cmake {
                path = file("src/main/jni/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4-rc01")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4-rc01")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:aidl:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
