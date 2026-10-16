plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.din131.aniview"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.din131.aniview"
        minSdk = 26
        targetSdk = 35
        // Naik otomatis tiap build di GitHub Actions supaya APK baru bisa di-install di atas yang lama
        // Nomor versi dari workflow (lihat VERSION_OFFSET di .github/workflows/build.yml)
        val appVersion = (System.getenv("APP_VERSION") ?: "1").toInt()
        versionCode = appVersion
        versionName = "1.0.$appVersion"
    }

    signingConfigs {
        create("release") {
            // Kunci tetap supaya update bisa di-install tanpa uninstall
            storeFile = rootProject.file("keystore/aniview.jks")
            storePassword = "aniview123"
            keyAlias = "aniview"
            keyPassword = "aniview123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
}
