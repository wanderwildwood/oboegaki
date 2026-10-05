package com.wanderwildwood.oboegaki.sync

import java.io.File

/**
 * Brings a folder of notes on this phone and a folder on a server to the same state.
 *
 * Three things are kept on the phone: [notes], the copies the app reads and writes; [base], each
 * note as it was the last time both ends agreed; and [stateFile], the server's etag for each note
 * at that moment. With those, every note falls into one of a few cases, and none of them needs a
 * clock: a note changed here if it differs from its base, and changed there if its etag moved.
 *
 * Where it changed on both, the two are merged line by line ([merge]). Where they cannot be, the
 * server's version keeps the name and what was written here is kept beside it as
 * "name (this phone).md", the same rule the Typewriter follows. Nothing is ever dropped to make
 * a sync come out tidy.
 *
 * [guard] wraps every touch of the phone's copies, and the editor takes the same guard to save,
 * so a sync can never write over a sentence typed while it was talking to the server. If the
 * copy here changed while the server was being asked, the copy is left alone and the next sync
 * deals with it.
 */
class Sync(
    private val notes: File,
    private val base: File,
    private val stateFile: File,
    private val guard: (() -> Unit) -> Unit = { it() },
) {

    /** Etag and hash of every attachment as both ends last agreed on it. Kept apart from the notes'. */
    private val blobStateFile = File(stateFile.parentFile, stateFile.name + ".attachments")

    data class Result(val sent: Int, val received: Int, val kept: List<String>)

    fun run(remote: Remote): Result {
        val state = readState().toMutableMap()
        val everything = remote.list()
        val there = everything.filterKeys { isNote(it.substringAfterLast('/')) }
        val here = localPaths()
        var sent = 0
        var received = 0
        val kept = mutableListOf<String>()

        for (path in (state.keys + there.keys + here).sorted()) {
            val known = state[path]
            val etag = there[path]
            val local = read(notes, path)
            val agreed = read(base, path)

            when {
                // Seen before, on both ends still.
                known != null && etag != null && local != null -> {
                    val changedHere = local != agreed
                    val changedThere = etag != known
                    when {
                        !changedHere && !changedThere -> Unit
                        !changedHere -> {
                            receive(remote, path, local, state)
                            received++
                        }
                        !changedThere -> try {
                            send(remote, path, local, Expect.Unchanged(known), state)
                            sent++
                        } catch (_: Moved) {
                            reconcile(remote, path, agreed ?: "", local, state, kept)
                            sent++
                            received++
                        }
                        else -> {
                            reconcile(remote, path, agreed ?: "", local, state, kept)
                            sent++
                            received++
                        }
                    }
                }

                // Gone from the server. Unchanged here means it was deleted there on purpose;
                // changed here means someone is still using it, so it goes back.
                known != null && etag == null && local != null -> {
                    if (local == agreed) {
                        guard { if (read(notes, path) == local) remove(notes, path) }
                        forget(path, state)
                    } else {
                        state.remove(path)
                        send(remote, path, local, Expect.Absent, state)
                        sent++
                    }
                }

                // Deleted on this phone. The server's copy goes too, unless it has changed since,
                // in which case the deletion was of something the deleter never saw, and the
                // newer version comes back.
                known != null && etag != null && local == null -> {
                    // A moved etag is not proof the note changed: a server may never have said
                    // it, or move it for sharing or a rescan. Before a deletion here is undone
                    // for being out of date, the note is read and compared, because assuming
                    // brought deleted notes back.
                    val unchanged = etag == known || remote.get(path).text == agreed
                    if (unchanged) {
                        try {
                            remote.delete(path, etag)
                            forget(path, state)
                        } catch (_: Moved) {
                            receive(remote, path, null, state)
                            received++
                        }
                    } else {
                        receive(remote, path, null, state)
                        received++
                    }
                }

                // Gone from both.
                known != null -> forget(path, state)

                // New on the server.
                etag != null && local == null -> {
                    receive(remote, path, null, state)
                    received++
                }

                // New here.
                etag == null && local != null -> try {
                    send(remote, path, local, Expect.Absent, state)
                    sent++
                } catch (_: Moved) {
                    reconcile(remote, path, "", local, state, kept)
                    sent++
                    received++
                }

                // New on both, never synced: two notes that happen to share a name.
                etag != null && local != null -> {
                    reconcile(remote, path, "", local, state, kept)
                    sent++
                    received++
                }
            }
            writeState(state)
        }

        // Copies made while reconciling are new notes, and go up now rather than next time.
        for (path in kept) {
            val text = read(notes, path) ?: continue
            runCatching { send(remote, path, text, Expect.Absent, state) }
            writeState(state)
        }

        val blobs = syncAttachments(remote, everything.filterKeys { isAttachment(it.substringAfterLast('/')) })
        return Result(sent + blobs.sent, received + blobs.received, kept + blobs.kept)
    }

    /**
     * Recordings, scans and pictures: files written once and never merged. The same cases as a
     * note, decided by etag on the server and by hash here; where both ends changed one, the
     * server's keeps the name and this phone's is kept beside it.
     */
    private fun syncAttachments(remote: Remote, there: Map<String, String>): Result {
        val state = readBlobState().toMutableMap()
        val here = mutableSetOf<String>()
        guard {
            notes.walkTopDown()
                .onEnter { it == notes || !it.name.startsWith(".") }
                .filter { it.isFile && isAttachment(it.name) }
                .forEach { here += it.relativeTo(notes).invariantSeparatorsPath }
        }
        var sent = 0
        var received = 0
        val kept = mutableListOf<String>()

        fun fetch(path: String) {
            val fetched = remote.getBytes(path)
            guard {
                writeBytes(notes, path, fetched.bytes)
                fetched.modified?.let { File(notes, path).setLastModified(it) }
            }
            state[path] = (fetched.etag ?: "") to hash(fetched.bytes)
            received++
        }

        fun upload(path: String, expect: Expect) {
            val bytes = File(notes, path).readBytes()
            val etag = remote.putBytes(path, bytes, expect)
            state[path] = (etag ?: "") to hash(bytes)
            sent++
        }

        fun keepBoth(path: String) {
            val beside = besideFree(path)
            guard { File(notes, path).renameTo(File(notes, beside).also { it.parentFile?.mkdirs() }) }
            kept += beside
            fetch(path)
            runCatching { upload(beside, Expect.Absent) }
        }

        for (path in (state.keys + there.keys + here).sorted()) {
            val known = state[path]
            val etag = there[path]
            val file = File(notes, path)
            val local = if (file.isFile) hash(file.readBytes()) else null

            when {
                known != null && etag != null && local != null -> {
                    val changedHere = local != known.second
                    val changedThere = etag != known.first
                    when {
                        !changedHere && !changedThere -> Unit
                        !changedHere -> fetch(path)
                        !changedThere -> try {
                            upload(path, Expect.Unchanged(known.first))
                        } catch (_: Moved) {
                            keepBoth(path)
                        }
                        else -> keepBoth(path)
                    }
                }
                known != null && etag == null && local != null -> {
                    if (local == known.second) {
                        guard { file.delete() }
                        state.remove(path)
                    } else {
                        upload(path, Expect.Absent)
                    }
                }
                known != null && etag != null && local == null -> {
                    val unchanged = etag == known.first || hash(remote.getBytes(path).bytes) == known.second
                    if (unchanged) {
                        try {
                            remote.delete(path, etag)
                            state.remove(path)
                        } catch (_: Moved) {
                            fetch(path)
                        }
                    } else {
                        fetch(path)
                    }
                }
                known != null -> state.remove(path)
                etag != null && local == null -> fetch(path)
                etag == null && local != null -> try {
                    upload(path, Expect.Absent)
                } catch (_: Moved) {
                    keepBoth(path)
                }
                etag != null && local != null -> {
                    val fetched = remote.getBytes(path)
                    if (hash(fetched.bytes) == local) {
                        state[path] = (fetched.etag ?: "") to local
                    } else {
                        keepBoth(path)
                    }
                }
            }
            writeBlobState(state)
        }
        return Result(sent, received, kept)
    }

    private fun writeBytes(root: File, path: String, bytes: ByteArray) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, ".${file.name}.part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(file)) {
            file.writeBytes(bytes)
            temp.delete()
        }
    }

    private fun readBlobState(): Map<String, Pair<String, String>> {
        if (!blobStateFile.isFile) return emptyMap()
        return blobStateFile.readLines()
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) null else parts[0] to (parts[1] to parts[2])
            }
            .toMap()
    }

    private fun writeBlobState(state: Map<String, Pair<String, String>>) {
        blobStateFile.parentFile?.mkdirs()
        val temp = File(blobStateFile.parentFile, blobStateFile.name + ".part")
        temp.writeText(state.entries.sortedBy { it.key }.joinToString("") { "${it.key}\t${it.value.first}\t${it.value.second}\n" })
        temp.renameTo(blobStateFile)
    }

    private fun hash(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Take the server's copy. [seen] is what this phone held when it decided to. */
    private fun receive(remote: Remote, path: String, seen: String?, state: MutableMap<String, String>) {
        val fetched = remote.get(path)
        var took = false
        guard {
            if (read(notes, path) == seen) {
                write(notes, path, fetched.text)
                // Dated as the server dates it, not as the moment it arrived, so a first sync
                // of a whole folder does not make every note look as if it was written just now.
                fetched.modified?.let { File(notes, path).setLastModified(it) }
                took = true
            }
        }
        // Typed into while the server was being asked: the base stays where it was, so the
        // next sync sees a change on both ends and merges, rather than a change only here
        // that would go up over the one just fetched.
        if (took) {
            write(base, path, fetched.text)
            state[path] = fetched.etag ?: ""
        }
    }

    private fun send(remote: Remote, path: String, text: String, expect: Expect, state: MutableMap<String, String>) {
        val etag = remote.put(path, text, expect)
        write(base, path, text)
        // A server that does not say the new etag gets an empty one, which never matches what
        // it lists, so the next sync fetches the note once and learns it.
        state[path] = etag ?: ""
    }

    /** Both ends changed [path] since [agreed]. Merge, or keep both. */
    private fun reconcile(
        remote: Remote,
        path: String,
        agreed: String,
        local: String,
        state: MutableMap<String, String>,
        kept: MutableList<String>,
    ) {
        val fetched = remote.get(path)
        val merged = merge(agreed, local, fetched.text)
        if (merged != null) {
            val etag = if (merged == fetched.text) {
                fetched.etag
            } else {
                remote.put(path, merged, fetched.etag?.let { Expect.Unchanged(it) } ?: Expect.Absent)
            }
            var took = false
            guard {
                if (read(notes, path) == local) {
                    write(notes, path, merged)
                    took = true
                }
            }
            // As in [receive]: if it was typed into meanwhile, the next sync merges again from
            // the old base, which is right, because the typing was done against that.
            if (took) {
                write(base, path, merged)
                state[path] = etag ?: ""
            }
        } else {
            var took = false
            guard {
                if (read(notes, path) == local) {
                    val beside = besideFree(path)
                    write(notes, beside, local)
                    kept += beside
                    write(notes, path, fetched.text)
                    took = true
                }
            }
            if (took) {
                write(base, path, fetched.text)
                state[path] = fetched.etag ?: ""
            }
        }
    }

    private fun forget(path: String, state: MutableMap<String, String>) {
        state.remove(path)
        remove(base, path)
    }

    /** "name (this phone).md", or "(this phone 2)" and on if that is taken too. */
    private fun besideFree(path: String): String {
        var n = 1
        while (true) {
            val candidate = besideName(path, n)
            if (!File(notes, candidate).exists()) return candidate
            n++
        }
    }

    private fun localPaths(): Set<String> {
        val out = mutableSetOf<String>()
        guard {
            notes.walkTopDown()
                .onEnter { it == notes || !it.name.startsWith(".") }
                .filter { it.isFile && isNote(it.name) }
                .forEach { out += it.relativeTo(notes).invariantSeparatorsPath }
        }
        return out
    }

    private fun read(root: File, path: String): String? {
        val file = File(root, path)
        return if (file.isFile) file.readText() else null
    }

    private fun write(root: File, path: String, text: String) {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        // Written beside and then moved over, so a phone that dies mid-write leaves the old
        // note rather than half of the new one.
        val temp = File(file.parentFile, ".${file.name}.part")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    private fun remove(root: File, path: String) {
        File(root, path).delete()
    }

    private fun readState(): Map<String, String> {
        if (!stateFile.isFile) return emptyMap()
        return stateFile.readLines()
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) null else line.substring(0, tab) to line.substring(tab + 1)
            }
            .toMap()
    }

    private fun writeState(state: Map<String, String>) {
        stateFile.parentFile?.mkdirs()
        val temp = File(stateFile.parentFile, stateFile.name + ".part")
        temp.writeText(state.entries.sortedBy { it.key }.joinToString("") { "${it.key}\t${it.value}\n" })
        temp.renameTo(stateFile)
    }
}

/** Whether a file is something a note can hold: a recording, a scan, a picture. */
fun isAttachment(name: String): Boolean {
    val lower = name.lowercase()
    if (lower.startsWith(".")) return false
    return ATTACHMENTS.any { lower.endsWith(".$it") }
}

private val ATTACHMENTS = listOf("m4a", "mp3", "wav", "ogg", "opus", "aac", "pdf", "jpg", "jpeg", "png")

/** Whether a file is something this app treats as a note. */
fun isNote(name: String): Boolean {
    val lower = name.lowercase()
    return !lower.startsWith(".") &&
        (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".markdown"))
}

/** Where what was written here goes, when the note itself has moved on somewhere else. */
fun besideName(path: String, n: Int = 1): String {
    val slash = path.lastIndexOf('/')
    val dir = path.substring(0, slash + 1)
    val name = path.substring(slash + 1)
    val label = if (n == 1) "(this phone)" else "(this phone $n)"
    val dot = name.lastIndexOf('.')
    return if (dot > 0) {
        "$dir${name.substring(0, dot)} $label${name.substring(dot)}"
    } else {
        "$dir$name $label"
    }
}
