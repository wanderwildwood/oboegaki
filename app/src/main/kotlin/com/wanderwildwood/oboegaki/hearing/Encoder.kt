package com.wanderwildwood.oboegaki.hearing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.RandomAccessFile

/**
 * A recording made small enough to sync: the WAV the microphone wrote, as AAC in an .m4a.
 *
 * A minute of WAV at Whisper's rate is nearly two megabytes; as speech-quality AAC it is about
 * a quarter of one. The WAV is kept only on the phone, and only until it has been heard.
 */
object Encoder {
    private const val BITRATE = 32_000

    /** Writes [wav] into [m4a]. Throws if the phone has no AAC encoder or the file is not ours. */
    fun toM4a(wav: File, m4a: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Wav.RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Wav.RATE * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        m4a.parentFile?.mkdirs()
        val muxer = MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val input = RandomAccessFile(wav, "r")
        try {
            input.seek(Wav.HEADER.toLong())
            codec.start()
            val info = MediaCodec.BufferInfo()
            var track = -1
            var fed = 0L
            var inputDone = false
            var outputDone = false
            val chunk = ByteArray(4096)
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val n = input.read(chunk, 0, minOf(chunk.size, buffer.remaining()))
                        val time = fed * 1_000_000L / (Wav.RATE * 2)
                        if (n <= 0) {
                            codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.put(chunk, 0, n)
                            codec.queueInputBuffer(index, 0, n, time, 0)
                            fed += n
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && track >= 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            input.close()
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
