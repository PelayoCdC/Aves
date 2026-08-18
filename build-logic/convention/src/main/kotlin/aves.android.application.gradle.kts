import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

extensions.configure<ApplicationExtension> {
    compileSdk = libs.findVersion("compileSdk").get().requiredVersion.toInt()

    defaultConfig {
        minSdk = libs.findVersion("minSdk").get().requiredVersion.toInt()
        targetSdk = libs.findVersion("targetSdk").get().requiredVersion.toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // --- Estrategia de licencias ---
    // Un solo codigo fuente, dos APKs:
    //   personal -> BirdNET (CC BY-NC-SA 4.0): 6.522 especies, NO publicable.
    //   open     -> solo modelos Apache 2.0: publicable, menos cobertura.
    // Existe desde el dia 1 para que la decision "publicar o no" nunca obligue
    // a reescribir nada. Las fases 0-6 solo construyen `personal`.
    flavorDimensions += "license"
    productFlavors {
        create("personal") {
            dimension = "license"
            applicationIdSuffix = ".personal"
            versionNameSuffix = "-personal"
        }
        create("open") {
            dimension = "license"
        }
    }
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
