pluginManagement {
    // build-logic es un "included build": sus convention plugins quedan disponibles
    // por id (aves.android.library, etc.) sin publicarlos en ningun repositorio.
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Aves"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// Kotlin puro, cero Android -> tests JVM instantaneos.
include(":core:common")
include(":core:model")

include(":core:designsystem")

// Captura de audio. No sabe nada de ML: se parametriza con lo que el modelo pide.
include(":core:audio")

// Room aislado en su propio modulo: KSP es lento, y asi un cambio en los
// repositorios no vuelve a disparar la generacion de codigo.
// Solo GBIF: sin API key y sin clausula no comercial, a diferencia de eBird y
// xeno-canto, que se quedan fuera de la app a proposito.
include(":core:network")

include(":core:database")

// Repositorios: une base de datos y red. Es lo que consumen las features.
include(":core:data")

// El modulo mas importante del proyecto: define las interfaces que hacen
// intercambiables los modelos. NO depende de LiteRT.
include(":core:ml:api")

// Implementacion CC BY-NC-SA. Solo entra en el flavor `personal`.
include(":core:ml:birdnet")

include(":feature:identify")

// Bandeja de revision: confirmar o descartar lo que el modelo cree haber oido.
// Es lo que separa "la maquina cree" de "yo lo vi", y sin ella la coleccion no
// significa nada para un observador de aves.
include(":feature:review")
include(":feature:pokedex")
include(":feature:settings")
