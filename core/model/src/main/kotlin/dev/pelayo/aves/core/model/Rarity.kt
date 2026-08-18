package dev.pelayo.aves.core.model

/**
 * La rareza NO es una propiedad de la especie: lo es del trio
 * (especie, lugar, mes). Un mirlo es trivial en Madrid y notable en Islandia.
 *
 * Por eso los avistamientos congelan el tier del momento de la captura en vez de
 * calcularlo al vuelo: si el usuario se muda, sus capturas antiguas no deben
 * cambiar de valor retroactivamente.
 */
enum class RarityTier(val level: Int) {
    VERY_COMMON(0),
    COMMON(1),
    UNCOMMON(2),
    RARE(3),
    EXCEPTIONAL(4);

    companion object {
        /**
         * Deriva el tier a partir de la cuota de ocurrencias de la especie sobre
         * el total de observaciones de aves en esa celda y mes (datos de GBIF).
         *
         * Los cortes son logaritmicos a proposito: las distribuciones de
         * abundancia son log-normales, y con umbrales lineales el 95% de las
         * especies caeria en un solo tier y la coleccion no tendria gracia.
         *
         * Son un punto de partida. Ajustarlos con datos reales de tu zona es
         * diseno de juego, no ingenieria.
         */
        fun fromShare(share: Float): RarityTier = when {
            share >= 1e-2f -> VERY_COMMON
            share >= 1e-3f -> COMMON
            share >= 1e-4f -> UNCOMMON
            share > 0f -> RARE
            else -> EXCEPTIONAL
        }

        fun fromLevel(level: Int): RarityTier =
            entries.firstOrNull { it.level == level } ?: EXCEPTIONAL
    }
}
