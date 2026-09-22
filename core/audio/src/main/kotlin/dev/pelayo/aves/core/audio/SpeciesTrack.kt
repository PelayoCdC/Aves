package dev.pelayo.aves.core.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Una ventana del fichero en la que el modelo oyo a una especie. */
data class ScoredWindow(
    /** Primera muestra de la ventana dentro del audio decodificado. */
    val startSample: Int,
    val score: Float,
)

/** Un tramo continuo de audio en el que canta la especie. */
data class TrackSegment(
    val startSample: Int,
    /** Exclusivo. */
    val endSample: Int,
    /** La mejor puntuacion de las ventanas que lo forman. */
    val score: Float,
) {
    val length: Int get() = endSample - startSample
}

/**
 * La pista de UNA especie dentro de una grabacion en la que cantan varias.
 *
 * Que es y que no es, porque el nombre promete mucho:
 *
 *  - **Aisla en el tiempo**: la pista solo contiene los tramos en los que el
 *    modelo oyo a esa especie. Un fichero de cinco minutos con un mirlo al
 *    principio y un petirrojo al final da dos pistas cortas, cada una con lo suyo.
 *  - **Limpia en frecuencia**: quita el ruido de fondo estacionario (viento,
 *    trafico, el soplido del micro) y atenua lo que queda fuera de la banda en la
 *    que esta la energia de esos tramos. Ver [SpectralCleaner].
 *  - **NO separa dos aves que cantan a la vez en la misma banda.** Eso pide un
 *    modelo de separacion de fuentes, que esta app no lleva. Si un mirlo y un
 *    zorzal se pisan, en la pista de cada uno se oye tambien al otro.
 */
object SpeciesTrackBuilder {

    /** Tope de duracion de la pista. Una ficha quiere un canto reconocible, no la grabacion entera. */
    const val DEFAULT_MAX_SECONDS = 20

    /** Silencio entre dos tramos que no eran contiguos: se tiene que oir que hay un corte. */
    private const val GAP_MILLIS = 180

    /** Fundido en los bordes de cada tramo. Sin el, cada corte es un clic. */
    private const val FADE_MILLIS = 25

    /**
     * Junta las ventanas (que se solapan) en tramos continuos.
     *
     * Dos ventanas se funden si se tocan o se solapan; un hueco entre ellas, por
     * pequeno que sea, es un tramo nuevo: ahi el modelo dejo de oir al ave.
     */
    fun mergeWindows(windows: List<ScoredWindow>, windowSize: Int, totalSamples: Int): List<TrackSegment> {
        if (windows.isEmpty() || windowSize <= 0) return emptyList()
        val sorted = windows.sortedBy { it.startSample }
        val merged = ArrayList<TrackSegment>()
        var start = sorted[0].startSample
        var end = min(start + windowSize, totalSamples)
        var score = sorted[0].score

        for (window in sorted.drop(1)) {
            if (window.startSample <= end) {
                end = max(end, min(window.startSample + windowSize, totalSamples))
                score = max(score, window.score)
            } else {
                merged += TrackSegment(start, end, score)
                start = window.startSample
                end = min(start + windowSize, totalSamples)
                score = window.score
            }
        }
        merged += TrackSegment(start, end, score)
        return merged.filter { it.length > 0 }
    }

    /**
     * Elige que tramos caben en [maxSamples], los mejores primero, y los devuelve
     * en el orden en que suenan. Un tramo que no cabe entero se recorta por el
     * final: mejor medio canto del mejor tramo que ninguno.
     */
    fun selectSegments(segments: List<TrackSegment>, maxSamples: Int): List<TrackSegment> {
        var budget = maxSamples
        val chosen = ArrayList<TrackSegment>()
        for (segment in segments.sortedByDescending { it.score }) {
            if (budget <= 0) break
            val length = min(segment.length, budget)
            chosen += segment.copy(endSample = segment.startSample + length)
            budget -= length
        }
        return chosen.sortedBy { it.startSample }
    }

