package dev.pelayo.aves.core.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Si la propia app esta reproduciendo audio ahora mismo.
 *
 * Existe por un fallo de autoengano: reproducir el recorte de un mirlo en la
 * bandeja de revision mientras el servicio escucha hace que el microfono oiga
 * ese mirlo por el altavoz, y el modelo lo "detecta" con toda confianza. La
 * escucha consulta esta senal y descarta las ventanas capturadas mientras algo
 * suena.
 *
 * Vive en `core:audio` porque es el unico modulo que ven a la vez quien
 * reproduce (`core:media`) y quien captura (`core:listening`), y no tiene mas
 * dependencias que las corrutinas. Es estado global a proposito: solo hay un
 * altavoz y un microfono.
 */
object PlaybackSignal {

    private val _isPlaying = MutableStateFlow(false)

    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Instante en que dejo de sonar lo ultimo. La escucha descarta tambien la
     * ventana siguiente al final: sus 3 s de audio contienen la cola de lo que
     * acababa de sonar.
     */
    @Volatile
    var lastStoppedAtMillis: Long = 0L
        private set

    fun playbackStarted() {
        _isPlaying.value = true
    }

    fun playbackStopped() {
        if (_isPlaying.value) lastStoppedAtMillis = System.currentTimeMillis()
        _isPlaying.value = false
    }

    /** Si una ventana que termina en [windowEndMillis] puede llevar audio propio dentro. */
    fun contaminates(windowEndMillis: Long, windowMillis: Long): Boolean =
        _isPlaying.value || windowEndMillis - lastStoppedAtMillis < windowMillis
}
