package com.wanderwildwood.oboegaki.hearing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ListeningTest {
    private fun tone(seconds: Int, amplitude: Float) =
        FloatArray(seconds * 16_000) { amplitude * sin(2 * PI * 220 * it / 16_000).toFloat() }

    @Test
    fun roomToneIsNothingSaid() {
        assertFalse(Listening.anythingSaid(tone(30, 0.005f)))
        assertFalse(Listening.anythingSaid(FloatArray(16_000 * 5)))
    }

    @Test
    fun oneSecondOfVoiceIsEnough() {
        val samples = tone(30, 0.005f)
        tone(1, 0.2f).copyInto(samples, destinationOffset = 16_000 * 12)
        assertTrue(Listening.anythingSaid(samples))
    }

    @Test
    fun tidyMakesOneLine() {
        assertEquals("I was on a boat. It was mine.", Listening.tidy("  I was on a boat.\n It was   mine. "))
        assertEquals("", Listening.tidy(" \n "))
    }
}
