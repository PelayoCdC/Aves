plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.feature.settings"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.ml.api)
}
