plugins {
    id("aves.android.library")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves.core.map"
}

// El mapa: teselas de GBIF (mapa base y densidad de registros por especie) sobre
// osmdroid, envuelto en un composable. Modulo propio para que la unica pieza de
// la app que sabe de teselas y proyecciones sea esta, y para que osmdroid no
// entre en ninguna feature que no pinte mapas.
//
// osmdroid (Apache 2.0) y no el SDK de Google Maps: sin clave, sin facturacion y
// sin nada que le impida al flavor `open` publicarse.
dependencies {
    implementation(libs.osmdroid)
    implementation(libs.androidx.lifecycle.runtime.compose)

    testImplementation(libs.junit)
}
