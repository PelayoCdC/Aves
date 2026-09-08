package dev.pelayo.aves.core.database

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dev.pelayo.aves.core.common.SearchText
import dev.pelayo.aves.core.database.dao.SpeciesTaxonomy
import dev.pelayo.aves.core.database.entity.ModelLabelEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader

/** Lo que hizo una siembra. Todo a cero significa que no habia nada que hacer. */
data class SeedReport(
    val speciesImported: Int = 0,
    /** Identificadores de los modelos cuyo cross-walk se importo o actualizo. */
    val modelsImported: List<String> = emptyList(),
) {
    val didAnything: Boolean get() = speciesImported > 0 || modelsImported.isNotEmpty()
}

/**
 * Importa el catalogo de especies y los cross-walks de los modelos.
 *
 * POR QUE NO SE USA UNA BASE DE DATOS PREPOBLADA
 * El plan original preveia generar un .db en tiempo de build y cargarlo con
 * `createFromAsset()`. Se descarto tras probarlo: ese mecanismo exige que el
 * fichero lleve el `identityHash` exacto que Room deriva del esquema, y
 * generarlo fuera de Room es fragil de una forma peligrosa: cualquier cambio en
 * una entidad rompe el arranque **en el movil del usuario**, no en el build.
 *
 * POR MODELO Y NO "SI HAY ESPECIES, NADA"
 * La primera version comprobaba `species.count() > 0` y se iba. Eso funciono
 * mientras hubo un modelo, y fallo en silencio con el segundo: las etiquetas de
 * BirdNET+ y las del clasificador de imagen nunca se sembraron en las
 * instalaciones existentes, y la primera deteccion del flavor `open` reventaba
 * al no encontrar cross-walk. Ahora cada tabla se compara con su fichero y se
 * reimporta si no coinciden, lo que ademas cubre las actualizaciones de la
 * taxonomia.
 *
 * Los TSV los genera `scripts/build-catalog.ps1`.
 */
class CatalogSeeder(
    private val context: Context,
    private val database: AvesDatabase,
) {
    /**
     * Puebla lo que falte. Es idempotente y barato de llamar en cada arranque:
     * leer los TSV cuesta decenas de milisegundos y, si las tablas ya cuadran,
     * no escribe nada.
     */
    suspend fun seedIfNeeded(): SeedReport = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val speciesDao = database.speciesDao()
        val labelDao = database.modelLabelDao()

        var speciesImported = 0
        val species = readSpecies()
        if (speciesDao.count() != species.size) {
            // Una sola transaccion: con inserciones sueltas cada una haria su
            // propio fsync y esto pasaria de segundos a minutos.
            database.withTransaction { speciesDao.upsertTaxonomy(species) }
            speciesImported = species.size
        }

        val modelsImported = ArrayList<String>()
        for (asset in modelLabelAssets()) {
            val modelId = modelIdOf(asset)
            val labels = readModelLabels(modelId, "$CATALOG_DIR/$asset")
            if (labelDao.countFor(modelId) != labels.size) {
                database.withTransaction { labelDao.replaceModel(modelId, labels) }
                modelsImported += modelId
                Log.i(
                    TAG,
                    "Cross-walk de $modelId: ${labels.size} clases, " +
                        "${labelDao.unmappedCountFor(modelId)} sin mapear",
                )
            }
        }

        val report = SeedReport(speciesImported, modelsImported)
        if (report.didAnything) {
            Log.i(
                TAG,
                "Catalogo sembrado en ${System.currentTimeMillis() - started} ms: " +
                    "$speciesImported especies, modelos ${modelsImported.joinToString()}",
            )
        }
        report
    }

    /** Los ficheros `model_labels_<modelId>.tsv` presentes en los assets. */
    private fun modelLabelAssets(): List<String> =
        context.assets.list(CATALOG_DIR).orEmpty()
            .filter { it.startsWith(MODEL_LABELS_PREFIX) && it.endsWith(TSV_SUFFIX) }
            .sorted()

    private fun modelIdOf(asset: String): String =
        asset.removePrefix(MODEL_LABELS_PREFIX).removeSuffix(TSV_SUFFIX)

    private fun readSpecies(): List<SpeciesTaxonomy> =
        context.assets.open(SPECIES_ASSET).bufferedReader().use { reader ->
            reader.dataRows().map { columns ->
                val scientific = columns[1]
                val common = columns[5]
                SpeciesTaxonomy(
                    speciesId = columns[0],
                    scientificName = scientific,
                    genus = columns[2],
                    family = columns[3],
                    order = columns[4],
                    defaultCommonName = common,
                    ebirdCode = columns.getOrNull(6)?.takeIf { it.isNotEmpty() },
                    // Nombre comun y cientifico juntos: se busca por cualquiera
                    // de los dos y asi basta una columna y un indice.
                    searchName = SearchText.fold("$common $scientific"),
                )
            }.toList()
        }

    private fun readModelLabels(modelId: String, asset: String): List<ModelLabelEntity> =
        context.assets.open(asset).bufferedReader().use { reader ->
            reader.dataRows().map { columns ->
                ModelLabelEntity(
                    modelId = modelId,
                    labelIndex = columns[0].toInt(),
                    // Vacio significa clase deliberadamente no mapeada: los
                    // no-eventos (perro, sirena...) y los animales que no son
                    // aves que los modelos incluyen a proposito.
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
                val line = if (index == 0) raw.removePrefix("﻿") else raw
                if (index == 0 || line.isBlank()) null else line.split('\t')
            }

    companion object {
        private const val TAG = "CatalogSeeder"

        const val BIRDNET_MODEL_ID = "birdnet-v2.4"
        const val BIRDNET_PLUS_MODEL_ID = "birdnetplus-v3.0"
        const val AIY_MODEL_ID = "aiy-birds-v1"

        private const val CATALOG_DIR = "catalog"
        private const val SPECIES_ASSET = "$CATALOG_DIR/species.tsv"
        private const val MODEL_LABELS_PREFIX = "model_labels_"
        private const val TSV_SUFFIX = ".tsv"
    }
}
