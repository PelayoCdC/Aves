package dev.pelayo.aves.core.audio

import android.content.Context
import android.media.AudioManager

/**
 * Que fuentes de microfono soporta de verdad este dispositivo.
 *
 * `MediaRecorder.AudioSource.UNPROCESSED` tiene una trampa documentada: si el
 * dispositivo no lo implementa, NO falla al abrirlo, se comporta como DEFAULT.
 * Es decir, la app creeria estar leyendo audio crudo mientras el sistema le
 * aplica control de ganancia y supresion de ruido afinados para voz, que es
 * justo lo que destroza los cantos agudos. Y el medidor de nivel diria "sin
 * procesar" con toda tranquilidad.
 *
 * La unica forma fiable de saberlo es la propiedad que Android publica para
 * eso. Se consulta una vez y se le pasa a [AudioCaptureEngine].
 */
object AudioSourceSupport {

    /** true solo si el sistema declara explicitamente que UNPROCESSED es real aqui. */
    fun unprocessedSupported(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        return manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.equals("true", ignoreCase = true) == true
    }
}
