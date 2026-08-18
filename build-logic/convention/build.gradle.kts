plugins {
    `kotlin-dsl`
}

group = "dev.pelayo.aves.buildlogic"

// Se usa el JDK que ejecuta Gradle (21 en esta maquina) en vez de una toolchain
// fija: no hay JDK 17 instalado y no queremos que Gradle intente descargarlo.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // Los convention plugins son "precompiled script plugins" (.gradle.kts).
    // Para que `plugins { id("com.android.library") }` resuelva dentro de ellos,
    // el artefacto tiene que estar en el classpath de compilacion como
    // implementation, no compileOnly.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.composeCompilerGradlePlugin)
}
