package dev.pelayo.aves.core.data

import android.util.Log
import dev.pelayo.aves.core.common.WikipediaImages
import dev.pelayo.aves.core.common.runSuspendCatching
import dev.pelayo.aves.core.model.Species
import dev.pelayo.aves.core.network.WikipediaClient
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Rellena la ficha de una especie con lo que Wikipedia sabe de ella, una vez.
 *
 * El catalogo trae nombre, familia y orden; la foto y la descripcion no estan
 * en ninguna fuente que se pueda empaquetar en un APK con 11.000 especies. Se
 * piden la primera vez que alguien abre la ficha y se guardan en la fila de la
 * especie; despues la ficha se pinta desde disco y funciona sin red.
 *
 * Un "no hay articulo" tambien se guarda (como resumen vacio) para no volver a
 * preguntar. Un fallo de red NO: se reintenta la proxima vez.
 */
class SpeciesEncyclopedia(
    private val wikipedia: WikipediaClient,
    private val species: SpeciesRepository,
) {
    /**
     * @return la especie con los campos de enciclopedia rellenos, o la misma
     *   si ya los tenia o si la red fallo.
     */
    suspend fun enrich(target: Species): Species {
        if (target.hasEncyclopedia) return target

        val summary = try {
            wikipedia.summaryWithFallback(target.scientificName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Sin red para la ficha de ${target.scientificName}", e)
            return target
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo leer la ficha de ${target.scientificName}", e)
            return target
        }

        val enriched = if (summary == null) {
            // Se deja constancia de que se busco y no habia nada.
            target.copy(summary = "", wikipediaUrl = null, imageUrl = null, imageAttribution = null)
        } else {
            target.copy(
                summary = summary.extract,
                wikipediaUrl = summary.pageUrl,
                // La miniatura TAL COMO VIENE (existe seguro) y no la original, que
                // puede ser una foto de 5.000 px. La pantalla la pide mas grande
                // con WikipediaImages y cae a esta si Commons no la tiene.
                imageUrl = summary.thumbnailUrl ?: summary.originalImageUrl,
                imageAttribution = "Wikimedia Commons · ${summary.language}.wikipedia.org",
            )
        }

        runSuspendCatching {
            species.saveEncyclopedia(
                speciesId = enriched.speciesId,
                imageUrl = enriched.imageUrl,
                wikipediaUrl = enriched.wikipediaUrl,
                summary = enriched.summary,
                imageAttribution = enriched.imageAttribution,
            )
        }.onFailure { Log.w(TAG, "No se pudo guardar la ficha de ${target.scientificName}", it) }

        return enriched
    }

    private companion object {
        const val TAG = "SpeciesEncyclopedia"
    }
}
