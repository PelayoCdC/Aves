plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.feature.review"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.media)
    implementation(projects.core.data)
    implementation(projects.core.model)

    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Para escuchar el recorte de cada deteccion antes de darle un veredicto.
    implementation(libs.media3.exoplayer)

    testImplementation(libs.kotlinx.coroutines.test)
}
