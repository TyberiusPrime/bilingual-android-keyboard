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
        // local build can still replace a CI one. A release overrides both from
        // its tag, so the number is a property of the version rather than of
        // however many times CI happened to run.
        versionCode = (
            System.getenv("BIKEYBOARD_VERSION_CODE")
                ?: System.getenv("GITHUB_RUN_NUMBER")
                ?: "1"
            ).toInt()
        versionName = System.getenv("BIKEYBOARD_VERSION_NAME") ?: "0.1.0"
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

        // A release APK has to be signed by *something*: an unsigned one cannot
        // be installed, and an APK nobody can install is not a release. So this
        // falls back to the same committed key rather than failing the build.
        //
        // The fallback is honest rather than good. A key whose password is in
        // the repository proves nothing about who built the APK, which is fine
        // while the only person installing it is the person who wrote it, and
        // not fine the moment anybody else does. Supply the four environment
        // variables and the same workflow signs properly, with no other change.
        create("release") {
            val keystore = System.getenv("RELEASE_KEYSTORE")
            if (keystore.isNullOrBlank()) {
                storeFile = file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                storeFile = file(keystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
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
            signingConfig = signingConfigs.getByName("release")
            // Left off deliberately. The keyboard is one process doing one
            // thing, so there is nothing to shrink worth the debugging cost of
            // a stack trace that no longer names its methods.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    androidResources {
        // The bigram stores are memory-mapped straight out of the APK (D46),
        // and a compressed asset cannot be — `AssetManager.openFd` throws for
        // one. Storing them flat costs a few megabytes of download and saves
        // eighteen of heap in a process the system is quick to kill.
        noCompress += "bigrams"
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
