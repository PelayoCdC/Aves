package dev.pelayo.aves.core.common

/**
 * Las URLs de las imagenes de Wikimedia Commons: que pedir para que carguen.
 *
 * El resumen de Wikipedia da una miniatura de 330 px y la imagen original, que
 * puede tener 5.000 px y varios megabytes. Lo que NO se puede hacer, y era lo
 * que dejaba la cabecera gris, es pedir una miniatura de otro ancho cambiando el
 * numero de la URL: Commons solo sirve unos pocos anchos ya renderizados (330,
 * 250...) y a los demas responde 400, y a rafagas de peticiones, 429.
 *
 * Asi que: la miniatura tal como viene para la cabecera (pequena, pero carga
 * siempre) y el original, derivado de la URL de la miniatura, para el visor a
 * pantalla completa, donde el usuario ha pedido verla grande y se puede esperar.
 */
object WikipediaImages {

    /**
     * `.../commons/thumb/6/6e/Foo.jpg/330px-Foo.jpg?utm=...`
     *  -> grupo 1: `.../commons/`, grupo 2: `6/6e/Foo.jpg`, grupo 3: el ancho.
     * Vale para `upload.wikimedia.org` y para `thumb.wikimedia.org`, que es el
     * host que usa el resumen de Wikipedia.
     */
    private val THUMB = Regex("""^(https?://[^/]+/wikipedia/[^/]+/)thumb/(.+?)/(?:[a-z0-9-]*?)?(\d+)px-[^/?#]+(?:[?#].*)?$""")

    /** El unico ancho que se pide a Commons: el que ya viene renderizado en el resumen. */
    const val THUMBNAIL_WIDTH_PX = 330

    /** Del mismo fichero, la miniatura estandar de 330 px. Si la URL no es una miniatura, tal cual. */
    fun thumbnail(url: String): String {
        val match = THUMB.find(url) ?: return url
        if (match.groupValues[3].toIntOrNull() == THUMBNAIL_WIDTH_PX) return url
        val (base, file) = match.destructured.let { it.component1() to it.component2() }
        return "${base}thumb/$file/${THUMBNAIL_WIDTH_PX}px-${file.substringAfterLast('/')}"
    }

    /**
     * La imagen original de la que salio una miniatura, o null si la URL no es
     * una miniatura de Commons. Siempre en `upload.wikimedia.org`, que es donde
     * viven los originales, y sin la query de seguimiento.
     */
    fun original(url: String): String? {
        val match = THUMB.find(url) ?: return null
        val base = match.groupValues[1].replace("://thumb.wikimedia.org/", "://upload.wikimedia.org/")
        return base + match.groupValues[2]
    }

    /** true si la URL es una miniatura de Commons. */
    fun isThumbnail(url: String): Boolean = THUMB.containsMatchIn(url)

    /** Para la cabecera de la ficha: la miniatura y, si esa falla, el original. */
    fun headerCandidates(url: String): List<String> = listOfNotNull(thumbnail(url), original(url)).distinct()

    /** Para verla a pantalla completa: el original y, si falla, la miniatura. */
    fun viewerCandidates(url: String): List<String> = listOfNotNull(original(url), thumbnail(url)).distinct()
}
