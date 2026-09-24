package dev.pelayo.aves.core.map

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/**
 * Lo que osmdroid necesita saber una vez por proceso.
 *
 * El User-Agent es obligatorio para los servidores de teselas (y tile.gbif.org
 * lo pide igual que OpenStreetMap); identifica a la app, nunca al usuario. La
 * cache de teselas va a la cache de la app, con tope: el sistema la puede
 * vaciar y el mapa se vuelve a pedir, que es lo que debe pasar con una cache.
 */
object MapSetup {
    fun configure(context: Context, userAgent: String) {
        val base = File(context.cacheDir, "osmdroid")
        Configuration.getInstance().apply {
            userAgentValue = userAgent
            osmdroidBasePath = base
            osmdroidTileCache = File(base, "tiles")
            tileFileSystemCacheMaxBytes = TILE_CACHE_MAX_BYTES
            tileFileSystemCacheTrimBytes = TILE_CACHE_TRIM_BYTES
        }
    }

    private const val TILE_CACHE_MAX_BYTES = 120L * 1024 * 1024
    private const val TILE_CACHE_TRIM_BYTES = 90L * 1024 * 1024
}

/** Una fuente de teselas de GBIF para osmdroid. La URL la construye [GbifTiles]. */
private class GbifTileSource(
    name: String,
    private val url: (zoom: Int, x: Int, y: Int) -> String,
) : OnlineTileSourceBase(
    name,
    GbifTiles.MIN_ZOOM,
    GbifTiles.MAX_ZOOM,
    GbifTiles.TILE_SIZE_PX,
    ".png",
    arrayOf("https://api.gbif.org/"),
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        url(MapTileIndex.getZoom(pMapTileIndex), MapTileIndex.getX(pMapTileIndex), MapTileIndex.getY(pMapTileIndex))
}

private val BASEMAP = GbifTileSource("gbif-natural", GbifTiles::basemapUrl)

/**
 * El mapa: mapa base de GBIF, la densidad de registros de un taxon si se pide, y
 * los avistamientos del usuario como puntos.
 *
 * @param viewport donde mirar. Si cambia, el mapa se desplaza hasta alli.
 * @param densityTaxonKey el taxon cuya densidad de registros se pinta encima, o
 *   null para no pintar ninguna.
 * @param interactive false para una vista previa que no se puede mover: entonces
 *   todo el mapa es un boton que llama a [onMapTap].
 * @param onMapTap con el mapa interactivo, un toque en el mapa (fuera de un
 *   punto). Sirve para quitar la seleccion.
 */
@Composable
fun BirdMap(
    viewport: MapViewport,
    points: List<MapPoint>,
    densityTaxonKey: Int?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    onPointClick: (MapPoint) -> Unit = {},
    onMapTap: (() -> Unit)? = null,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val overlays = remember { OverlayHolder() }
    var mapView by remember { mutableStateOf<MapView?>(null) }
    val currentPointClick by rememberUpdatedState(onPointClick)
    val currentMapTap by rememberUpdatedState(onMapTap)

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                MapView(context).apply {
                    // osmdroid, por defecto, se AUTODESTRUYE al separarse de la ventana
                    // (onDetachedFromWindow -> onDetach): vacia su repositorio y deja
                    // de pedir teselas. Dentro de Compose la vista se separa y se
                    // vuelve a enganchar en momentos que no controlamos (una lista
                    // perezosa, una transicion), y el resultado era un mapa gris
                    // para siempre o un NullPointerException al crear un marcador.
                    // La destruccion la decide el DisposableEffect de abajo, y solo el.
                    setDestroyMode(false)
                    setTileSource(BASEMAP)
                    // Teselas a doble resolucion: no hace falta que osmdroid las
                    // reescale a la densidad de la pantalla, y quedan nitidas.
                    isTilesScaledToDpi = false
                    setMultiTouchControls(interactive)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    minZoomLevel = GbifTiles.MIN_ZOOM.toDouble()
                    maxZoomLevel = GbifTiles.MAX_ZOOM.toDouble()
                    controller.setZoom(viewport.zoom)
                    controller.setCenter(GeoPoint(viewport.latitude, viewport.longitude))
                    if (!interactive) {
                        // Ni un dedo lo mueve, y un toque abre el grande. El toque se
                        // recoge AQUI, en la vista, y no en una capa de Compose encima:
                        // la capa no llegaba a recibirlo con el mapa debajo.
                        setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                view.performClick()
                                currentMapTap?.invoke()
                            }
                            true
                        }
                    }
                    mapView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { map ->
                overlays.sync(
                    map = map,
                    points = points,
                    densityTaxonKey = densityTaxonKey,
                    onPointClick = { currentPointClick(it) },
                    onMapTap = if (interactive && currentMapTap != null) ({ currentMapTap?.invoke() }) else null,
                )
            },
        )
    }

    LaunchedEffect(viewport, mapView) {
        mapView?.controller?.animateTo(GeoPoint(viewport.latitude, viewport.longitude), viewport.zoom, ANIMATION_MILLIS)
    }

    // osmdroid quiere saber cuando la pantalla se va: para las descargas y suelta
    // los bitmaps. Sin esto, cada mapa abandonado sigue pidiendo teselas.
    DisposableEffect(lifecycleOwner, mapView) {
        val map = mapView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> map?.onResume()
                Lifecycle.Event.ON_PAUSE -> map?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (map != null) {
                overlays.clear(map)
                map.onDetach()
            }
        }
    }
}

