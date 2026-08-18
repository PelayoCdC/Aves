plugins {
    id("aves.android.library")
}

android {
    namespace = "dev.pelayo.aves.core.ml.vision"

    androidResources {
        noCompress += "tflite"
    }
}

// A diferencia de :core:ml:birdnet, este modulo SI es publicable: el modelo de
// Google AIY es Apache 2.0. Por eso se enlaza en los dos flavors.
dependencies {
    api(projects.core.ml.api)
    implementation(libs.litert)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
