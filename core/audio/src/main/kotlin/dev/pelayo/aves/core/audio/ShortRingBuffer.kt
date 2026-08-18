package dev.pelayo.aves.core.audio

/**
 * Buffer circular de muestras PCM de 16 bits.
 *
 * Existe porque la inferencia necesita ventanas SOLAPADAS: BirdNET analiza 3
 * segundos, pero si solo se clasificara cada 3 segundos, un canto a caballo
 * entre dos ventanas se partiria por la mitad y podria no reconocerse en
 * ninguna de las dos. Manteniendo siempre los ultimos N segundos y disparando
 * cada 800 ms, cualquier canto cae entero dentro de alguna ventana.
 *
 * No es thread-safe: se usa desde el unico hilo que lee del AudioRecord.
 */
class ShortRingBuffer(val capacity: Int) {

    init {
        require(capacity > 0) { "capacity debe ser positiva, era $capacity" }
    }

    private val data = ShortArray(capacity)
    private var writeIndex = 0
    private var written = 0L

    /** Muestras totales escritas desde el inicio. Util para calcular el salto entre ventanas. */
    val totalWritten: Long get() = written

    /** false hasta que se han acumulado [capacity] muestras: antes no hay ventana completa. */
    val isFull: Boolean get() = written >= capacity

    fun write(source: ShortArray, length: Int = source.size) {
        require(length >= 0 && length <= source.size) { "length fuera de rango: $length" }
        var offset = 0
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writeIndex)
            source.copyInto(
                destination = data,
                destinationOffset = writeIndex,
                startIndex = offset,
                endIndex = offset + chunk,
            )
            writeIndex = (writeIndex + chunk) % capacity
            offset += chunk
            remaining -= chunk
        }
        written += length
    }

    /**
     * Copia la ventana completa en [dest] en orden cronologico (la muestra mas
     * antigua primero), que es lo que espera el modelo.
     *
     * Si aun no se ha llenado el buffer, las posiciones sin datos quedan a cero.
     */
    fun snapshotInto(dest: ShortArray) {
        require(dest.size == capacity) { "dest debe medir $capacity, media ${dest.size}" }
        val tail = capacity - writeIndex
        data.copyInto(dest, destinationOffset = 0, startIndex = writeIndex, endIndex = capacity)
        data.copyInto(dest, destinationOffset = tail, startIndex = 0, endIndex = writeIndex)
    }

    fun reset() {
        data.fill(0)
        writeIndex = 0
        written = 0L
    }
}