    /**
     * Construye la pista: tramos elegidos, con fundidos y un silencio entre los
     * que no eran contiguos. No limpia; para eso esta [SpectralCleaner].
     *
     * @return la pista, o un array vacio si no habia ninguna ventana.
     */
    fun build(
        samples: FloatArray,
        sampleRate: Int,
        windows: List<ScoredWindow>,
        windowSize: Int,
        maxSeconds: Int = DEFAULT_MAX_SECONDS,
    ): FloatArray {
        val segments = selectSegments(
            mergeWindows(windows, windowSize, samples.size),
            maxSamples = maxSeconds * sampleRate,
        )
        if (segments.isEmpty()) return FloatArray(0)

        val gap = GAP_MILLIS * sampleRate / 1000
        val fade = FADE_MILLIS * sampleRate / 1000
        val total = segments.sumOf { it.length } + gap * (segments.size - 1)
        val track = FloatArray(total)

        var cursor = 0
        for (segment in segments) {
            samples.copyInto(track, cursor, segment.startSample, segment.endSample)
            applyFades(track, cursor, segment.length, fade)
            cursor += segment.length + gap
        }
        return track
    }

    private fun applyFades(track: FloatArray, offset: Int, length: Int, fade: Int) {
        val ramp = min(fade, length / 2)
        for (i in 0 until ramp) {
            // Medio coseno: sin esquinas, que es lo que se oye como clic.
            val gain = (0.5 - 0.5 * cos(PI * i / ramp)).toFloat()
            track[offset + i] *= gain
            track[offset + length - 1 - i] *= gain
        }
    }
}

/**
 * Limpieza espectral de una pista: resta de ruido y foco en la banda del canto.
 *
 * Es procesado clasico (STFT, mascara de ganancia, reconstruccion por solape), no
 * un modelo. Dos pasos sobre la misma mascara:
 *
 *  1. **Resta de ruido** tipo Wiener, con el perfil de ruido estimado de la
 *     grabacion entera ([noiseProfile]): lo que en cada frecuencia esta SIEMPRE
 *     ahi (viento, trafico lejano, soplido) es ruido; lo que sobresale, canto.
 *  2. **Foco en la banda**: se mira donde esta la energia que sobrevive a la
 *     resta y lo que queda fuera de esa banda se atenua. Es lo que quita al
 *     cuco grave de la pista del reyezuelo agudo cuando coinciden.
 *
 * La ganancia nunca baja de un suelo: llevarla a cero deja "ruido musical"
 * (pitidos sueltos que aparecen y desaparecen), que molesta mas que el ruido
 * que se queria quitar.
 */
