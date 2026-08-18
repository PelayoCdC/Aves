plugins {
    id("aves.android.library")
}

android {
    namespace = "dev.pelayo.aves.core.ml.birdnetplus"

    androidResources {
        // Los .onnx se mapean desde assets igual que los .tflite y tampoco
        // pueden ir comprimidos.
        noCompress += "onnx"
    }
}

// EL MODULO QUE HACE PUBLICABLE LA APP.
//
// Usa BirdNET+ V3, cuyos pesos son **Apache 2.0** (ver MODEL_LICENSE en
// birdnet-team/birdnet-live-app), a diferencia de los de BirdNET v2.4, que son
// CC BY-NC-SA. Cubre 9.789 taxones frente a 6.522 clases, asi que el flavor
// `open` acaba siendo mejor que el `personal`.
//
// Los pesos vienen en ONNX y no en TFLite, de ahi el runtime distinto.
dependencies {
    api(projects.core.ml.api)
    implementation(libs.onnxruntime.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
