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
}
