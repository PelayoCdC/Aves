package dev.pelayo.aves.core.listening

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * El tile de ajustes rapidos: escuchar sin abrir la app.
 *
 * El gesto que importa es "lo oigo ahora mismo, sacalo ya", y desbloquear,
 * buscar el icono y pulsar cuesta los segundos que el pajaro no espera. Un tile
 * esta a un deslizamiento de distancia.
 *
 * Parar se hace desde aqui directamente. Arrancar NO: un servicio de microfono
 * no puede empezar desde un tile en Android 14 sin excepciones que dependen
 * del fabricante, y ademas la primera vez hace falta el dialogo de permisos,
 * que solo puede abrir una Activity. Asi que el tile abre la app con una
 * peticion de arranque y la pantalla de identificar la atiende.
 */
class ListeningTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val fresh = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = fresh
        fresh.launch {
            ListeningService.state.collect { render(it) }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (ListeningService.state.value.isRunning) {
            ListeningService.stop(this)
            return
        }

        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.putExtra(EXTRA_START_LISTENING, true)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    REQUEST_LAUNCH,
                    launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }

    private fun render(state: ListeningServiceState) {
        val tile = qsTile ?: return
        tile.state = if (state.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.listening_tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !state.isRunning -> getString(R.string.listening_tile_idle)
                state.isWarmingUp -> getString(R.string.listening_notification_warming_up)
                state.detections.isEmpty() -> getString(R.string.listening_notification_empty)
                else -> resources.getQuantityString(
                    R.plurals.listening_notification_species,
                    state.detections.size,
                    state.detections.size,
                )
            }
        }
        tile.updateTile()
    }

    companion object {
        /** Boolean. La Activity que lo reciba debe arrancar la escucha si puede. */
        const val EXTRA_START_LISTENING = "dev.pelayo.aves.core.listening.extra.START_LISTENING"

        private const val REQUEST_LAUNCH = 3
    }
}
