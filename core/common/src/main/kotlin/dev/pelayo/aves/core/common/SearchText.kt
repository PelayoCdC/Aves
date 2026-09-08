package dev.pelayo.aves.core.common

import java.text.Normalizer

/**
 * Normalizacion de texto para buscar: minusculas y sin diacriticos.
 *
 * Vive en `core` porque la usan dos sitios que tienen que coincidir
 * exactamente: la columna `searchName` que se escribe al sembrar el catalogo y
 * la consulta que teclea el usuario. Si una normaliza y la otra no, "cárabo"
 * no encuentra "carabo" y nadie sabe por que.
 */
object SearchText {

    private val DIACRITICS = Regex("\\p{Mn}+")
    private val WHITESPACE = Regex("\\s+")

    fun fold(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace(WHITESPACE, " ")
}
