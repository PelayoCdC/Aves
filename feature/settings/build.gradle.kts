plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.feature.settings"
}

dependencies {
    implementation(projects.core.designsystem)
    // Solo por ModelDescriptor: la pantalla necesita licencia y atribucion de
    // cada modelo, nunca el modelo en si.
    implementation(projects.core.ml.api)
    // Para el contador de la coleccion. La pantalla funciona sin el.
    implementation(projects.core.data)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
