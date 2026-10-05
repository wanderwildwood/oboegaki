package com.wanderwildwood.oboegaki.hearing

import kotlin.math.sqrt

/**
 * Deciding whether a recording has anything in it, before asking Whisper.
 *
 * Whisper given silence does not return nothing. It returns something plausible, "Thank you."
 * or "you", because that is what the end of its training audio sounded like. A recording that
 * never rose above the room is therefore marked as nothing said, rather than transcribed into
 * words nobody spoke.
 */
object Listening {
    /** Loudness, as an RMS of 16-bit samples, below which a second counts as quiet. */
    const val QUIET_RMS = 400.0

    /** The loudness of each whole second of [samples], on the 16-bit scale. */
    fun secondsRms(samples: FloatArray, rate: Int = 16_000): List<Double> =
        (0 until samples.size / rate).map { s ->
            var sum = 0.0
            for (i in s * rate until (s + 1) * rate) {
                val v = samples[i] * 32768.0
                sum += v * v
            }
            sqrt(sum / rate)
        }

    fun anythingSaid(samples: FloatArray): Boolean = secondsRms(samples).any { it >= QUIET_RMS }

    /** Whisper's text, tidied: one line of words with single spaces, or empty. */
    fun tidy(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
