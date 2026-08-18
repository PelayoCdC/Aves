plugins {
    id("aves.android.library")
}

android {
    namespace = "dev.pelayo.aves.core.data"
}

// Los repositorios son la frontera: las features hablan con esto y nunca con
// Room ni con LiteRT directamente.
dependencies {
    api(projects.core.model)
    api(projects.core.database)
    implementation(projects.core.network)
    api(projects.core.ml.api)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
