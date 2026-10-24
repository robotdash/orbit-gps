import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing key details live in keystore.properties next to this project
// (never inside it, and never shared). See tools/README-release.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Optional: check a built region pack (see RegionFilesTest).
        unitTests.all { t -> (project.findProperty("orbit.region") as String?)?.let { t.systemProperty("orbit.region", it) } }
    }
    namespace = "com.robotdash.orbitgps"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.robotdash.orbitgps"
        minSdk = 26
        targetSdk = 36
        versionCode = 42
        versionName = "3.7"
        // The offline routing engine is built for 64-bit ARM (all phones from the last several years).
        ndk {
            abiFilters += "arm64-v8a"
            // Lets Play show readable crash reports from the routing/map engines.
            debugSymbolLevel = "SYMBOL_TABLE"
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Voice clips are already compressed (Opus); don't recompress them.
    androidResources {
        noCompress += "ogg"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // Offline routing engine (Valhalla) running on the phone
    // Android Auto
    implementation("androidx.car.app:app:1.7.0")
    implementation("io.github.rallista:valhalla-mobile:0.6.4")
    implementation("io.github.rallista:valhalla-models-config:0.6.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("androidx.car.app:app-testing:1.7.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
}
