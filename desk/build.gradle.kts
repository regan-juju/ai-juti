plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.reganbarua.claudedesk"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.reganbarua.claudedesk"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        create("aijuti") {
            storeFile = file("../app/aijuti.keystore")
            storePassword = "aijuti123"
            keyAlias = "aijuti"
            keyPassword = "aijuti123"
        }
    }

    buildTypes {
        release {
            // অপ্রয়োজনীয় কোড ছেঁটে অ্যাপ ছোট ও দ্রুত রাখা হয়
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
