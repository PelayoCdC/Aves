plugins {
    id("aves.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.pelayo.aves.core.database"
}

// El esquema exportado se versiona: es lo que permite escribir tests de
// migracion de verdad en vez de confiar en que "seguramente funcione".
//
// Se escribe directamente dentro de los assets de androidTest en vez de en
// $projectDir/schemas y luego enlazarlo con sourceSets, porque la API de source
// sets cambio en AGP 9 y configurarla desde Kotlin DSL revienta con un
// ClassCastException. Asi el esquema queda versionado Y visible para los tests
// sin tocar la configuracion de source sets. Va en la raiz de los assets (no en
// una subcarpeta `schemas/`) porque es ahi donde MigrationTestHelper busca
// `<clase>/<version>.json`.
ksp {
    arg("room.schemaLocation", "$projectDir/src/androidTest/assets")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    api(libs.room.runtime)
    api(libs.room.ktx)
    ksp(libs.room.compiler)

    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
