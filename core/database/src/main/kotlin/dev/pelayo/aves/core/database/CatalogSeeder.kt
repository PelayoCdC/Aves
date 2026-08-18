package dev.pelayo.aves.core.database

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dev.pelayo.aves.core.database.entity.ModelLabelEntity
import dev.pelayo.aves.core.database.entity.SpeciesEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/**
 * Importa el catalogo de especies y el cross-walk de modelos en el primer
 * arranque.
 *
 * POR QUE NO SE USA UNA BASE DE DATOS PREPOBLADA
 * El plan original preveia generar un .db en tiempo de build y cargarlo con
 * `createFromAsset()`. Se descarto tras probarlo: ese mecanismo exige que el
 * fichero lleve el `identityHash` exacto que Room deriva del esquema, y
 * generarlo fuera de Room es fragil de una forma peligrosa: cualquier cambio en
 * una entidad rompe el arranque **en el movil del usuario**, no en el build.
 *
 * Importar ~13.000 filas cuesta ~2,3 s medidos en emulador y, si algo va mal,
 * falla de forma visible y depurable.
 *
 * Los TSV los genera `scripts/build-catalog.ps1`.
 */
class CatalogSeeder(
    private val context: Context,
    private val database: AvesDatabase,
) {
    /**
     * Puebla el catalogo si hace falta. Es idempotente y barato de llamar en
     * cada arranque: si ya hay especies, no hace nada.
     *
     * @return numero de especies importadas, o 0 si ya estaba sembrado.
     */
    suspend fun seedIfNeeded(): Int = withContext(Dispatchers.IO) {
        val speciesDao = database.speciesDao()
        if (speciesDao.count() > 0) return@withContext 0

        val started = System.currentTimeMillis()
        val species = readSpecies()
        val labels = readModelLabels(BIRDNET_MODEL_ID, BIRDNET_LABELS_ASSET)

        // Una sola transaccion: con inserciones sueltas cada una haria su propio
        // fsync y esto pasaria de milisegundos a decenas de segundos.
        database.withTransaction {
            speciesDao.upsertAll(species)
            // NO se siembra `species_names`. El nombre en español ya viaja en
            // `defaultCommonName`, asi que llenar esa tabla con lo mismo son
            // 6.419 filas redundantes: un tercio del trabajo de siembra para no
            // aportar nada. La tabla existe para cuando haya un segundo idioma,
            // y hasta entonces las consultas caen a `defaultCommonName`.
            database.modelLabelDao().replaceModel(BIRDNET_MODEL_ID, labels)
        }

        val unmapped = database.modelLabelDao().unmappedCountFor(BIRDNET_MODEL_ID)
        Log.i(
            TAG,
            "Catalogo sembrado en ${System.currentTimeMillis() - started} ms: " +
                "${species.size} especies, ${labels.size} etiquetas ($unmapped sin mapear)",
        )
        species.size
    }

    private fun readSpecies(): List<SpeciesEntity> =
        context.assets.open(SPECIES_ASSET).bufferedReader().use { reader ->
            reader.dataRows().map { columns ->
                SpeciesEntity(
                    speciesId = columns[0],
                    scientificName = columns[1],
                    genus = columns[2],
                    family = columns[3],
                    order = columns[4],
                    defaultCommonName = columns[5],
                    ebirdCode = columns.getOrNull(6)?.takeIf { it.isNotEmpty() },
                )
            }.toList()
        }

    private fun readModelLabels(modelId: String, asset: String): List<ModelLabelEntity> =
        context.assets.open(asset).bufferedReader().use { reader ->
            reader.dataRows().map { columns ->
                ModelLabelEntity(
                    modelId = modelId,
                    labelIndex = columns[0].toInt(),
                    // Vacio significa clase deliberadamente no mapeada: los 11
                    // no-eventos y los ~90 animales que no son aves (ranas,
                    // grillos, sapos) que BirdNET incluye a proposito.
                    speciesId = columns.getOrNull(1)?.takeIf { it.isNotEmpty() },
                    rawLabel = columns.getOrElse(2) { "" },
                )
            }.toList()
        }

    /**
     * Filas de datos de un TSV, sin cabecera.
     *
     * Se quita el BOM de la primera linea: PowerShell escribe UTF-8 con marca de
     * orden y, sin esto, la primera columna del primer registro llevaria un
     * caracter invisible delante y no casaria con nada.
     */
    private fun BufferedReader.dataRows(): Sequence<List<String>> =
        lineSequence()
            .withIndex()
            .mapNotNull { (index, raw) ->
                val line = if (index == 0) raw.removePrefix("\uFEFF") else raw
                if (index == 0 || line.isBlank()) null else line.split('\t')
            }

    companion object {
        private const val TAG = "CatalogSeeder"

        const val BIRDNET_MODEL_ID = "birdnet-v2.4"
        const val CATALOG_LANGUAGE = "es"

        private const val SPECIES_ASSET = "catalog/species.tsv"
        private const val BIRDNET_LABELS_ASSET = "catalog/model_labels_birdnet-v2.4.tsv"
    }
}
