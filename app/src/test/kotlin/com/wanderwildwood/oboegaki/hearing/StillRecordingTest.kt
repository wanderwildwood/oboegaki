package com.wanderwildwood.oboegaki.hearing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StillRecordingTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun sound(name: String, writtenAt: Long): File =
        temp.newFile(name).apply {
            writeBytes(Wav.header(0))
            setLastModified(writtenAt)
        }

    @Test
    fun theRecordingUnderWayIsLeftAlone() {
        val wav = sound("2026-11-18 091500.wav", 1_000_000L)
        assertTrue(stillRecording(wav, File(temp.root, wav.name), 1_000_000L + 60_000))
    }

    @Test
    fun aSoundJustWrittenIsLeftAloneThoughNothingIsUnderWay() {
        // Begun after the folder was looked through: not yet known as the one under way.
        val wav = sound("2026-11-18 091500.wav", 1_000_000L)
        assertTrue(stillRecording(wav, null, 1_000_000L + 2_000))
    }

    @Test
    fun anOldSoundCutShortIsTakenUp() {
        val wav = sound("2026-11-18 091500.wav", 1_000_000L)
        assertFalse(stillRecording(wav, null, 1_000_000L + RECENT_MS + 1))
        assertFalse(stillRecording(wav, File(temp.root, "2026-11-18 091501.wav"), 1_000_000L + RECENT_MS + 1))
    }

    @Test
    fun aSoundNotThereIsNotUnderWay() {
        assertFalse(stillRecording(File(temp.root, "gone.wav"), null, 1_000_000L))
    }
}
