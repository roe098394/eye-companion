plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenfit"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.screenfit"
        minSdk = 21
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

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
