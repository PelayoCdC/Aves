plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.feature.pokedex"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.media)
    implementation(projects.core.map)
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    // Fotos de las fichas, desde Wikipedia.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.kotlinx.coroutines.test)
}
