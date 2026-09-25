package dev.pelayo.aves.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

class BandEnergyGateTest {

    private val rate = 48_000
    private val gate = EnergyGate(PcmScaling.NORMALIZED, sampleRate = rate)

    @Test
    fun `el zumbido grave no abre la puerta por banda pero si la de toda la banda`() {
        // Viento o trafico: mucha energia a 150 Hz y nada donde cantan las aves.
        val hum = tone(150.0, dbfs = -40.0)

        assertFalse(gate.shouldClassify(hum))
        assertTrue(EnergyGate(PcmScaling.NORMALIZED).shouldClassify(hum))
    }

    @Test
    fun `un canto flojo en la banda si la abre`() {
        // -60 dBFS a 3 kHz: un ave lejana. Con toda la banda y -60 de corte
        // quedaba justo en el filo.
        assertTrue(gate.shouldClassify(tone(3_000.0, dbfs = -60.0)))
    }

    @Test
    fun `el silencio no la abre`() {
        assertFalse(gate.shouldClassify(FloatArray(rate * 3)))
    }

    @Test
    fun `en la banda de paso el nivel es el de la senal`() {
        // El RMS de un seno es su amplitud / raiz de 2: -40 dBFS de pico son
        // unos -43 dBFS de RMS. El filtro no debe comerse la banda del canto.
        val level = gate.level(tone(4_000.0, dbfs = -40.0))
        assertEquals(-43.0, 20 * log10(level.toDouble()), 1.0)
    }

    @Test
    fun `a 16 kHz no hay paso-bajo y no revienta`() {
        val low = EnergyGate(PcmScaling.NORMALIZED, sampleRate = 16_000)
        assertTrue(low.shouldClassify(tone(3_000.0, dbfs = -50.0, sampleRate = 16_000)))
    }

    private fun tone(hz: Double, dbfs: Double, sampleRate: Int = rate): FloatArray {
        val amplitude = 10.0.pow(dbfs / 20)
        return FloatArray(sampleRate * 3) { (amplitude * sin(2 * PI * hz * it / sampleRate)).toFloat() }
    }
}
