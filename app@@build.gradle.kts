plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}
android {
    namespace = "com.amir.ai14"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.amir.ai14"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "3.0.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    signingConfigs {
        create("fixed") {
            storeFile = file("ai14.keystore")
            storeType = "pkcs12"
            storePassword = "ai14ai14"
            keyAlias = "ai14"
            keyPassword = "ai14ai14"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
        getByName("release") { signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
chaquopy {
    defaultConfig {
        version = "3.12"
        pip {
            install("pillow")
            install("numpy")
        }
    }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core:1.13.1")
}
