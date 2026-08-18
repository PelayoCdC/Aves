plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.core.designsystem"
}

dependencies {
    api(libs.androidx.compose.material.icons.extended)
}
