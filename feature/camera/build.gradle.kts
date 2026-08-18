plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.feature.camera"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.data)
    implementation(projects.core.model)
    implementation(projects.core.ml.api)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
