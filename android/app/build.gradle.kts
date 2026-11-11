plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.multicuenta.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.multicuenta.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // Same key on every CI build, so a new APK can update the installed one.
    // CI passes it via secrets; without them Gradle falls back to a throwaway debug key.
    val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_FILE")
    signingConfigs {
        if (keystorePath != null) {
            create("shared") {
                storeFile = file(keystorePath)
                storeType = "pkcs12"
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = "multicuenta"
                keyPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("shared")
        }
        // The build to install: not debuggable (no run-as access to the session data, no remote
        // WebView debugging), and R8-optimized
        release {
            if (keystorePath != null) signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Profile API: one isolated cookie/storage profile per account (WebViewFeature.MULTI_PROFILE)
    implementation("androidx.webkit:webkit:1.12.1")
}
