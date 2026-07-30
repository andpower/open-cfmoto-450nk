plugins {
    alias(libs.plugins.android.application)
}

val signingStoreFile = providers.environmentVariable("SIGNING_STORE_FILE")
    .orElse(providers.gradleProperty("signingStoreFile"))
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD")
    .orElse(providers.gradleProperty("signingStorePassword"))
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS")
    .orElse(providers.gradleProperty("signingKeyAlias"))
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD")
    .orElse(providers.gradleProperty("signingKeyPassword"))
val releaseSigningRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = true)
}
val missingSigningValues = listOf(
    "SIGNING_STORE_FILE" to signingStoreFile.orNull,
    "SIGNING_STORE_PASSWORD" to signingStorePassword.orNull,
    "SIGNING_KEY_ALIAS" to signingKeyAlias.orNull,
    "SIGNING_KEY_PASSWORD" to signingKeyPassword.orNull,
).filter { it.second.isNullOrBlank() }.map { it.first }

if (releaseSigningRequested && missingSigningValues.isNotEmpty()) {
    throw GradleException(
        "Release signing is incomplete. Missing: ${missingSigningValues.joinToString()}. " +
            "Unsigned release APKs are intentionally blocked.",
    )
}

android {
    namespace = "dev.zanderp.opencfmoto"
    androidResources {
        // Generate Android 13+ per-app language metadata from values/ and values-es/.
        generateLocaleConfig = true
    }
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // Keep this 450NK edition installable next to the upstream OpenCfMoto app.
        // The Kotlin namespace stays unchanged to avoid a risky source/package migration.
        applicationId = "com.andpower.opencfmoto450nk"
        minSdk = 29
        targetSdk = 36
        versionCode = 34
        versionName = "2.2.1-450nk"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Default OpenRouteService key used when the rider hasn't entered their own. Supply it via
        // `-PorsApiKey=...`, an `orsApiKey` in gradle.properties, or the ORS_API_KEY env var so the
        // key isn't hardcoded in source. Empty → routing falls back to the OSRM demo, then beeline.
        val orsDefaultKey = (project.findProperty("orsApiKey") as String?)
            ?: System.getenv("ORS_API_KEY")
            ?: ""
        buildConfigField("String", "ORS_API_KEY", "\"$orsDefaultKey\"")
    }

    signingConfigs {
        create("permanentRelease") {
            if (missingSigningValues.isEmpty()) {
                storeFile = file(signingStoreFile.get())
                storePassword = signingStorePassword.get()
                keyAlias = signingKeyAlias.get()
                keyPassword = signingKeyPassword.get()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("permanentRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.mlkit.barcodescanner)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.jmdns)
    implementation(libs.protobuf.java)
    implementation(libs.conscrypt.android)
    implementation(libs.osmdroid)
    implementation(libs.maplibre)
    // Compile-time OkHttp for MapLibre cellular pin (MapLibre brings it as runtime only).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
