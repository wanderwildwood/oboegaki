package com.wanderwildwood.oboegaki.hearing

import android.content.Context
import android.os.PowerManager
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.inFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Voice notes: a recording becomes a note that holds it.
 *
 * Stopping writes the sound beside a new note, "Voice 2026-10-05 0214.m4a" next to
 * "Voice 2026-10-05 0214.md", and the note links it the way Obsidian does, `![[…]]`, so the
 * same folder opened on a computer shows the same thing. Then the recording is heard on the
 * phone, with Dream Log's Whisper, and the words go into the note under the link. English
 * unless another language was chosen in the settings; see [Speech].
 *
 * Hearing takes about as long as the recording did, on the Kompakt. It happens in the
 * background, one at a time, and whatever was waiting when the app last closed is picked up
 * when it opens. Until then the note holds the recording alone.
 */
object Voice {

    /** Seconds recorded so far, or null when not recording. */
    private val _recording = MutableStateFlow<Int?>(null)
    val recording: StateFlow<Int?> = _recording

    /** The note just made from a recording, for the screen to open; cleared once opened. */
    private val _made = MutableStateFlow<String?>(null)
    val made: StateFlow<String?> = _made

    /** Notes whose recording is waiting to be heard or being heard. */
    private val _hearing = MutableStateFlow<Set<String>>(emptySet())
    val hearing: StateFlow<Set<String>> = _hearing

    /** True while the recording under way is paused. */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused

    /** Set when the microphone could not be opened, or the recording could not be kept. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed

    private var recorder: Recorder? = null
    private lateinit var app: Context
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "voice") }

    private val dir get() = File(app.filesDir, "voice")

    /**
     * Recording quality, from the settings: speech (16 kHz, as Whisper hears, small) or full
     * (48 kHz through the least-processed microphone, for music, rooms and voices as they are).
     */
    fun full(context: Context): Boolean = prefs(context).getBoolean(FULL, false)

    fun setFull(context: Context, on: Boolean) = prefs(context).edit().putBoolean(FULL, on).apply()

    private fun prefs(context: Context) = context.getSharedPreferences("hearing", Context.MODE_PRIVATE)

    /** Whether the recording under way is to be written down; asked on the recording screen. */
    @Volatile var writeDown = true

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        Speech.init(app)
        refreshHearing()
    }

    /** Start recording. False if the microphone would not open. */
    fun begin(context: Context): Boolean {
        init(context)
        if (recorder != null) return true
        _failed.value = false
        // To the second, and never one already there: the sound of a recording still waiting
        // to be written down sits under this name, and a second recording begun in the same
        // minute used to write over it. The note's title keeps the minute.
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.ROOT).format(Date())
        var wav = File(dir, "$stamp.wav")
        var n = 2
        while (wav.exists()) wav = File(dir, "$stamp-${n++}.wav")
        writeDown = true
        _paused.value = false
        val r = Recorder(
            full = full(context),
            onSecond = { _recording.value = it },
            onFinished = { file, ok -> worker.execute { keep(file, ok) } },
        )
        if (!r.start(wav, context.getSystemService(android.media.AudioManager::class.java))) {
            _failed.value = true
            return false
        }
        recorder = r
        _recording.value = 0
        return true
    }

    fun end() {
        val r = recorder ?: return
        recorder = null
        r.paused = false
        r.stop()
        _recording.value = null
        _paused.value = false
    }

    fun pause(on: Boolean) {
        val r = recorder ?: return
        r.paused = on
        _paused.value = on
    }

    fun opened() {
        _made.value = null
    }

    /** The recording is finished: keep it as a note, and queue it to be heard. */
    private fun keep(wav: File, ok: Boolean) {
        _recording.value = null
        recorder = null
        if (!ok && (!wav.exists() || Wav.seconds(wav.length(), Wav.rateOf(wav)) < 1)) {
            wav.delete()
            _failed.value = true
            return
        }
        val stamp = wav.nameWithoutExtension
        val folder = Notes.newFolder
        val notePath = Notes.newPath(folder, "Voice ${stamp.take(TITLE_STAMP)}")
        val stem = notePath.substringAfterLast('/').substringBeforeLast('.')
        // The link names it as it sits beside the note, which is how Obsidian finds it too.
        val audioPath = "$stem.m4a"
        val m4a = File(dir, "$stem.m4a.part")
        try {
            Encoder.toM4a(wav, m4a)
            val shelf = Notes.shelf() ?: error("nowhere to keep it")
            shelf.writeBytes(inFolder(folder, audioPath), m4a.readBytes(), "audio/mp4")
            shelf.write(notePath, "![[$audioPath]]\n")
        } catch (e: Exception) {
            _failed.value = true
            return
        } finally {
            m4a.delete()
        }
        // Not to be written down: the sound is kept in the note, and that is all.
        if (!writeDown) {
            wav.delete()
            _made.value = notePath
            refreshHearing()
            Notes.afterEdit()
            return
        }
        // What is left to do: hear the WAV, and put the words in this note under this link.
        File(dir, "$stamp.target").writeText("$notePath\n$audioPath\n")
        _made.value = notePath
        refreshHearing()
        Notes.afterEdit()
        catchUp(app)
    }

    /** Hear every recording still waiting. */
    fun catchUp(context: Context) {
        init(context)
        worker.execute {
            val lock = app.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "oboegaki:hearing")
            lock.acquire(60 * 60 * 1000L)
            try {
                val tried = mutableSetOf<String>()
                while (true) {
                    val target = dir.listFiles { f -> f.name.endsWith(".target") }
                        ?.sortedBy { it.name }
                        ?.firstOrNull { it.name !in tried } ?: break
                    tried += target.name
                    hear(target)
                }
            } finally {
                if (lock.isHeld) lock.release()
                refreshHearing()
            }
        }
    }

    private fun hear(target: File) {
        val stamp = target.name.removeSuffix(".target")
        val wav = File(dir, "$stamp.wav")
        val (notePath, audioPath) = target.readLines().let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        // The English model in the APK if the other one has gone missing, rather than nothing.
        val model = Speech.modelFor(Speech.language)
        val language = if (model == null) "en" else Speech.language
        val samples = runCatching { Wav.samples(wav.readBytes()) }.getOrNull()
        val text = when {
            samples == null -> ""
            !Listening.anythingSaid(samples) -> ""
            else -> Whisper.transcribe(app.assets, Whisper.MODEL, model?.path, language, samples, THREADS)
                ?.let(Listening::tidy)
                // Whisper could not run at all: left waiting, and tried again next time.
                ?: return
        }
        if (text.isNotEmpty()) Notes.addUnder(notePath, "![[$audioPath]]", text)
        wav.delete()
        target.delete()
        refreshHearing()
        Notes.afterEdit()
    }

    private fun refreshHearing() {
        _hearing.value = dir.listFiles { f -> f.name.endsWith(".target") }
            ?.mapNotNull { it.readLines().firstOrNull() }
            ?.toSet()
            .orEmpty()
    }

    /** Three of the Kompakt's four cores, so the phone stays usable while it listens. */
    private const val THREADS = 3
    private const val FULL = "full"
    /** "2026-10-05 0214": the date and the minute, as voice notes have always been titled. */
    private const val TITLE_STAMP = 15
}
