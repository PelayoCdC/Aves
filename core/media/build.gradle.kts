plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.core.media"
}

// Reproduccion de los recortes de audio, compartida por la bandeja de revision y
// la ficha de especie.
//
// Modulo propio y no dentro de :core:designsystem porque este arrastra ExoPlayer,
// y designsystem lo usan TODAS las features: meterlo ahi cargaria el reproductor
// en pantallas que no reproducen nada.
dependencies {
    // Los iconos vienen de :core:designsystem, que ya los expone con api().
    implementation(projects.core.designsystem)
    implementation(libs.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
