plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sharpfocus"
    compileSdk = 35
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.sharpfocus"
        minSdk = 24
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    // The camera library has to exist as a real file so the root helper can load it.
    packaging { jniLibs { useLegacyPackaging = true } }

    // Same hobby key as the other apps, so each new build installs over the last one.
    signingConfigs {
        create("hobby") {
            storeFile = file("companion.jks")
            storePassword = "companion"
            keyAlias = "companion"
            keyPassword = "companion"
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("hobby") }
        debug { signingConfig = signingConfigs.getByName("hobby") }
    }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
