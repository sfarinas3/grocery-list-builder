// Pure Kotlin/JVM module — no Android dependency, no Context. Mirrors grocery/ (the Python
// package): models, ingest, extract, aggregate, pipeline. Kept Android-free so its tests run in
// seconds with `./gradlew :core:test`, no emulator, same fast feedback loop as `pytest` against
// grocery/ today.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
