// Root build file. Deliberadamente vacio de logica: toda la configuracion
// compartida vive en los convention plugins de build-logic/.
// Los plugins se declaran con apply=false solo para fijar su version en el classpath.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
}
