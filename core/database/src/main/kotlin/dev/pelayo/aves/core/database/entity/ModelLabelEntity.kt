package dev.pelayo.aves.core.database.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * El cross-walk que hace intercambiables los modelos.
 *
 * Cada modelo tiene su propio orden de etiquetas y su propia taxonomia: BirdNET
 * tiene 6.522 clases, el clasificador de imagen de Google 965, y un Perch
 * regional futuro tendria otras tantas. Esta tabla traduce
 * (modelo, indice de tensor) -> especie canonica.
 *
 * Cambiar de modelo es poblar otra tabla, no tocar logica. Es la pieza que
 * sostiene la promesa de "no reescribir la app para cambiar de clasificador".
 *
 * [speciesId] nulo significa clase deliberadamente no mapeada: ruido, perro,
 * sirena, o un taxon que no seguimos. No es un error, es informacion.
 */
@Entity(
    tableName = "model_labels",
    primaryKeys = ["modelId", "labelIndex"],
    indices = [Index("speciesId"), Index("modelId")],
)
data class ModelLabelEntity(
    /** "birdnet-v2.4", "aiy-birds-v1", "perch-v2-eu700"... */
    val modelId: String,
    /** Indice en el tensor de salida del modelo. */
    val labelIndex: Int,
    val speciesId: String?,
    /** Etiqueta cruda tal cual la publica el modelo, para poder auditar el mapeo. */
    val rawLabel: String,
)
