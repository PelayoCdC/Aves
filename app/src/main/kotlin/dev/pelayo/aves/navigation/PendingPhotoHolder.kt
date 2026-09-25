package dev.pelayo.aves.navigation

import androidx.lifecycle.ViewModel
import dev.pelayo.aves.feature.camera.ImageIdentification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * La foto identificada que espera a que el usuario la confirme desde la ficha.
 *
 * Vive en un ViewModel de la actividad y no en un `remember` de la navegacion:
 * con `remember`, girar el movil recreaba todo y la tarjeta de "¿es esta?"
 * desaparecia con la foto dentro. No sobrevive a la muerte del proceso (lleva
 * bitmaps, que no caben en un Bundle), y eso es aceptable: la foto sigue en la
 * galeria o se vuelve a hacer.
 *
 * Cada foto lleva una ficha propia en la ruta (`?photo=<token>`). El token es
 * aleatorio y no un contador desde 1: tras morir el proceso la pila de
 * navegacion se restaura con sus tokens viejos, y un contador que vuelve a
 * empezar podia hacer que una ficha antigua reclamase una foto nueva.
 */
class PendingPhotoHolder : ViewModel() {

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending: StateFlow<Pending?> = _pending.asStateFlow()

    /** Guarda [identification] a la espera y devuelve el token para la ruta. */
    fun hold(identification: ImageIdentification): Int {
        val token = Random.nextInt(1, Int.MAX_VALUE)
        _pending.value = Pending(token, identification)
        return token
    }

    /** La foto que espera en la ficha con [token], si es esa. */
    fun awaiting(token: Int): ImageIdentification? =
        _pending.value?.takeIf { token != 0 && it.token == token }?.identification

    /**
     * Saca la foto de la espera y la devuelve, solo una vez: un doble toque en
     * "confirmar" ya no la guarda dos veces, porque el segundo no encuentra nada.
     */
    fun take(token: Int): ImageIdentification? {
        val current = _pending.value?.takeIf { it.token == token } ?: return null
        return if (_pending.compareAndSet(current, null)) current.identification else null
    }

    fun discard() {
        _pending.value = null
    }

    data class Pending(val token: Int, val identification: ImageIdentification)
}