class SpectralCleaner(
    private val fftSize: Int = 1024,
) {
    private val hop = fftSize / 2
    private val bins = fftSize / 2 + 1

    /** Raiz de Hann en analisis y en sintesis: su producto es Hann, que al 50 % de solape suma 1. */
    private val window = DoubleArray(fftSize) { sqrt(0.5 - 0.5 * cos(2 * PI * it / fftSize)) }

    init {
        require(fftSize >= 64 && fftSize and (fftSize - 1) == 0) { "fftSize debe ser potencia de dos" }
    }

    /**
     * Cuanto hay de ruido en cada frecuencia: el percentil bajo de la magnitud a
     * lo largo de la grabacion.
     *
     * El percentil y no la media porque la media incluye los cantos, que son
     * justo lo que no es ruido. Se muestrean como mucho [MAX_PROFILE_FRAMES]
     * tramas repartidas por todo el fichero: el ruido de fondo cambia despacio
     * y ordenar 30.000 tramas por cada frecuencia no aporta nada.
     */
    fun noiseProfile(samples: FloatArray): DoubleArray {
        val frames = if (samples.size < fftSize) 0 else (samples.size - fftSize) / hop + 1
        if (frames == 0) return DoubleArray(bins)

        val step = max(1, frames / MAX_PROFILE_FRAMES)
        val picked = (0 until frames step step).toList()
        val magnitudes = Array(bins) { DoubleArray(picked.size) }
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)

        picked.forEachIndexed { column, frame ->
            analyze(samples, frame * hop, re, im)
            for (bin in 0 until bins) magnitudes[bin][column] = magnitude(re[bin], im[bin])
        }

        val percentile = DoubleArray(bins) { bin ->
            val sorted = magnitudes[bin].also { it.sort() }
            sorted[((sorted.size - 1) * NOISE_PERCENTILE).toInt()]
        }
        return capToSmoothFloor(percentile)
    }

    /**
     * Protege al ave que no calla.
     *
     * Si en una grabacion corta el ave canta sin parar, "lo que siempre esta
     * ahi" en su banda es ELLA, y el percentil la tomaria por ruido y la
     * restaria. La diferencia esta en la forma: el ruido de fondo es suave a lo
     * ancho del espectro y un canto es un pico estrecho. Asi que ninguna
     * frecuencia puede tener mas ruido que un margen sobre la mediana de sus
     * vecinas: el pico se recorta y el fondo se queda como estaba.
     */
    internal fun capToSmoothFloor(profile: DoubleArray): DoubleArray {
        val neighbours = DoubleArray(2 * SMOOTH_RADIUS_BINS + 1)
        return DoubleArray(profile.size) { bin ->
            val from = max(0, bin - SMOOTH_RADIUS_BINS)
            val to = min(profile.size - 1, bin + SMOOTH_RADIUS_BINS)
            val count = to - from + 1
            profile.copyInto(neighbours, 0, from, to + 1)
            neighbours.sort(0, count)
            min(profile[bin], neighbours[count / 2] * SMOOTH_HEADROOM)
        }
    }

    /**
     * Limpia [track] con el perfil de ruido de la grabacion de la que salio.
     *
     * Devuelve un array del mismo tamano. Si la pista es mas corta que una trama
     * se devuelve tal cual: no hay nada que analizar.
     */
    fun clean(track: FloatArray, noise: DoubleArray): FloatArray {
        require(noise.size == bins) { "El perfil de ruido no es de este tamano de FFT" }
        if (track.size < fftSize) return track.copyOf()

        // Se rellena por los dos lados para que los extremos tambien tengan sus
        // dos tramas solapadas; sin relleno, el principio y el final salen
        // atenuados por la ventana.
        val padded = FloatArray(track.size + 2 * fftSize)
        track.copyInto(padded, fftSize)
        val frames = (padded.size - fftSize) / hop + 1

        // Los espectros NO se guardan: 20 s a 48 kHz serian ~30 MB de dobles. Se
        // guarda solo la mascara (en float) y la FFT se repite al reconstruir,
        // que cuesta mucho menos que esa memoria en un movil modesto.
        val re = DoubleArray(fftSize)
        val im = DoubleArray(fftSize)
        val gains = Array(frames) { FloatArray(bins) }
        val survivingEnergy = DoubleArray(bins)

        // Pasada 1: ganancia de la resta de ruido, y donde queda la energia.
        for (frame in 0 until frames) {
            analyze(padded, frame * hop, re, im)
            for (bin in 0 until bins) {
                val power = re[bin] * re[bin] + im[bin] * im[bin]
                val noisePower = noise[bin] * noise[bin] * OVER_SUBTRACTION
                val gain = if (power <= 0.0) GAIN_FLOOR else max(GAIN_FLOOR, (power - noisePower) / power)
                gains[frame][bin] = gain.toFloat()
                survivingEnergy[bin] += power * gain * gain
            }
        }

        // Pasada 2: foco en la banda y suavizado en el tiempo. El suavizado evita
        // que la ganancia de una frecuencia salte de trama a trama, que es de
        // donde sale el ruido musical.
        val bandGain = bandFocus(survivingEnergy)
        for (frame in 0 until frames) {
            for (bin in 0 until bins) {
                var gain = gains[frame][bin] * bandGain[bin]
                if (frame > 0) gain = max(gain, gains[frame - 1][bin] * RELEASE)
                gains[frame][bin] = gain.toFloat()
            }
        }

        // Pasada 3: aplicar la mascara y reconstruir por solape.
        val output = DoubleArray(padded.size)
        for (frame in 0 until frames) {
            val offset = frame * hop
            analyze(padded, offset, re, im)
            for (bin in 0 until bins) {
                val gain = gains[frame][bin]
                re[bin] *= gain
                im[bin] *= gain
                // La mitad de arriba es la conjugada de la de abajo: la misma ganancia.
                if (bin in 1 until fftSize / 2) {
                    re[fftSize - bin] *= gain
                    im[fftSize - bin] *= gain
                }
            }
            inverse(re, im)
            for (i in 0 until fftSize) output[offset + i] += re[i] * window[i]
        }

        return FloatArray(track.size) { output[it + fftSize].toFloat() }
    }

    /**
     * La ganancia por frecuencia que deja pasar la banda del canto y atenua el
     * resto, con bordes suaves.
     *
     * La banda es la que contiene el grueso de la energia que sobrevivio a la
     * resta de ruido (entre sus percentiles [BAND_LOW] y [BAND_HIGH]), con un
     * margen: los armonicos y los ataques de las notas estan en los bordes.
     */
    internal fun bandFocus(energy: DoubleArray): DoubleArray {
        val total = energy.sum()
        if (total <= 0.0) return DoubleArray(bins) { 1.0 }

        var cumulative = 0.0
        var low = 0
        var high = bins - 1
        var lowFound = false
        for (bin in 0 until bins) {
            cumulative += energy[bin]
            if (!lowFound && cumulative >= total * BAND_LOW) {
                low = bin
                lowFound = true
            }
            if (cumulative >= total * BAND_HIGH) {
                high = bin
                break
            }
        }

        val margin = max(BAND_MARGIN_BINS, (high - low) / 4)
        val passLow = low - margin
        val passHigh = high + margin
        val slope = max(1, margin)

        return DoubleArray(bins) { bin ->
            val distance = when {
                bin < passLow -> passLow - bin
                bin > passHigh -> bin - passHigh
                else -> 0
            }
            // De 1 dentro de la banda a OUT_OF_BAND_GAIN a `slope` bins de ella.
            val t = min(1.0, distance.toDouble() / slope)
            1.0 - t * (1.0 - OUT_OF_BAND_GAIN)
        }
    }

    private fun analyze(samples: FloatArray, start: Int, re: DoubleArray, im: DoubleArray) {
        for (i in 0 until fftSize) {
            re[i] = samples[start + i] * window[i]
            im[i] = 0.0
        }
        SpectrogramComputer.fft(re, im)
    }

    /** La inversa con la directa: conjugar, transformar, conjugar y dividir entre N. */
    private fun inverse(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until fftSize) im[i] = -im[i]
        SpectrogramComputer.fft(re, im)
        for (i in 0 until fftSize) {
            re[i] /= fftSize
            im[i] = -im[i] / fftSize
        }
    }

    private fun magnitude(re: Double, im: Double): Double = sqrt(re * re + im * im)

    private companion object {
        const val MAX_PROFILE_FRAMES = 400

        /** El 20 % mas silencioso de cada frecuencia es el fondo. */
        const val NOISE_PERCENTILE = 0.2

        /**
         * Se resta bastante mas que el percentil, EN POTENCIA. Con ruido gaussiano la
         * magnitud sigue una Rayleigh: su percentil 20 al cuadrado es 0,45 s2 y la
         * potencia media 2 s2, asi que x4,5 seria restar justo la media y x9 es el
         * doble de la media, que es lo que deja al ruido por debajo del suelo casi
         * siempre.
         */
        const val OVER_SUBTRACTION = 9.0

        /** -14 dB. Ni una frecuencia se apaga del todo: si la estimacion de ruido se
         *  equivoca, el dano es que algo suena mas bajo, no que desaparece. */
        const val GAIN_FLOOR = 0.2

        /** Cuanto puede caer la ganancia de una trama a la siguiente. */
        const val RELEASE = 0.7

        /** +-1,5 kHz a 48 kHz: mas ancho que el canto de casi cualquier ave. */
        const val SMOOTH_RADIUS_BINS = 32

        /** Cuanto puede sobresalir una frecuencia de sus vecinas y seguir siendo ruido. */
        const val SMOOTH_HEADROOM = 1.5

        const val BAND_LOW = 0.03
        const val BAND_HIGH = 0.97
        const val BAND_MARGIN_BINS = 6

        /** -14 dB fuera de la banda del canto. */
        const val OUT_OF_BAND_GAIN = 0.2
    }
}

/**
 * Sube el volumen de una pista hasta que su pico roce el fondo de escala.
 *
 * Un canto grabado con el movil a veinte metros ocupa un 2 % de la escala: se
 * guarda bien pero se reproduce inaudible. La ganancia tiene tope para que una
 * pista que solo es ruido no se convierta en un rugido.
 *
 * @param fullScale el valor que representa el maximo (1 con muestras normalizadas).
 */
fun normalizePeak(track: FloatArray, fullScale: Float = 1f, maxGain: Float = 30f): FloatArray {
    var peak = 0f
    for (sample in track) peak = max(peak, kotlin.math.abs(sample))
    if (peak <= 0f) return track
    val gain = min(maxGain, fullScale * 0.9f / peak)
    if (gain <= 1f) return track
    return FloatArray(track.size) { track[it] * gain }
}
