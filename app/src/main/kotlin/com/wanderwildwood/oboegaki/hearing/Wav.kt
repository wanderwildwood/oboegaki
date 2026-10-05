package com.wanderwildwood.oboegaki.hearing

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The one sound format this app writes and reads: 16 kHz, mono, 16-bit, which is what Whisper
 * listens at, so nothing is ever resampled.
 */
object Wav {
    const val RATE = 16_000
    const val HEADER = 44

    /** The 44-byte header for [dataBytes] of samples. */
    fun header(dataBytes: Long): ByteArray =
        ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt((dataBytes + 36).toInt()); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes.toInt())
        }.array()

    /** Whole seconds of sound in a file of [fileBytes], as this app writes them. */
    fun seconds(fileBytes: Long): Int = ((fileBytes - HEADER).coerceAtLeast(0) / (RATE * 2)).toInt()

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
                    require(format == Triple(1, RATE, 16)) { "not 16 kHz mono 16-bit: $format" }
                    val n = minOf(size, buf.remaining()) / 2
                    return FloatArray(n) { buf.short / 32768f }
                }
                else -> buf.position(buf.position() + size + (size and 1))
            }
        }
        throw IllegalArgumentException("no data chunk")
    }
}
