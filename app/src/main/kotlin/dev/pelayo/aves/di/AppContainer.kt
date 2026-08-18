package dev.pelayo.aves.di

import android.content.Context
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.data.SpeciesResolver
import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.data.GbifOccurrenceSource
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.database.CatalogSeeder
import dev.pelayo.aves.core.network.GbifClient
import dev.pelayo.aves.location.LocationProvider
import java.io.File

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

    private val gbifClient: GbifClient by lazy { GbifClient() }

    /**
     * Rareza y prior derivados de ocurrencias reales de GBIF.
     *
     * Funciona en los dos flavors: GBIF no pide clave ni tiene clausula no
     * comercial. El flavor `personal` tiene ademas el meta-modelo de BirdNET,
     * que es mas fino porque se entreno junto al clasificador, pero la rareza
     * de la coleccion sale siempre de aqui.
     */
    val regionRepository: RegionRepository by lazy {
        RegionRepository(database.regionDao(), GbifOccurrenceSource(gbifClient))
    }

    val locationProvider: LocationProvider by lazy { LocationProvider(context) }

    /**
     * Donde viven los recortes de audio de las detecciones.
     *
     * En `filesDir` y no en la cache: el sistema puede vaciar la cache cuando
     * quiera, y un recorte perdido deja una observacion de la coleccion sin la
     * prueba que la justifica.
     */
    val clipsDirectory: File by lazy {
        File(context.filesDir, "clips").apply { mkdirs() }
    }
}
