package dev.pelayo.aves.di

import android.content.Context
import dev.pelayo.aves.core.data.ChecklistRepository
import dev.pelayo.aves.core.data.GbifChecklistSource
import dev.pelayo.aves.core.data.GbifOccurrenceSource
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RangeMapRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.data.SpeciesRepository
import dev.pelayo.aves.core.data.SpeciesEncyclopedia
import dev.pelayo.aves.core.data.SpeciesResolver
import dev.pelayo.aves.core.database.AvesDatabase
import dev.pelayo.aves.core.database.CatalogSeeder
import dev.pelayo.aves.core.audio.ClipStorage
import dev.pelayo.aves.core.designsystem.components.PickedPlace
import dev.pelayo.aves.core.designsystem.components.PlacePickerActions
import dev.pelayo.aves.core.listening.DetectionNamer
import dev.pelayo.aves.core.listening.FileAnalysisSaver
import dev.pelayo.aves.core.ml.api.ContextPriorProvider
import dev.pelayo.aves.core.network.GbifClient
import dev.pelayo.aves.core.network.WikipediaClient
import dev.pelayo.aves.feature.settings.SettingsStore
import dev.pelayo.aves.feature.settings.settingsStore
import dev.pelayo.aves.location.LocationProvider
import dev.pelayo.aves.location.PlaceSearch
import dev.pelayo.aves.ml.createContextPrior
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Contenedor de dependencias hecho a mano.
 *
 * El plan preveia Hilt y acabara entrando, pero con una docena de objetos y
 * ninguno con scope complicado, meterlo ahora seria pagar KSP y una capa de
 * indireccion a cambio de nada. Entrara cuando haya varias features
 * compitiendo por los mismos ViewModels, que es cuando Hilt empieza a devolver
 * lo que cuesta.
 *
 * Todo es `by lazy`: la base de datos no se abre hasta que alguien la usa, asi
 * el arranque en frio no paga el coste de SQLite si el usuario solo mira la
 * pantalla de identificar.
 *
 * @param applicationScope trabajos que sobreviven a cualquier pantalla y a la
 *   escucha: la siembra del catalogo, la descarga de GBIF, el relleno de rarezas.
 */
