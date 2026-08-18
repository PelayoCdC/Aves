package dev.pelayo.aves.di

import android.content.Context
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.data.SpeciesResolver
import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.CatalogSeeder

/**
 * Contenedor de dependencias hecho a mano.
 *
 * El plan preveia Hilt desde la Fase 2 y acabara entrando, pero con seis objetos
 * y ninguno con scope complicado, meterlo ahora seria pagar KSP y una capa de
 * indireccion a cambio de nada. Entrara cuando haya varias features
 * compitiendo por los mismos ViewModels, que es cuando Hilt empieza a devolver
 * lo que cuesta.
 *
 * Todo es `by lazy`: la base de datos no se abre hasta que alguien la usa, asi
 * el arranque en frio no paga el coste de SQLite si el usuario solo mira la
 * pantalla de identificar.
 */
class AppContainer(private val context: Context) {

    val database: AvesDatabase by lazy { AvesDatabase.create(context) }

    val catalogSeeder: CatalogSeeder by lazy { CatalogSeeder(context, database) }

    private val speciesResolver: SpeciesResolver by lazy {
        SpeciesResolver(database.modelLabelDao())
    }

    val speciesRepository: SpeciesRepository by lazy { SpeciesRepository(database) }

    val observationRepository: ObservationRepository by lazy {
        ObservationRepository(database, speciesResolver)
    }
}
