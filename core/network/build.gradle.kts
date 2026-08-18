plugins {
    id("aves.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.pelayo.aves.core.network"
}

// Solo GBIF. Ni eBird ni xeno-canto entran aqui: sus APIs son no comerciales y
// meterlas contaminaria el flavor publicable. eBird se usa unicamente en tiempo
// de build, desde scripts/build-catalog.ps1.
dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
}
