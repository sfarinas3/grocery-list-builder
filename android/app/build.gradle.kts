// AGP 9's built-in Kotlin support means the separate org.jetbrains.kotlin.android plugin is no
// longer applied here — see https://kotl.in/gradle/agp-built-in-kotlin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.grocerylistbuilder.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grocerylistbuilder.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing reads from environment variables rather than a checked-in
    // keystore/keystore.properties — the release key never lives in the repo. Locally these are
    // unset, so a release build is just unsigned (fine for `assembleRelease` sanity checks on a
    // dev machine); CI (see .github/workflows/android-release.yml) sets them from GitHub Actions
    // secrets before building, so every published release is signed with the same key and can
    // cleanly update over a previous install.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // No Android port of pypdf/python-docx exists (see plan §"Key dependencies"). pdfbox-android
    // is the maintained ART-compatible PDFBox fork; DOCX is hand-rolled (zip + XmlPullParser).
    implementation(libs.pdfbox.android)

    // OCR for recipe photos.
    implementation(libs.mlkit.text.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
