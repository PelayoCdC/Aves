import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension

plugins {
    // Obligatorio desde Kotlin 2.0 en cuanto se activa buildFeatures.compose,
    // tambien con el Kotlin integrado de AGP 9.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Se aplica ENCIMA de aves.android.library o aves.android.application.
// Se usa pluginManager.withPlugin en vez del accesor `android` porque este
// plugin no sabe cual de los dos tipos de modulo lo esta aplicando.
pluginManager.withPlugin("com.android.application") {
    extensions.configure<ApplicationExtension> {
        buildFeatures { compose = true }
    }
}
pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryExtension> {
        buildFeatures { compose = true }
    }
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val bom = platform(libs.findLibrary("androidx-compose-bom").get())
    "implementation"(bom)
    "androidTestImplementation"(bom)

    "implementation"(libs.findLibrary("androidx-compose-ui").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-graphics").get())
    "implementation"(libs.findLibrary("androidx-compose-material3").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())

    "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())
}
