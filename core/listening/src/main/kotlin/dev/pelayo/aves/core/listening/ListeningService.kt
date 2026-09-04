package dev.pelayo.aves.core.listening

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.text.format.DateFormat
import android.util.Log
import dev.pelayo.aves.core.audio.PcmScaling
import dev.pelayo.aves.core.data.ObservationRepository
import dev.pelayo.aves.core.data.RegionRepository
import dev.pelayo.aves.core.ml.api.AudioBirdClassifier
import dev.pelayo.aves.core.model.Coordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Date

/**
 * El corte automatico por tiempo.
 *
 * Existe porque el usuario NUNCA lo pide: arranca la escucha, se distrae y se va
 * a cenar. Sin limite, el microfono y una inferencia cada 800 ms se comen la
 * bateria durante toda la noche, y a la manana siguiente el movil esta muerto y
 * la culpa es de esta app. Un limite generoso pero finito convierte ese fallo en
 * "se paro sola hace horas", que es una decepcion pequena y recuperable.
 *
 * Es una clase aparte y no un `withTimeout` suelto dentro del servicio para que
 * la aritmetica del limite se pueda probar en la JVM, sin Android delante.
 *
 * @param requestedDurationMillis lo que pidio quien arranco. Se recorta a
 *   [MIN_DURATION_MILLIS]..[MAX_DURATION_MILLIS] en vez de rechazarse: un valor
 *   absurdo en un extra de Intent no debe impedir escuchar.
 */
class ListeningDeadline(
    val startedAtMillis: Long,
    requestedDurationMillis: Long = DEFAULT_DURATION_MILLIS,
) {
    val durationMillis: Long =
        requestedDurationMillis.coerceIn(MIN_DURATION_MILLIS, MAX_DURATION_MILLIS)

    val expiresAtMillis: Long = startedAtMillis + durationMillis

    /** Lo que queda, nunca negativo: se pasa tal cual a `withTimeoutOrNull`. */
    fun remainingMillis(nowMillis: Long): Long =
        (expiresAtMillis - nowMillis).coerceAtLeast(0L)

    fun hasExpired(nowMillis: Long): Boolean = remainingMillis(nowMillis) == 0L

    companion object {
        /**
         * Una hora.
         *
         * Es aproximadamente lo que dura el coro del amanecer, que es el caso de
         * uso que justifica todo este servicio. Mas corto obligaria a rearmar la
         * escucha en mitad de lo interesante; mucho mas largo deja de parecerse
         * a nada que un usuario haga a proposito.
         */
        const val DEFAULT_DURATION_MILLIS = 60L * 60_000L

        /** Por debajo de esto no da tiempo ni a confirmar una deteccion. */
        const val MIN_DURATION_MILLIS = 60_000L

        /**
         * Ocho horas es el techo absoluto. Aun queriendo dejarlo escuchando toda
         * la noche, eso es un descuido, no una intencion: quien lo quiera de
         * verdad puede volver a arrancarlo por la manana.
         */
        const val MAX_DURATION_MILLIS = 8L * 60L * 60_000L
    }
}

/**
 * Por que no hay escucha. Se distinguen porque cada una se arregla distinto y la
 * UI tiene que poder decir cual es sin interpretar un mensaje de excepcion.
 */
enum class ListeningFailure {
    /** Nadie publico [ListeningService.dependencies]. Es un fallo de montaje. */
    NOT_CONFIGURED,

    /** El flavor compilado no incluye ningun modelo de audio (caso de `open`). */
    NO_MODEL_IN_BUILD,

    /** Falta RECORD_AUDIO: el servicio se arranco sin pasar por el dialogo. */
    MICROPHONE_PERMISSION_MISSING,

    /** El sistema no dejo pasar a primer plano, normalmente por arrancar desde atras. */
    FOREGROUND_START_DENIED,

    /** El motor se paro solo: modelo ausente, microfono ocupado, E/S. */
    ENGINE_FAILED,
}

/**
 * Lo que la UI necesita saber de una escucha en segundo plano.
 *
 * Es un espejo del estado del servicio, no su fuente de verdad: se reconstruye
 * entero en cada arranque.
 */
data class ListeningServiceState(
    val isRunning: Boolean = false,
    val isWarmingUp: Boolean = false,
    val sessionId: Long? = null,
    val detections: List<StableDetection> = emptyList(),
    val windowsProcessed: Int = 0,
    val windowsSkipped: Int = 0,
    val lastInferenceMillis: Long = 0L,
    /** Nivel de audio de la ultima ventana, 0..1 sobre fondo de escala. */
    val levelRms: Float = 0f,
    /** Fuente de microfono en uso, para poder diagnosticar un "no capta nada". */
    val audioSource: String? = null,
    val startedAtMillis: Long = 0L,
    /** Instante en el que se cortara sola. 0 si no hay escucha en curso. */
    val stopsAtMillis: Long = 0L,
    /** Se paro por agotar el limite de tiempo y no porque nadie lo pidiera. */
    val stoppedByTimeout: Boolean = false,
    val failure: ListeningFailure? = null,
    val failureDetail: String? = null,
)

