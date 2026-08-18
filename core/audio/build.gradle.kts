plugins {
    id("aves.android.library")
}

android {
    namespace = "dev.pelayo.aves.core.audio"
}

// Este modulo captura audio y nada mas. No conoce ningun modelo: el formato se
// lo dicta quien lo instancia, leyendo requiredSampleRate/requiredSampleCount
// del clasificador. Es lo que permite cambiar de BirdNET (48 kHz / 3 s) a Perch
// (32 kHz / 5 s) sin tocar una linea de aqui.
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)

    // MediaCodec y MediaMuxer son stubs en la JVM: la codificacion solo se puede
    // probar de verdad en un dispositivo.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
