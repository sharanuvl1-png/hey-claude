plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.personal.heyclaude"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.personal.heyclaude"
        minSdk = 28
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // A fixed key kept in the repo so each new build installs over the old one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    // "core" leaves out SMS, notification access and accessibility, which Google
    // Play Protect blocks for apps installed outside the Play Store in India.
    // "voice" is core plus the auto-mic helper (accessibility), no SMS or messages.
    // "full" has everything and needs Play Protect scanning off to install.
    flavorDimensions += "features"
    productFlavors {
        create("core") { dimension = "features" }
        create("voice") { dimension = "features" }
        create("full") { dimension = "features" }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // The speech model is already compressed; keep it as-is in the APK.
    // Robolectric runs the assistant's conversation tests on the build machine.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    // The Claude SDK's libraries each ship the same licence files.
    packaging {
        resources {
            excludes += listOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES",
                "META-INF/versions/9/previous-compilation-data.bin")
        }
    }
    androidResources {
        noCompress += listOf("mdl", "fst", "conf", "int", "mat", "carpa", "txt")
    }
}

dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("androidx.work:work-runtime:2.9.1")
    implementation("com.anthropic:anthropic-java:3.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
