plugins {
    id("aves.android.library")
}

android {
    namespace = "dev.pelayo.aves.core.listening"

    // El motor escribe en el log de Android; en la JVM, `Log` no hace nada en
    // vez de lanzar. Es lo que permite probar el bucle de escucha sin movil.
    testOptions.unitTests.isReturnDefaultValues = true
}

// El bucle de escucha extraido del ViewModel para que pueda vivir tambien en un
// servicio en primer plano. Sin esto, salir de la app corta la identificacion.
dependencies {
    api(projects.core.audio)
    api(projects.core.data)
    api(projects.core.ml.api)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlinx.coroutines.test)
}
