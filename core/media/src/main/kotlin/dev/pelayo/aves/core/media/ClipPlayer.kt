package dev.pelayo.aves.core.media

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dev.pelayo.aves.core.audio.PlaybackSignal
import java.io.File

/**
 * Un unico reproductor para toda la pantalla.
 *
 * La regla de "solo uno sonando" no se vigila, se hace imposible: como solo hay
 * un [ExoPlayer], cargar el recorte de otra tarjeta desaloja al anterior por
 * construccion. Un reproductor por tarjeta permitiria dos cantos a la vez, que
 * es exactamente lo que no deja juzgar ninguno de los dos.
 *
 * Cada arranque y parada se anuncia en [PlaybackSignal]: la escucha en segundo
 * plano descarta las ventanas capturadas mientras suena algo, porque si no el
 * microfono oye el recorte por el altavoz y el modelo "detecta" lo que acaba de
 * reproducirse.
 *
 * [player] puede ser null cuando no hay reproductor disponible (una
 * previsualizacion del IDE, o un dispositivo que no pudo crearlo). En ese caso
 * el estado se comporta como si no hubiera audio: nada suena y los botones se
 * quedan desactivados, en lugar de arrastrar la pantalla entera al fallo.
 */
@Stable
class ClipPlayerState internal constructor(
    private val player: ExoPlayer?,
    private val clipBaseDir: File?,
) {

    /** Observacion que suena ahora mismo, o null si hay silencio. */
    var playingKey: Long? by mutableStateOf<Long?>(null)
        private set

    /**
     * Si el recorte de [mediaPath] existe y se puede reproducir.
     *
     * Toca disco (`exists`), asi que conviene memorizarlo en el sitio de la
     * llamada en vez de preguntarlo en cada recomposicion.
     */
    fun canPlay(mediaPath: String?): Boolean =
        player != null && hasPlayableClip(mediaPath, clipBaseDir)

    fun isPlaying(key: Long): Boolean = playingKey == key

    /**
     * Toque en el boton de la observacion [key]: arranca, cambia o para.
     *
     * El fichero se vuelve a resolver aqui y no se confia en lo que dijo
     * [canPlay]: entre que se pinto la tarjeta y el usuario pulso, el recorte
     * puede haber desaparecido.
     */
    fun toggle(key: Long, mediaPath: String?) {
        val exo = player ?: return

        when (playbackActionFor(playingKey, key)) {
            PlaybackAction.STOP -> stop()

            // Parar antes de cargar no es imprescindible (`setMediaItem` ya
            // desaloja lo anterior), pero deja la regla escrita donde se lee en
            // vez de delegarla en el comportamiento interno de ExoPlayer.
            PlaybackAction.SWITCH -> {
                exo.stop()
                start(exo, key, mediaPath)
            }

            PlaybackAction.START -> start(exo, key, mediaPath)
        }
    }

    /** Corta la reproduccion, venga de donde venga. */
    fun stop() {
        player?.stop()
        player?.clearMediaItems()
        playingKey = null
        PlaybackSignal.playbackStopped()
    }

    /**
     * Corta solo si lo que suena es [key].
     *
     * Lo usa la bandeja al despachar una tarjeta: si desaparece de la lista
     * mientras suena, el canto se quedaria huerfano sin nada en pantalla que lo
     * pueda parar.
     */
    fun stopIfPlaying(key: Long) {
        if (playingKey == key) stop()
    }

    private fun start(exo: ExoPlayer, key: Long, mediaPath: String?) {
        val file = resolveClipFile(mediaPath, clipBaseDir)
        if (file == null) {
            // El recorte ya no esta: se degrada a silencio. La tarjeta lo
            // reflejara en cuanto vuelva a preguntar por `canPlay`.
            stop()
            return
        }

        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.play()
        playingKey = playbackAfter(playingKey, key)
        PlaybackSignal.playbackStarted()
    }

    /** El reproductor llego al final o fallo: la interfaz vuelve a "parado". */
    internal fun onPlaybackFinished() {
        playingKey = null
        PlaybackSignal.playbackStopped()
    }
}

/**
 * Crea el reproductor y lo suelta al salir de la composicion.
 *
 * Liberarlo no es opcional: un [ExoPlayer] vivo retiene un descodificador
 * (recurso escaso, compartido con la grabacion) y el foco de audio, asi que uno
 * olvidado deja al usuario sin poder escuchar y sin saber por que.
 *
 * @param clipBaseDir raiz de los recortes. Por defecto `filesDir`, que es donde
 *   los deja `ClipStorage`.
 */
@Composable
fun rememberClipPlayer(clipBaseDir: File? = null): ClipPlayerState {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current
    val baseDir = clipBaseDir ?: remember(context) { context.filesDir }

    val player = remember(context, isPreview) {
        // En una previsualizacion del IDE no hay servicios de media reales;
        // intentar construirlo ahi solo consigue que la preview no se pinte.
        if (isPreview) {
            null
        } else {
            runCatching {
                ExoPlayer.Builder(context)
                    // Con el foco de audio pedido, la musica del usuario se
                    // silencia mientras suena el recorte. Sin el, el canto se
                    // mezclaria con lo que estuviera sonando y no habria forma
                    // de juzgar la deteccion.
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(C.USAGE_MEDIA)
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .build(),
                        /* handleAudioFocus = */ true,
                    )
                    .build()
            }.getOrNull()
        }
    }

    val state = remember(player, baseDir) { ClipPlayerState(player, baseDir) }

    DisposableEffect(player, state) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) state.onPlaybackFinished()
            }

            // La senal para la escucha sigue a lo que suena DE VERDAD. Una
            // llamada o una notificacion quitan el foco de audio y ExoPlayer se
            // pausa sin llegar a STATE_ENDED: la senal se quedaba en "sonando" y
            // la escucha descartaba todas las ventanas hasta salir de la pantalla.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) PlaybackSignal.playbackStarted() else PlaybackSignal.playbackStopped()
            }

            override fun onPlayerError(error: PlaybackException) {
                // Un recorte corrupto o borrado entre la comprobacion y el
                // prepare no puede tumbar la pantalla: se vuelve a "parado" y el
                // usuario puede seguir revisando.
                state.onPlaybackFinished()
            }
        }
        player?.addListener(listener)

        onDispose {
            // Si se sale de la pantalla con algo sonando, la senal de
            // reproduccion tiene que apagarse igual: si no, la escucha
            // descartaria ventanas para siempre.
            state.onPlaybackFinished()
            player?.removeListener(listener)
            player?.release()
        }
    }

    return state
}

/**
 * Icono del boton de escucha: triangulo parado, cuadrado sonando.
 *
 * El cuadrado se dibuja a mano porque el set basico de Material no trae icono de
 * parar, y arrastrar `material-icons-extended` entero por una forma de cuatro
 * lados no compensa.
 */
@Composable
fun ClipPlayIcon(
    isPlaying: Boolean,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val description = contentDescription

    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (isPlaying) {
            Box(
                Modifier
                    .size(size * 0.55f)
                    // El color sale del contenedor, asi el cuadrado se apaga solo
                    // cuando el boton esta desactivado.
                    .background(LocalContentColor.current, RoundedCornerShape(2.dp))
                    .semantics { if (description != null) this.contentDescription = description },
            )
        } else {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = description,
                modifier = Modifier.size(size),
            )
        }
    }
}
