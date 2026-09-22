package dev.pelayo.aves.core.common

/**
 * Los tamanos de las imagenes de Wikimedia Commons.
 *
 * La miniatura que da el resumen de Wikipedia tiene 320 px de ancho, que en una
 * ficha a pantalla completa se ve borrosa; y la imagen original puede tener
 * 5.000 px y varios megabytes, que para un movil es tirar datos. Pero la URL de
 * una miniatura lleva el ancho dentro (`.../330px-Common_Blackbird.jpg`) y
 * Commons sirve cualquier otro ancho con solo cambiar ese numero. Asi que se
 * guarda la miniatura y se pide del tamano que haga falta en cada sitio.
 */
object WikipediaImages {

    private val WIDTH_SEGMENT = Regex("/(\\d+)px-")

    /** Lo que se pide para la cabecera de la ficha. */
    const val HEADER_WIDTH_PX = 800

    /** Lo que se pide para verla a pantalla completa. */
    const val VIEWER_WIDTH_PX = 1600

    /**
     * La misma imagen, a [widthPx] de ancho. Si la URL no es una miniatura de
     * Commons (no lleva el ancho dentro) se devuelve tal cual: no hay nada que
     * cambiar, y romperla seria peor.
     */
    fun sized(url: String, widthPx: Int): String {
        require(widthPx > 0) { "El ancho debe ser positivo" }
        return WIDTH_SEGMENT.replaceFirst(url, "/${widthPx}px-")
    }

    /** true si la URL es una miniatura de Commons que admite cambiar de tamano. */
    fun isResizable(url: String): Boolean = WIDTH_SEGMENT.containsMatchIn(url)
}
