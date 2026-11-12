plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "tw.bktu.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "tw.bktu.reader"
        minSdk = 21
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("reader.keystore")
            storePassword = "reader123"
            keyAlias = "reader"
            keyPassword = "reader123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    // 刻意不用 AppCompat 等大型函式庫：App 小、啟動快
}
