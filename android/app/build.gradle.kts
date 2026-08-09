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
        versionCode = 2
        versionName = "0.2.0-phase2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