/**
 * De donde saca el servicio lo que no puede construir el mismo.
 *
 * Un `Service` lo instancia el sistema con el constructor vacio, asi que no hay
 * forma de inyectarle nada por parametro. Y el clasificador ademas depende del
 * flavor compilado (`personal` trae BirdNET, `open` no trae ninguno), cosa que
 * un modulo `core` no puede ni debe saber.
 *
 * Lo rellena la Application al arrancar. Cuando entre Hilt esto se sustituye por
 * `@AndroidEntryPoint` y desaparece.
 */
interface ListeningDependencies {
    /**
     * Crea un clasificador nuevo para esta escucha, o null si el flavor
     * compilado no incluye ninguno. El servicio lo cierra al terminar: retiene
     * un modelo de ~25 MB mapeado en memoria.
     */
    fun createClassifier(): AudioBirdClassifier?

    val observationRepository: ObservationRepository?

    val regionRepository: RegionRepository?

    val clipsDirectory: File?

    /** Se llama una vez por sesion, fuera del hilo principal. */
    fun currentLocation(): Coordinates?
}

/**
 * Ejecuta [ListeningEngine] en primer plano, fuera del ciclo de vida de
 * cualquier pantalla.
 *
 * Este servicio es la razon de ser del modulo. Con la escucha dentro de un
 * ViewModel, apagar la pantalla o cambiar de app cortaba la identificacion, y
 * eso rompe el caso de uso mas interesante que tiene la app: dejar el movil
 * escuchando un rato largo mientras haces otra cosa.
 *
 * **Estado observable: [state], un `StateFlow` estatico, y no un binder.** Es
 * una decision deliberada:
 *
 *  - Solo puede haber una escucha a la vez porque solo hay un microfono, asi que
 *    el estado ya es unico por proceso de hecho. Un binder no aportaria
 *    aislamiento real, solo ceremonia.
 *  - La UI necesita saber si hay una escucha en curso ANTES de conectarse a
 *    nada, por ejemplo al volver a la pantalla de identificar. Con un binder eso
 *    obliga a `bindService` + `ServiceConnection` + un estado "conectando" que no
 *    representa nada del dominio.
 *  - El precio es estado mutable global, que se paga reseteandolo al parar y
 *    manteniendolo de solo lectura hacia fuera.
 */
