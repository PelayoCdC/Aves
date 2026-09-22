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

    // Un APK por arquitectura en vez de uno con las cuatro dentro.
    //
    // Las librerias nativas de LiteRT pesan ~19 MB repetidas cuatro veces, y
    // ningun movil usa mas de una. Dividir por ABI es la mitigacion mas barata
    // del tamano: no toca ni una linea de codigo y quita ~14 MB del APK que
    // acaba en el telefono.
    //
    // `isUniversalApk` sigue produciendo el APK completo, que es el que se
    // instala a mano cuando no se sabe la arquitectura del destino.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
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
    implementation(projects.core.listening)
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

    // Y el flavor publicable enlaza BirdNET+, cuyos pesos SI son Apache 2.0.
    // Cubre mas especies que el otro: 8.923 aves frente a 6.421.
    "openImplementation"(projects.core.ml.birdnetplus)

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

    // El cargador de imagenes se configura aqui (ver AvesApplication): Wikimedia
    // rechaza con 403 el User-Agent por defecto de OkHttp.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
