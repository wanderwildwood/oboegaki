package com.wanderwildwood.oboegaki.hearing

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    private fun file(samples: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        return Wav.header(samples.size * 2L) + data.array()
    }

    @Test
    fun samplesComeBackAsWritten() {
        val read = Wav.samples(file(shortArrayOf(0, 16384, -32768, 32767)))
        assertEquals(listOf(0f, 0.5f, -1f, 32767 / 32768f), read.toList())
    }

    @Test
    fun secondsCountWholeSecondsOfSound() {
        assertEquals(0, Wav.seconds(44))
        assertEquals(1, Wav.seconds(44L + 32_000))
        assertEquals(1, Wav.seconds(44L + 63_998))
        assertEquals(90, Wav.seconds(44L + 90 * 32_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anyOtherFormatIsRefused() {
        val bytes = file(shortArrayOf(1, 2))
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 44_100)
        Wav.samples(bytes)
    }

    private fun rms(x: FloatArray, from: Int = 100) =
        kotlin.math.sqrt(x.drop(from).dropLast(from).map { it * it }.average()).toFloat()

    private fun tone48(hz: Int) = FloatArray(48_000) { 0.5f * kotlin.math.sin(2 * Math.PI * hz * it / 48_000).toFloat() }

    @Test
    fun fullRateComesDownToSixteenKilohertz() {
        assertEquals(16_000, Wav.down(tone48(1_000)).size)
    }

    @Test
    fun speechFrequenciesPassTheFilter() {
        // A sine of amplitude 0.5 has an RMS of about 0.354, before and after.
        assertEquals(0.354f, rms(Wav.down(tone48(1_000))), 0.02f)
    }

    @Test
    fun whatSixteenKilohertzCannotHoldIsTakenOut() {
        // 12 kHz would fold back down to 4 kHz without the filter; it should be all but gone.
        assert(rms(Wav.down(tone48(12_000))) < 0.01f)
    }

    @Test
    fun aFullRateFileIsReadAtSixteen() {
        val samples = ShortArray(48_000) { (8000 * kotlin.math.sin(2 * Math.PI * 440 * it / 48_000)).toInt().toShort() }
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { data.putShort(it) }
        val bytes = Wav.header(samples.size * 2L, Wav.FULL_RATE) + data.array()
        assertEquals(16_000, Wav.samples(bytes).size)
    }
}
