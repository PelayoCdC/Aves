package dev.pelayo.aves.core.data

import dev.pelayo.aves.core.database.dao.ModelLabelDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Traduce indices de tensor a especies canonicas.
 *
 * El mapeo se carga entero en memoria y se cachea porque la inferencia lo
 * consulta varias veces por segundo: una consulta a SQLite por cada candidato de
 * cada ventana seria absurdo. Son 6.522 entradas por modelo, caben de sobra.
 *
 * Esta clase es la que convierte la promesa de "cambiar de modelo sin reescribir
 * la app" en algo real: la tabla `model_labels` es la fuente de verdad y resuelve
 * casos que el nombre cientifico por si solo no puede, como los 38 taxones de
 * BirdNET cuyo codigo eBird caduco o la agrupacion de Parus minor dentro de
 * Parus major.
 */
class SpeciesResolver(private val modelLabelDao: ModelLabelDao) {

    private val mutex = Mutex()
    // Concurrente: se lee fuera del cerrojo (la via rapida) desde varios hilos.
    private val cache = ConcurrentHashMap<String, Array<String?>>()

    /**
     * @return speciesId canonico, o null si la clase no es un ave: las 11 de
     *   no-evento (perro, sirena, humano...) y los ~90 animales que BirdNET
     *   incluye pero no lo son (ranas, grillos, sapos).
     */
    suspend fun resolve(modelId: String, labelIndex: Int): String? =
        mapping(modelId).getOrNull(labelIndex)

    private suspend fun mapping(modelId: String): Array<String?> {
        cache[modelId]?.let { return it }
        return mutex.withLock {
            cache[modelId]?.let { return@withLock it }
            val labels = modelLabelDao.labelsFor(modelId)
            check(labels.isNotEmpty()) {
                "No hay cross-walk para el modelo '$modelId'. ¿Se sembró el catálogo?"
            }
            // Array indexado por labelIndex en vez de mapa: el acceso es por
            // indice denso y esto evita el boxing de miles de Integer.
            val size = labels.maxOf { it.labelIndex } + 1
            val table = arrayOfNulls<String>(size)
            for (label in labels) table[label.labelIndex] = label.speciesId
            cache[modelId] = table
            table
        }
    }

    fun invalidate() {
        cache.clear()
    }
}
