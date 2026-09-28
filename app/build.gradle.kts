plugins {
    id("aves.android.application")
    id("aves.android.compose")
}

android {
    namespace = "dev.pelayo.aves"

    defaultConfig {
        // ".personal" porque es el identificador con el que estaba instalada la
        // version que se usa de verdad (el antiguo flavor `personal`): con otro,
        // Android la instalaria como una app distinta y la coleccion se quedaria
        // en la vieja.
        applicationId = "dev.pelayo.aves.personal"
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
    implementation(projects.core.map)
    // :app es el punto de composicion: conocer las implementaciones concretas es
    // literalmente su trabajo.
    implementation(projects.core.network)
    implementation(projects.core.ml.api)
    implementation(projects.core.ml.vision)

    // Los dos modelos de cantos; el usuario elige en Ajustes (o los dos a la
    // vez). BirdNET v2.4 es CC BY-NC-SA 4.0: por el, esta app no se puede
    // publicar ni vender. BirdNET+ es Apache 2.0. Para una version publicable
    // bastaria con quitar esta primera linea y su opcion en createAudioClassifier.
    implementation(projects.core.ml.birdnet)
    implementation(projects.core.ml.birdnetplus)

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
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    // El cargador de imagenes se configura aqui (ver AvesApplication): Wikimedia
    // rechaza con 403 el User-Agent por defecto de OkHttp.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
}
