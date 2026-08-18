plugins {
    id("aves.android.application")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves"

    defaultConfig {
        applicationId = "dev.pelayo.aves"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    // :app es el punto de composicion: conocer las implementaciones concretas es
    // literalmente su trabajo, igual que conoce BirdNetAudioClassifier en el
    // flavor personal.
    implementation(projects.core.network)
    implementation(projects.core.ml.api)
    // Apache 2.0: entra en los DOS flavors, a diferencia de BirdNET.
    implementation(projects.core.ml.vision)

    // Solo el flavor `personal` enlaza BirdNET. El flavor `open` se compila sin
    // el, y por eso puede publicarse: el modelo CC BY-NC-SA ni siquiera esta en
    // el APK. Es la diferencia entre "no lo usamos" y "no esta".
    "personalImplementation"(projects.core.ml.birdnet)

    implementation(projects.feature.identify)
    implementation(projects.feature.pokedex)
    implementation(projects.feature.review)
    implementation(projects.feature.camera)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
}
