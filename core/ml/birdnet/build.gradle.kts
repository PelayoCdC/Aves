plugins {
    id("aves.android.library")
}

android {
    namespace = "dev.pelayo.aves.core.ml.birdnet"

    androidResources {
        // El .tflite se mapea en memoria con AssetFileDescriptor, y eso exige que
        // el APK lo guarde SIN comprimir. Si se comprime, openFd falla en runtime.
        noCompress += "tflite"
    }
}

// ATENCION - LICENCIA
// Este modulo carga los modelos de BirdNET, que son CC BY-NC-SA 4.0: NO se
// pueden usar comercialmente sin permiso expreso de Cornell
// (ccb-birdnet@cornell.edu). Por eso solo se enlaza desde el flavor `personal`.
// El codigo de este modulo es nuestro; lo que no es libre es el .tflite.
dependencies {
    api(projects.core.ml.api)
    implementation(libs.litert)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)

    // Test instrumentado: es la unica forma de ejecutar el .tflite de verdad,
    // porque LiteRT necesita las librerias nativas de Android.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