class ListeningService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var listenJob: Job? = null

    private val notifications: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    /** El estado no se expone por binder: ver el KDoc de la clase. */
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // LO PRIMERO Y SIN EXCEPCIONES. Desde Android 8 el sistema mata el
        // proceso con ANR si un servicio arrancado con startForegroundService no
        // llama a startForeground en unos segundos. Se llama incluso en la ruta
        // de parada, donde el servicio podria no estar aun en primer plano.
        if (!enterForeground()) {
            stopEverything(
                ListeningServiceState(failure = ListeningFailure.FOREGROUND_START_DENIED),
            )
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            // Se conservan las detecciones: el usuario acaba de parar y lo
            // primero que quiere ver al abrir la app es lo que ha cazado.
            stopEverything(_state.value.copy(isRunning = false, isWarmingUp = false))
            return START_NOT_STICKY
        }

        if (listenJob?.isActive == true) return START_NOT_STICKY

        val requested = intent?.getLongExtra(
            EXTRA_MAX_DURATION_MILLIS,
            ListeningDeadline.DEFAULT_DURATION_MILLIS,
        ) ?: ListeningDeadline.DEFAULT_DURATION_MILLIS

        startListening(ListeningDeadline(System.currentTimeMillis(), requested))

        // START_NOT_STICKY a proposito: si el sistema mata el proceso, NO debe
        // resucitar el microfono por su cuenta. Grabar sin que el usuario lo
        // haya pedido en ese momento es lo peor que podria hacer esta app.
        return START_NOT_STICKY
    }

    private fun startListening(deadline: ListeningDeadline) {
        val dependencies = ListeningService.dependencies
        if (dependencies == null) {
            Log.e(TAG, "No hay dependencias registradas; la Application no las publico")
            stopEverything(ListeningServiceState(failure = ListeningFailure.NOT_CONFIGURED))
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // El servicio puede arrancarse desde una notificacion o un atajo,
            // donde no ha pasado por el dialogo de permisos de la pantalla.
            stopEverything(
                ListeningServiceState(failure = ListeningFailure.MICROPHONE_PERMISSION_MISSING),
            )
            return
        }

        _state.value = ListeningServiceState(
            isRunning = true,
            isWarmingUp = true,
            startedAtMillis = deadline.startedAtMillis,
            stopsAtMillis = deadline.expiresAtMillis,
        )
        refreshNotification()

        listenJob = scope.launch {
            val classifier = dependencies.createClassifier()
            if (classifier == null) {
                stopEverything(ListeningServiceState(failure = ListeningFailure.NO_MODEL_IN_BUILD))
                return@launch
            }

            try {
                val engine = ListeningEngine(
                    classifier = classifier,
                    observationRepository = dependencies.observationRepository,
                    regionRepository = dependencies.regionRepository,
                    clipsDirectory = dependencies.clipsDirectory,
                    scaling = INPUT_SCALING,
                )
                val location = runCatching { dependencies.currentLocation() }
                    .onFailure { Log.w(TAG, "No se pudo obtener la ubicacion", it) }
                    .getOrNull()

                // El corte por tiempo es una cancelacion normal, asi que el
                // `finally` del motor cierra la sesion igual que si el usuario
                // hubiera pulsado parar.
                val completed = withTimeoutOrNull(
                    deadline.remainingMillis(System.currentTimeMillis()),
                ) {
                    engine.listen(location).collect { event -> reduce(event) }
                }

                if (completed == null) {
                    Log.i(TAG, "Escucha cortada por el limite de ${deadline.durationMillis} ms")
                    _state.update { it.copy(stoppedByTimeout = true) }
                }
            } finally {
                // Sin esto se queda vivo un MappedByteBuffer de ~25 MB hasta que
                // el sistema mate el proceso.
                runCatching { classifier.close() }
                stopEverything(_state.value.copy(isRunning = false, isWarmingUp = false))
            }
        }
    }

    /** Pliega los eventos del motor sobre el estado observable. */
    private fun reduce(event: ListeningEvent) {
        when (event) {
            is ListeningEvent.WarmingUp -> {
                _state.update { it.copy(isWarmingUp = true) }
                refreshNotification()
            }

            is ListeningEvent.Started -> {
                _state.update { it.copy(isWarmingUp = false, sessionId = event.sessionId) }
                refreshNotification()
            }

            is ListeningEvent.Window -> {
                // Sin refrescar la notificacion: estos contadores cambian 1,25
                // veces por segundo y no se ven en ella.
                _state.update {
                    it.copy(
                        windowsProcessed = event.processed,
                        windowsSkipped = event.skipped,
                        lastInferenceMillis = event.inferenceMillis,
                        levelRms = event.levelRms,
                        audioSource = event.sourceName ?: it.audioSource,
                    )
                }
            }

            is ListeningEvent.Detections -> {
                _state.update { it.copy(detections = event.detections) }
                refreshNotification()
            }

            is ListeningEvent.Failed -> {
                _state.update {
                    it.copy(
                        failure = ListeningFailure.ENGINE_FAILED,
                        failureDetail = event.cause.message,
                    )
                }
                refreshNotification()
            }
        }
    }

    /**
     * Deja el estado en [finalState], suelta el primer plano y se para.
     *
     * Se puede llamar desde cualquier hilo y mas de una vez: parar dos veces es
     * un no-op, y es facil que pase (el usuario pulsa parar justo cuando salta
     * el corte por tiempo).
     */
    private fun stopEverything(finalState: ListeningServiceState) {
        _state.value = finalState
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        // REMOVE y no DETACH: una notificacion de "estoy escuchando" que sigue
        // ahi cuando ya no se escucha es peor que no tener ninguna.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Cancelar dispara el `finally` del motor, que cierra la sesion en
        // NonCancellable. El proceso sigue vivo lo suficiente para esa escritura;
        // si el sistema lo mata antes, la sesion se queda abierta y la limpia
        // `discardEmptySessions` en el siguiente arranque.
        listenJob = null
        scope.cancel()
        if (_state.value.isRunning) _state.value = ListeningServiceState()
        super.onDestroy()
    }

    /**
     * @return false si el sistema no permite pasar a primer plano ahora mismo.
     */
    private fun enterForeground(): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Obligatorio desde Android 14: sin declarar el tipo, el sistema
            // lanza en vez de arrancar.
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        true
    } catch (e: SecurityException) {
        // Android 14+: falta RECORD_AUDIO o el permiso de servicio de microfono.
        Log.e(TAG, "El sistema rechazo el servicio de microfono", e)
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException (Android 12+) al intentar
        // arrancar desde segundo plano. Se captura por la superclase para no
        // tener que referenciar una clase que no existe antes de API 31.
        Log.e(TAG, "No se puede arrancar un servicio en primer plano ahora", e)
        false
    }

    private fun createChannel() {
        // Importancia baja: es un aviso permanente de que el microfono esta
        // abierto, no algo que deba sonar. Con IMPORTANCE_DEFAULT haria ruido al
        // arrancar, justo encima del audio que se quiere analizar.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.listening_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.listening_channel_description)
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    private fun refreshNotification() {
        // notify() sobre el mismo id actualiza la notificacion del servicio en
        // vez de crear otra.
        runCatching { notifications.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val current = _state.value
        val count = current.detections.size

        val text = when {
            current.failure != null -> getString(R.string.listening_notification_error)
            // El `!isRunning` cubre la primera notificacion de todas, la que hay
            // que publicar en el mismo instante de arrancar para no incumplir el
            // plazo de startForeground: entonces el estado aun esta vacio.
            current.isWarmingUp || !current.isRunning ->
                getString(R.string.listening_notification_warming_up)

            count == 0 -> getString(R.string.listening_notification_empty)
            else -> resources.getQuantityString(
                R.plurals.listening_notification_species,
                count,
                count,
            )
        }

        val builder = Notification.Builder(this, CHANNEL_ID)
            // Drawable del sistema para no arrastrar recursos graficos a un
            // modulo que no tiene ninguno. Es un microfono, que es exactamente
            // lo que esta pasando.
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.listening_notification_title))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            // Sin esto, cada actualizacion volveria a llamar la atencion.
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(current.startedAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis())
            // El cronometro le dice al usuario cuanto lleva escuchando sin que
            // haya que repintar la notificacion cada segundo.
            .setUsesChronometer(true)
            .addAction(stopAction())

        // La hora de corte, en absoluto y no en "quedan N minutos": la
        // notificacion solo se repinta cuando cambian las detecciones, y un
        // contador relativo se quedaria mintiendo entre repintados.
        if (current.stopsAtMillis > 0L) {
            builder.setSubText(
                getString(
                    R.string.listening_notification_until,
                    DateFormat.getTimeFormat(this).format(Date(current.stopsAtMillis)),
                ),
            )
        }

        launchAppIntent()?.let(builder::setContentIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Que aparezca ya y no diez segundos despues: si el usuario no ve
            // enseguida que el microfono esta abierto, la app parece opaca.
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun stopAction(): Notification.Action {
        val intent = Intent(this, ListeningService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(
            this,
            REQUEST_STOP,
            intent,
            // IMMUTABLE porque nadie debe poder reescribir este intent, y es
            // obligatorio desde Android 12.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            // Icono del sistema: la mayoria de superficies ya no lo pintan, pero
            // el constructor sin icono esta obsoleto.
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.listening_notification_stop),
            pending,
        ).build()
    }

    /** Abrir la app al tocar la notificacion, sin que `core` conozca su Activity. */
    private fun launchAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "ListeningService"

        private const val CHANNEL_ID = "listening"
        private const val NOTIFICATION_ID = 1001
        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2

        const val ACTION_STOP = "dev.pelayo.aves.core.listening.action.STOP"

        /** Long, milisegundos. Ver [ListeningDeadline]. */
        const val EXTRA_MAX_DURATION_MILLIS = "dev.pelayo.aves.core.listening.extra.MAX_DURATION"

        /**
         * Da igual cual se elija: BirdNET normaliza por min-max dentro del grafo
         * y es invariante a la escala. Se deja en RAW porque evita una
         * multiplicacion por cada una de las 144.000 muestras de cada ventana.
         */
        val INPUT_SCALING = PcmScaling.RAW

        /** Lo publica la Application antes de que nadie arranque el servicio. */
        @Volatile
        var dependencies: ListeningDependencies? = null

        private val _state = MutableStateFlow(ListeningServiceState())

        /**
         * Estado de la escucha en segundo plano, valido tambien cuando no hay
         * ninguna en curso (`isRunning = false`).
         */
        val state: StateFlow<ListeningServiceState> = _state.asStateFlow()

        /**
         * Arranca la escucha.
         *
         * @param maxDurationMillis limite tras el que se para sola. Ver
         *   [ListeningDeadline]: no es opcional por diseno, solo configurable.
         */
        fun start(
            context: Context,
            maxDurationMillis: Long = ListeningDeadline.DEFAULT_DURATION_MILLIS,
        ) {
            val intent = Intent(context, ListeningService::class.java)
                .putExtra(EXTRA_MAX_DURATION_MILLIS, maxDurationMillis)
            // startForegroundService y no startService: desde Android 8 lo
            // segundo lanza IllegalStateException si la app no esta en primer
            // plano, que es justo cuando este servicio tiene sentido.
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_STOP)
            context.startForegroundService(intent)
        }
    }
}
