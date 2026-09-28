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

    // Una sola version. Hubo dos flavors (`personal` con BirdNET v2.4, no
    // comercial; `open` solo con modelos Apache 2.0, publicable), y se juntaron
    // en una que lleva los dos modelos de cantos y deja elegir en Ajustes. Con
    // BirdNET v2.4 dentro la app NO se puede publicar ni vender.
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