class AppContainer(
    private val context: Context,
    val applicationScope: CoroutineScope,
) {

    val database: AvesDatabase by lazy { AvesDatabase.create(context) }

    val catalogSeeder: CatalogSeeder by lazy { CatalogSeeder(context, database) }

    val speciesResolver: SpeciesResolver by lazy {
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
     * comercial. Cada flavor tiene ademas el geomodelo de su clasificador, que
     * es mas fino porque se entreno junto a el, pero la rareza de la coleccion
     * sale siempre de aqui.
     */
    val regionRepository: RegionRepository by lazy {
        RegionRepository(database.regionDao(), GbifOccurrenceSource(gbifClient))
    }

    /**
     * UN prior para toda la vida del proceso.
     *
     * Antes se creaba uno por escucha y nadie lo cerraba: en el flavor
     * `personal` eso era un interprete de 14 MB mapeado en memoria que se
     * fugaba en cada sesion, mas el barrido anual de 48 inferencias repetido
     * cada vez. Como singleton, la cache del barrido sobrevive entre escuchas.
     */
    val contextPrior: ContextPriorProvider by lazy {
        createContextPrior(context, regionRepository)
    }

    val locationProvider: LocationProvider by lazy { LocationProvider(context) }

    val settingsStore: SettingsStore by lazy { settingsStore(context) }

    /** Nombres del catalogo para lo que dicen los modelos. Ver [DetectionNamer]. */
    val detectionNamer: DetectionNamer by lazy {
        DetectionNamer(speciesResolver, speciesRepository)
    }

    /** Descarga de GBIF y relleno de rarezas, fuera del ciclo de vida de la escucha. */
    val regionWarmer: RegionWarmer by lazy {
        RegionWarmer(applicationScope, regionRepository, observationRepository)
    }

    val imageObservationRecorder: ImageObservationRecorder by lazy {
        ImageObservationRecorder(observationRepository, regionRepository, photosDirectory)
    }

    /** Nombre en castellano y speciesId canonico para los candidatos de la camara. */
    val imageCandidateNamer: ImageCandidateNamer by lazy {
        ImageCandidateNamer(speciesResolver, speciesRepository)
    }

    /** Una foto subida desde la ficha de una especie, guardada como las de la camara. */
    val galleryPhotoSaver: GalleryPhotoSaver by lazy { GalleryPhotoSaver(context, imageObservationRecorder) }

    /**
     * Las aves de tu provincia, tu region y tu pais: lo que permite que la
     * Pokedex diga que te falta por ver AQUI y no en el planeta. Sale de GBIF,
     * igual que la rareza, asi que vale para los dos flavors.
     */
    val checklistRepository: ChecklistRepository by lazy {
        ChecklistRepository(database, GbifChecklistSource(gbifClient))
    }

    /** La clave de GBIF de cada especie, para el mapa de donde se registra. */
    val rangeMapRepository: RangeMapRepository by lazy { RangeMapRepository(database, gbifClient) }

    private val wikipediaClient: WikipediaClient by lazy { WikipediaClient() }

    /** Foto y descripcion de cada especie, pedidas una vez y guardadas en el catalogo. */
    val encyclopedia: SpeciesEncyclopedia by lazy {
        SpeciesEncyclopedia(wikipediaClient, speciesRepository)
    }

    val exporter: ObservationExporter by lazy {
        ObservationExporter(observationRepository, speciesRepository)
    }

    /**
     * El analisis de un fichero deja de ser un callejon sin salida: va a la
     * bandeja con la pista de cada especie y, si se dijo donde se grabo, con su
     * rareza alli.
     */
    val fileAnalysisSaver: FileAnalysisSaver by lazy {
        FileAnalysisSaver(
            observations = observationRepository,
            clipsDirectory = clipsDirectory,
            rarityOf = { speciesId, where, epochMillis ->
                // El sitio de la grabacion casi nunca es una celda ya descargada.
                // Se pide (una vez por celda) y, cuando llegue, las rarezas que
                // ahora queden pendientes se rellenan solas.
                regionWarmer.warm(where)
                regionRepository.rarityOf(
                    speciesId,
                    where.latitude,
                    where.longitude,
                    RegionRepository.monthOf(epochMillis),
                )
            },
        )
    }

    private val placeSearch: PlaceSearch by lazy { PlaceSearch(context) }

    /** Los ultimos lugares elegidos a mano. En memoria: son una comodidad, no un dato. */
    private val recentPlaces = ArrayDeque<PickedPlace>()

    /**
     * Lo que necesita el selector de "¿donde fue?" de una foto o una grabacion.
     *
     * @param locationDisabled el modo sin ubicacion de los ajustes. Con el
     *   encendido, "mi ubicacion actual" no consulta nada; buscar un sitio por su
     *   nombre sigue valiendo, porque eso lo escribe el usuario.
     */
    fun placePickerActions(locationDisabled: suspend () -> Boolean): PlacePickerActions = PlacePickerActions(
        currentPlace = {
            if (locationDisabled()) {
                null
            } else {
                locationProvider.current()?.let { PickedPlace(it.latitude, it.longitude, CURRENT_PLACE_NAME) }
            }
        },
        search = placeSearch::search,
        recent = { synchronized(recentPlaces) { recentPlaces.toList() } },
        remember = { place ->
            // La ubicacion actual no se recuerda: mañana "actual" es otro sitio.
            if (place.name != CURRENT_PLACE_NAME) {
                synchronized(recentPlaces) {
                    recentPlaces.removeAll { it.name == place.name }
                    recentPlaces.addFirst(place)
                    while (recentPlaces.size > MAX_RECENT_PLACES) recentPlaces.removeLast()
                }
            }
        },
    )

    /** Rutas de otro perfil y ficheros huerfanos. Ver [MediaHousekeeping]. */
    val mediaHousekeeping: MediaHousekeeping by lazy {
        MediaHousekeeping(
            filesDirectory = context.filesDir,
            mediaDirectories = listOf(clipsDirectory, photosDirectory),
            observations = observationRepository,
        )
    }

    /** Borra una escucha con sus observaciones y sus recortes. */
    suspend fun deleteSession(sessionId: Long) {
        observationRepository.deleteSession(sessionId)
        ClipStorage.deleteClipsFor(clipsDirectory, sessionId)
    }

    /**
     * Donde viven los recortes de audio de las detecciones.
     *
     * En `filesDir` y no en la cache: el sistema puede vaciar la cache cuando
     * quiera, y un recorte perdido deja una observacion de la coleccion sin la
     * prueba que la justifica. Quedan fuera de la copia de seguridad en la
     * nube (ver `res/xml/backup_rules.xml`): pesan y se regeneran escuchando.
     */
    val clipsDirectory: File by lazy {
        File(context.filesDir, "clips").apply { mkdirs() }
    }

    /**
     * Las fotos con las que se identifico por imagen: la miniatura de 224 px que
     * vio el modelo y, en `full/`, el encuadre entero para mirarla. Ver
     * `PhotoStorage`.
     */
    val photosDirectory: File by lazy {
        File(context.filesDir, "photos").apply { mkdirs() }
    }
}

private const val CURRENT_PLACE_NAME = "Mi ubicación actual"
private const val MAX_RECENT_PLACES = 3
