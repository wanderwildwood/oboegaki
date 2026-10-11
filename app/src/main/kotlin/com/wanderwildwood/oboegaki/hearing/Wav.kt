package com.wanderwildwood.oboegaki.hearing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sound this app writes and reads: mono, 16-bit, at 16 kHz — what Whisper listens at — or,
 * for a recording made at full quality, at [FULL_RATE]. Whisper is only ever given 16 kHz; a full
 * recording is brought down to it by [samples] first.
 */
object Wav {
    const val RATE = 16_000
    const val FULL_RATE = 48_000
    const val HEADER = 44

    /** The 44-byte header for [dataBytes] of samples at [rate]. */
    fun header(dataBytes: Long, rate: Int = RATE): ByteArray =
        ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((dataBytes + 36).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()

    /** The rate written in the header of a file this app wrote (bytes 24–27). */
    fun rateOf(file: java.io.File): Int = java.io.RandomAccessFile(file, "r").use { f ->
        if (f.length() < HEADER) return RATE
        f.seek(24)
        val b = ByteArray(4).also { f.readFully(it) }
        ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /** Whole seconds of sound in a file of [fileBytes] at [rate], as this app writes them. */
    fun seconds(fileBytes: Long, rate: Int = RATE): Int = ((fileBytes - HEADER).coerceAtLeast(0) / (rate * 2)).toInt()

    /**
     * The samples of a file this app wrote, as floats in -1..1. It finds the data chunk rather
     * than assuming 44 bytes, and refuses anything not in the one format.
     */
    fun samples(bytes: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(12)
        var format = Triple(0, 0, 0)
        while (buf.remaining() >= 8) {
            val id = String(ByteArray(4).also { buf.get(it) })
            val size = buf.int
            when (id) {
                "fmt " -> {
                    val start = buf.position()
                    buf.short
                    val channels = buf.short.toInt()
                    val rate = buf.int
                    buf.int; buf.short
                    format = Triple(channels, rate, buf.short.toInt())
                    buf.position(start + size)
                }
                "data" -> {
                    require(format.first == 1 && format.third == 16 && format.second in setOf(RATE, FULL_RATE)) {
                        "not 16 or 48 kHz mono 16-bit: $format"
                    }
                    val n = minOf(size, buf.remaining()) / 2
                    val read = FloatArray(n) { buf.short / 32768f }
                    return if (format.second == FULL_RATE) down(read) else read
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }
        throw IllegalArgumentException("no data chunk")
    }

    /**
     * 48 kHz to 16 kHz for Whisper: every third sample, after a low-pass that takes out what
     * 16 kHz cannot hold (above 8 kHz), so it does not fold back down as hiss. A windowed-sinc
     * filter of [TAPS] taps cut at 7 kHz; speech sits well below that.
     */
    fun down(full: FloatArray): FloatArray {
        val out = FloatArray(full.size / 3)
        val half = TAPS / 2
        for (i in out.indices) {
            val centre = i * 3
            var sum = 0f
            for (k in 0 until TAPS) {
                val j = centre + k - half
                if (j in full.indices) sum += full[j] * LOW_PASS[k]
            }
            out[i] = sum
        }
        return out
    }

    private const val TAPS = 63
    private val LOW_PASS: FloatArray = run {
        val cut = 7_000.0 / FULL_RATE
        val taps = DoubleArray(TAPS) { k ->
            val n = k - TAPS / 2
            val sinc = if (n == 0) 2 * cut else kotlin.math.sin(2 * Math.PI * cut * n) / (Math.PI * n)
            val window = 0.54 - 0.46 * kotlin.math.cos(2 * Math.PI * k / (TAPS - 1))
            sinc * window
        }
        val total = taps.sum()
        FloatArray(TAPS) { (taps[it] / total).toFloat() }
    }
}
