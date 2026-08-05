plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "de.coonabibba.bikeyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.coonabibba.bikeyboard"
        // 28 is the floor for InputMethodService#switchToNextInputMethod, which
        // the globe key needs. Raise it if the design doc calls for newer APIs.
        minSdk = 28
        targetSdk = 35
        // Monotonic across CI builds so a newer APK is an upgrade rather than a
        // reinstall. Local builds stay at 1; scripts/install.sh passes -d so a
        // local build can still replace a CI one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Checked in and shared by every build, local and CI alike. Android
        // refuses to update an installed app whose signature differs, and the
        // per-runner keystore AGP generates by default differs every time —
        // which is what forced an uninstall between builds.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // Suffix so a CI build can live next to a locally built copy.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
