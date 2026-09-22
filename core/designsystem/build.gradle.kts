plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material.icons.extended)

    // El selector de lugar pide el permiso de ubicacion el mismo.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