private const val ANIMATION_MILLIS = 500L

/**
 * Lo que hay pintado encima del mapa base, para poder cambiarlo sin rehacer el
 * mapa: la capa de densidad de un taxon, los puntos y el receptor de toques.
 */
private class OverlayHolder {
    /** Tras [clear] no se toca mas el mapa: un marcador sobre un mapa destruido revienta. */
    private var detached = false
    private var densityKey: Int? = null
    private var densityProvider: MapTileProviderBasic? = null
    private var density: TilesOverlay? = null
    private var markers: List<Marker> = emptyList()
    private var markerIds: List<Long> = emptyList()
    private var events: MapEventsOverlay? = null

    fun sync(
        map: MapView,
        points: List<MapPoint>,
        densityTaxonKey: Int?,
        onPointClick: (MapPoint) -> Unit,
        onMapTap: (() -> Unit)?,
    ) {
        if (detached) return
        var changed = false

        if (densityTaxonKey != densityKey) {
            density?.let { map.overlays.remove(it) }
            densityProvider?.detach()
            density = null
            densityProvider = null
            if (densityTaxonKey != null) {
                val source = GbifTileSource("gbif-density-$densityTaxonKey") { zoom, x, y ->
                    GbifTiles.densityUrl(densityTaxonKey, zoom, x, y)
                }
                val provider = MapTileProviderBasic(map.context, source)
                val overlay = TilesOverlay(provider, map.context).apply {
                    // Sin fondo mientras carga: si no, tapa el mapa base con gris.
                    loadingBackgroundColor = AndroidColor.TRANSPARENT
                    loadingLineColor = AndroidColor.TRANSPARENT
                }
                // Debajo de los puntos, encima del mapa base.
                map.overlays.add(0, overlay)
                density = overlay
                densityProvider = provider
            }
            densityKey = densityTaxonKey
            changed = true
        }

        val ids = points.map { it.id }
        if (ids != markerIds) {
            markers.forEach { map.overlays.remove(it) }
            markers = points.map { point ->
                Marker(map).apply {
                    position = GeoPoint(point.latitude, point.longitude)
                    title = point.title
                    icon = pinDrawable(map.context, point.kind)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // Sin la burbuja de osmdroid: lo que se ensena al tocar lo
                    // decide la pantalla, en Compose.
                    setInfoWindow(null)
                    setOnMarkerClickListener { _, _ ->
                        onPointClick(point)
                        true
                    }
                }
            }
            map.overlays.addAll(markers)
            markerIds = ids
            changed = true
        }

        if ((onMapTap != null) != (events != null)) {
            events?.let { map.overlays.remove(it) }
            events = onMapTap?.let { tap ->
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                            tap()
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint?): Boolean = false
                    },
                ).also { map.overlays.add(0, it) }
            }
            changed = true
        }

        if (changed) map.invalidate()
    }

    fun clear(map: MapView) {
        detached = true
        density?.let { map.overlays.remove(it) }
        densityProvider?.detach()
        markers.forEach { map.overlays.remove(it) }
        events?.let { map.overlays.remove(it) }
        density = null
        densityProvider = null
        markers = emptyList()
        markerIds = emptyList()
        events = null
        densityKey = null
    }
}

/**
 * El punto de un avistamiento: un circulo del color de su origen con borde
 * blanco, que se ve igual sobre bosque que sobre mar.
 */
private fun pinDrawable(context: Context, kind: MapPointKind): Drawable {
    val density = context.resources.displayMetrics.density
    val size = (PIN_SIZE_DP * density).toInt()
    return GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(pinColor(kind))
        setStroke((PIN_STROKE_DP * density).toInt(), AndroidColor.WHITE)
        setSize(size, size)
    }
}

/** Verde para lo visto, naranja para lo fotografiado, morado para lo oido. */
fun pinColor(kind: MapPointKind): Int = when (kind) {
    MapPointKind.SEEN -> 0xFF1E8449.toInt()
    MapPointKind.PHOTO -> 0xFFD35400.toInt()
    MapPointKind.HEARD -> 0xFF6C3483.toInt()
}

private const val PIN_SIZE_DP = 18f
private const val PIN_STROKE_DP = 2.5f
