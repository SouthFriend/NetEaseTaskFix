plugins {
    id("com.android.application")
}

android {
    namespace = "com.neteasetaskfix"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.neteasetaskfix"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    // Xposed API - compileOnly，不打包进 APK，由 LSPosed 运行时提供
    compileOnly("de.robv.android.xposed:api:82")
}
