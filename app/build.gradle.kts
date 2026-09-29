plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reganbarua.aijuti"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reganbarua.aijuti"
        minSdk = 26
        targetSdk = 34
        // GitHub Actions-এর রান নম্বর দিয়ে প্রতি বিল্ডে নতুন ভার্সন
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // একই চাবি দিয়ে সাইন হয়, তাই নতুন APK পুরোনোটার ওপর সরাসরি আপডেট হয় (লগইন মুছে যায় না)
    signingConfigs {
        create("aijuti") {
            storeFile = file("aijuti.keystore")
            storePassword = "aijuti123"
            keyAlias = "aijuti"
            keyPassword = "aijuti123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("aijuti")
        }
        debug {
            signingConfig = signingConfigs.getByName("aijuti")
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
    implementation("androidx.webkit:webkit:1.11.0")
}
