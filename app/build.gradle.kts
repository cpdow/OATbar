plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.oatbar"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.oatbar"
        minSdk = 26          // TYPE_APPLICATION_OVERLAY requires 26+
        targetSdk = 33       // keeps foreground-service rules simple on head units
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed debug signing key (committed on purpose; it protects nothing). Every build is then
    // signed identically, so a newer APK installs over an older one without uninstalling first.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party dependencies on purpose: plain Android framework only.
dependencies {
}
