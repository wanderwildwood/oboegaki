package com.wanderwildwood.oboegaki.hearing

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

/**
 * The microphone, a second at a time, into a WAV file at Whisper's own rate, so what is heard
 * can be turned into words without resampling.
 *
 * Dream Log's recorder, without its stopping on silence: someone thinking aloud pauses, and
 * a voice note ends when they say so. It does stop at [LONGEST], because a recording left
 * running in a pocket is not a note.
 */
class Recorder(private val onSecond: (Int) -> Unit, private val onFinished: (File, Boolean) -> Unit) {

    companion object {
        const val LONGEST = 60 * 60
    }

    @Volatile private var running = false
    private var thread: Thread? = null

    /** False if the microphone could not be opened. */
    @SuppressLint("MissingPermission")
    fun start(into: File): Boolean {
        val rate = Wav.RATE
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, rate),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        running = true
        thread = Thread({ capture(record, into) }, "recorder").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread?.join(3000)
        thread = null
    }

    private fun capture(record: AudioRecord, into: File) {
        val rate = Wav.RATE
        val second = ShortArray(rate)
        val bytes = ByteArray(rate * 2)
        var seconds = 0
        var ok = true
        into.parentFile?.mkdirs()
        val out = RandomAccessFile(into, "rw")
        try {
            out.setLength(0)
            out.write(ByteArray(Wav.HEADER))
            record.startRecording()
            while (running) {
                var filled = 0
                while (filled < rate && running) {
                    val n = record.read(second, filled, rate - filled)
                    if (n < 0) { ok = false; running = false; break }
                    filled += n
                }
                for (i in 0 until filled) {
                    val s = second[i].toInt()
                    bytes[i * 2] = (s and 0xff).toByte()
                    bytes[i * 2 + 1] = (s shr 8 and 0xff).toByte()
                }
                out.write(bytes, 0, filled * 2)
                seconds++
                onSecond(seconds)
                if (seconds >= LONGEST) running = false
            }
        } finally {
            // Released in a finally, or a failed read leaves the microphone held and the
            // next press finds it busy.
            runCatching { record.stop() }
            record.release()
            out.seek(0)
            out.write(Wav.header(out.length() - Wav.HEADER))
            out.close()
            running = false
            onFinished(into, ok)
        }
    }
}
