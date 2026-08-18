package dev.pelayo.aves.feature.camera

import kotlin.math.roundToInt

/**
 * Aritmetica de la preparacion de la imagen, sin una sola clase de Android.
 *
 * Vive aparte a proposito: los dos errores que de verdad hacen dano aqui -no
 * rotar y deformar el encuadre- no revientan la app, solo empeoran la
 * clasificacion en silencio. Un fallo silencioso solo se detecta con tests, y
 * los tests solo son baratos si esta parte no necesita ni emulador ni
 * Robolectric.
 */

/** Un recorte cuadrado dentro de una imagen, en pixeles. */
data class SquareCrop(
    val left: Int,
    val top: Int,
    val size: Int,
) {
    /** Primer pixel ya fuera del recorte por la derecha. */
    val right: Int get() = left + size

    /** Primer pixel ya fuera del recorte por abajo. */
    val bottom: Int get() = top + size
}

/**
 * Recorte cuadrado centrado de una imagen de [width] x [height].
 *
 * Hace falta porque el atajo evidente -pasar el 4:3 de la camara directamente a
 * 224x224- estira al ave un 33 % en un eje. El modelo se entreno con aves sin
 * deformar, asi que esa distorsion no da error: da peores candidatos y nadie se
 * entera.
 *
 * Centrado y no otra cosa porque es lo unico que se puede prometer sin
 * detector: el usuario apunta al ave, y lo que apunta queda en el centro.
 */
fun centerSquareCrop(width: Int, height: Int): SquareCrop {
    require(width > 0 && height > 0) {
        "La imagen no puede medir ${width}x$height"
    }
    val size = minOf(width, height)
    // Division entera: si lo que sobra es impar, el recorte se corre medio pixel
    // hacia el origen. Es irrelevante para el modelo y evita inventarse una
    // regla de redondeo que luego habria que recordar.
    return SquareCrop(
        left = (width - size) / 2,
        top = (height - size) / 2,
        size = size,
    )
}

/**
 * Cuartos de vuelta en sentido horario que hay que aplicar, de 0 a 3.
 *
 * `ImageProxy.imageInfo.rotationDegrees` documenta devolver 0, 90, 180 o 270,
 * pero llega de la capa de HAL del fabricante y aqui no se le concede el
 * beneficio de la duda: cualquier entero entra, incluidos negativos y valores
 * por encima de 360. Se redondea al cuarto de vuelta mas proximo porque, ante
 * un valor raro, girar de mas es infinitamente mejor que no girar: el modelo
 * nunca vio un ave tumbada.
 */
fun quarterTurnsOf(degrees: Int): Int {
    val turns = (degrees / 90.0).roundToInt()
    // rem en Kotlin conserva el signo del dividendo, de ahi el +4.
    return ((turns % 4) + 4) % 4
}

/** Los mismos grados llevados al rango canonico: 0, 90, 180 o 270. */
fun normalizeRotationDegrees(degrees: Int): Int = quarterTurnsOf(degrees) * 90

/**
 * true si hay que girar de verdad.
 *
 * Se consulta antes de construir la matriz porque rotar cero grados en Android
 * no es gratis: copia el bitmap entero igual.
 */
fun isRotationNeeded(degrees: Int): Boolean = quarterTurnsOf(degrees) != 0
