package dev.pelayo.aves.core.ml.api

/**
 * Identidad y condiciones legales de un modelo, disponibles en tiempo de
 * ejecucion.
 *
 * Que la licencia sea un dato y no un comentario es intencionado y tiene dos
 * consecuencias practicas:
 *
 *  1. La UI puede mostrar [attributionText] automaticamente. CC BY-NC-SA
 *     *exige* atribucion; olvidarla es incumplir la licencia, no un descuido
 *     estetico.
 *  2. Se puede escribir un test que falle el build del flavor `open` si algun
 *     clasificador registrado tiene [allowsCommercialUse] a false. Es la unica
 *     defensa fiable contra publicar por accidente un modelo no comercial.
 */
data class ModelDescriptor(
    val modelId: String,
    val displayName: String,
    /** Identificador SPDX, p. ej. "CC-BY-NC-SA-4.0" o "Apache-2.0". */
    val licenseSpdx: String,
    val allowsCommercialUse: Boolean,
    val attributionText: String,
    val speciesCount: Int,
)
