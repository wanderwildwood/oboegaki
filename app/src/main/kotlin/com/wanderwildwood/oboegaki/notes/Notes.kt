package com.wanderwildwood.oboegaki.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.wanderwildwood.oboegaki.sync.DavAccount
import com.wanderwildwood.oboegaki.sync.WebDavRemote
import com.wanderwildwood.oboegaki.sync.nextcloudRemote
import com.wanderwildwood.oboegaki.sync.Refused
import com.wanderwildwood.oboegaki.glance.GlanceProvider
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.tasks.Tasks
import com.wanderwildwood.oboegaki.sync.PINS
import com.wanderwildwood.oboegaki.sync.Sharing
import com.wanderwildwood.oboegaki.sync.isNote
import com.wanderwildwood.oboegaki.sync.Sync
import com.wanderwildwood.oboegaki.sync.Unreachable
import com.wanderwildwood.oboegaki.sync.merge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How the last sync went. */
sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    /** The server could not be reached; the notes here are still all here. */
    data object Unreachable : SyncState
    /** The server refused the app password: it was revoked, or the account changed. */
    data object SignedOut : SyncState
    data class Failed(val why: String) : SyncState
}

/**
 * Where the notes were kept, held while the reader chooses somewhere new. [bring]: whether
 * they are copied across once the new place is ready.
 */
data class Move(val keeping: Keeping, val folder: Uri?, val bring: Boolean)

/** How the last move went: running, or done with [failed] files that stayed behind. */
sealed interface Moving {
    data object Running : Moving
    data class Done(val failed: Int) : Moving
}

/**
 * The notes, wherever they are kept, and the one place the screens go to change them.
 *
 * On a server, Nextcloud or any other WebDAV, the app works on a copy kept in its own storage,
 * one for each kind, and [sync] brings that copy and the server together. Nothing waits on the network to open, save or list a note, so a
 * shopping list still opens in a shop with no signal.
 */
object Notes {

    private lateinit var appContext: Context
    lateinit var preferences: Preferences
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = Mutex()

    private val _list = MutableStateFlow<List<Note>>(emptyList())
    val list: StateFlow<List<Note>> = _list

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    /** Moves on every time a sync changes what is on the phone, so an open note can look again. */
    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed

    /** Where the copy of a server's notes, and what the sync knows of it, are kept. */
    private fun syncDir(place: Keeping) = File(appContext.filesDir, if (place == Keeping.WEBDAV) "webdav" else "nextcloud")
    private fun mirror(place: Keeping) = File(syncDir(place), "notes")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        preferences = Preferences(appContext)
    }

    /** Where the notes are, or null before the reader has chosen. */
    fun shelf(): Shelf? = when (preferences.keeping) {
        Keeping.NOWHERE -> null
        Keeping.NEXTCLOUD, Keeping.WEBDAV -> FileShelf(mirror(preferences.keeping)) {
            FileProvider.getUriForFile(appContext, "${appContext.packageName}.files", it)
        }
        Keeping.FOLDER -> preferences.folder?.let { FolderShelf(appContext.contentResolver, it) }
    }

    fun refresh() {
        scope.launch { reload() }
    }

    private var watching: Job? = null

    /**
     * While the app is in front, with the notes in a folder, look every few seconds for what
     * another app has written there, so a note made in Obsidian shows up once Syncthing brings
     * it, with nothing to reopen. A folder on a server, such as a DAVx5 mount, is asked less often.
     */
    fun watch() {
        if (watching?.isActive == true) return
        val shelf = shelf() as? FolderShelf ?: return
        watching = scope.launch {
            var seen = shelf.stamp()
            while (isActive) {
                delay(if (shelf.isLocal) 10_000L else 60_000L)
                val now = shelf.stamp() ?: continue
                if (now != seen) {
                    seen = now
                    reload()
                    _changed.value++
                }
            }
        }
    }

    fun stopWatching() {
        watching?.cancel()
        watching = null
    }

    private val _pins = MutableStateFlow<Set<String>>(emptySet())
    /** The notes pinned to the top of the list, by path. */
    val pins: StateFlow<Set<String>> = _pins

    private fun reload() {
        val shelf = shelf()
        _list.value = runCatching { shelf?.list() }.getOrNull().orEmpty()
            .sortedByDescending { it.modified }
        _pins.value = runCatching { readPins(shelf) }.getOrDefault(emptySet())
        // The lock screen shows the pinned notes; whatever just changed may be one of them.
        GlanceProvider.changed(appContext)
    }

    /** The pinned notes, in the order they were pinned, read now rather than remembered. */
    fun pinnedNow(): List<String> =
        shelf()?.read(PINS)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private fun readPins(shelf: Shelf?): Set<String> =
        shelf?.read(PINS)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /**
     * Change the pins, in the file that holds them. Kept as a list, one path to a line, so a pin
     * made here and one made on another device merge like any two edits to a note.
     */
    private fun editPins(change: (MutableSet<String>) -> Unit) {
        val shelf = shelf() ?: return
        synchronized(notesLock) {
            val before = runCatching { shelf.read(PINS) }.getOrNull().orEmpty()
            val pins = before.lines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            val set = pins.toMutableSet()
            change(set)
            val after = pins.filter { it in set } + set.filter { it !in pins }
            val text = after.joinToString("") { "$it\n" }
            if (text != before) shelf.write(PINS, text)
            _pins.value = after.toSet()
            GlanceProvider.changed(appContext)
        }
    }

    fun togglePin(path: String) {
        editPins { if (!it.remove(path)) it.add(path) }
        afterEdit()
    }

    private fun repin(from: String, to: String?) {
        if (from !in _pins.value) return
        editPins { it.remove(from); if (to != null) it.add(to) }
    }

    private val _shared = MutableStateFlow<Set<String>>(emptySet())
    /** The notes, by path, shared with someone or by someone. */
    val shared: StateFlow<Set<String>> = _shared

    /**
     * Bring the copy here and the server's folder together, if that is where the notes are
     * kept. One at a time: a second ask while one is running is the same ask.
     */
    fun syncNow() {
        scope.launch {
            if (!syncing.tryLock()) return@launch
            try {
                runSync()
            } finally {
                syncing.unlock()
            }
        }
    }

    /** Sync, waiting for one already running rather than skipping, and return once done. */
    suspend fun syncAndWait() {
        withContext(Dispatchers.IO) { syncing.withLock { runSync() } }
    }

    /**
     * Sync with the server at [place]: where the notes are kept, or, once more, one being moved
     * away from, so its last edits go up.
     */
    private fun runSync(place: Keeping = preferences.keeping) {
        if (!place.isServer) return
        val account = if (place == Keeping.NEXTCLOUD) preferences.account ?: return else null
        val dav = if (place == Keeping.WEBDAV) preferences.dav ?: return else null
        try {
            _sync.value = SyncState.Running
            // Sharing is Nextcloud's own; a plain WebDAV server has none.
            val sharing = account?.let(::Sharing)
            // A note someone shared lands at the top of this account's files. Moved into the
            // notes folder, it is a note like any other here. Never allowed to stop a sync.
            if (sharing != null) runCatching { adoptShared(sharing) }
            val remote = if (account != null) {
                nextcloudRemote(account, preferences.remoteFolder)
            } else {
                WebDavRemote(dav!!.address, dav.user, dav.password, preferences.davFolder)
            }
            val dir = syncDir(place)
            val sync = Sync(
                notes = mirror(place),
                base = File(dir, "base"),
                stateFile = File(dir, "state"),
                guard = { synchronized(notesLock) { it() } },
            )
            val result = sync.run(remote)
            preferences.lastSync = System.currentTimeMillis()
            _sync.value = SyncState.Idle
            if (sharing != null) runCatching { _shared.value = sharedPaths(sharing) } else _shared.value = emptySet()
            if (result.received > 0 || result.kept.isNotEmpty()) {
                _changed.value++
                // Someone else may have ticked an item that has a reminder here, or taken it off.
                Reminders.sync(appContext)
            }
        } catch (_: Unreachable) {
            _sync.value = SyncState.Unreachable
        } catch (_: Refused) {
            _sync.value = SyncState.SignedOut
        } catch (e: Exception) {
            _sync.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            reload()
        }
    }

    private val folderPrefix get() = "/" + preferences.remoteFolder.trim('/') + "/"

    private fun adoptShared(sharing: Sharing) {
        for (incoming in sharing.withMe()) {
            val name = incoming.path.substringAfterLast('/')
            if (!isNote(name) || incoming.path.startsWith(folderPrefix)) continue
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.')
            var n = 1
            while (n < 20) {
                val target = folderPrefix + (if (n == 1) name else "$stem $n.$ext")
                // Overwrite is refused, so a name already taken here fails and the next is tried.
                if (runCatching { sharing.move(incoming.path, target) }.isSuccess) break
                n++
            }
        }
    }

    private fun sharedPaths(sharing: Sharing): Set<String> =
        (sharing.mine() + sharing.withMe().map { it.path })
            .filter { it.startsWith(folderPrefix) }
            .map { it.removePrefix(folderPrefix) }
            .toSet()

    /** Sharing on the account the notes are kept in, or null when they are not on Nextcloud. */
    fun sharing(): Sharing? =
        if (preferences.keeping == Keeping.NEXTCLOUD) preferences.account?.let(::Sharing) else null

    /** A note's path as the server knows it, from the top of the account's files. */
    fun serverPath(path: String): String = folderPrefix + path

    /** Wait for any sync in progress to finish, then run [block]. */
    private suspend fun afterSync(block: () -> Unit) = syncing.withLock { block() }

    /** Stop keeping notes on the server on this phone. What is on the server is not touched. */
    fun signOut() {
        scope.launch {
            // The tasks, too, send what they have before they are let go.
            if (preferences.keeping == Keeping.NEXTCLOUD) runCatching { Tasks.finalSync() }
            afterSync {
                syncDir(preferences.keeping).deleteRecursively()
                forget(preferences.keeping)
                preferences.keeping = Keeping.NOWHERE
                preferences.lastSync = 0
                _sync.value = SyncState.Idle
            }
            reload()
        }
    }

    /** Let go of a server's sign-in: its password, and the shares it saw. */
    private fun forget(place: Keeping) {
        when (place) {
            Keeping.NEXTCLOUD -> {
                preferences.account = null
                // Its task lists are its own; the ones kept on this phone stay.
                Tasks.init(appContext)
                Tasks.forgetServer()
            }
            Keeping.WEBDAV -> preferences.dav = null
            else -> Unit
        }
        _shared.value = emptySet()
    }

    /** The folder on the server the notes are in, for whichever server they are kept on. */
    val remoteFolder: String
        get() = if (preferences.keeping == Keeping.WEBDAV) preferences.davFolder else preferences.remoteFolder

    /**
     * Point at another folder on the server. The copy here is of the old folder, so it is
     * synced once more and then let go, and the new folder is fetched fresh.
     */
    fun changeRemoteFolder(folder: String) {
        scope.launch {
            val place = preferences.keeping
            afterSync {
                runSync(place)
                syncDir(place).deleteRecursively()
            }
            if (place == Keeping.WEBDAV) preferences.davFolder = folder else preferences.remoteFolder = folder
            _list.value = emptyList()
            syncNow()
        }
    }

    /**
     * Keep the notes on a WebDAV server, already checked. With the notes there already, a new
     * password carries on as before; another server or folder is a new place, so the copy here
     * is synced once more with the old one, let go, and the new one fetched fresh.
     */
    fun useWebDav(dav: DavAccount, folder: String) {
        val now = preferences.dav
        val wanted = folder.trim('/', ' ')
        val onWebDav = preferences.keeping == Keeping.WEBDAV
        val samePlace = onWebDav && now?.address == dav.address && now.user == dav.user && preferences.davFolder == wanted
        if (!onWebDav || samePlace) {
            preferences.dav = dav
            preferences.davFolder = wanted
            preferences.keeping = Keeping.WEBDAV
            reload()
            syncNow()
            return
        }
        scope.launch {
            afterSync {
                runSync(Keeping.WEBDAV)
                syncDir(Keeping.WEBDAV).deleteRecursively()
                preferences.dav = dav
                preferences.davFolder = wanted
                preferences.lastSync = 0
            }
            _list.value = emptyList()
            syncNow()
        }
    }

    private val _moving = MutableStateFlow<Moving?>(null)
    val moving: StateFlow<Moving?> = _moving

    /** Where the notes are now, to be held while the reader chooses somewhere new. */
    fun startMove(bring: Boolean) = Move(preferences.keeping, preferences.folder, bring)

    /**
     * The reader has chosen somewhere new, and [preferences] already say where. Bring the notes
     * from where they were, if asked: copied, never moved, so the old place keeps every one.
     *
     * Leaving a server, the phone's copy goes up one last time first; it is let go afterwards
     * only if that worked or the notes came along, so nothing typed offline is lost. Going to a
     * server, its notes come down first, so one there with the same name is seen.
     */
    fun finishMove(from: Move) {
        scope.launch {
            val leavingServer = from.keeping.isServer && preferences.keeping != from.keeping
            var wentUp = false
            if (leavingServer) {
                syncing.withLock { runSync(from.keeping) }
                wentUp = _sync.value == SyncState.Idle
                if (from.keeping == Keeping.NEXTCLOUD) runCatching { Tasks.finalSync() }
            }
            if (preferences.keeping.isServer) syncAndWait()
            val old = when (from.keeping) {
                Keeping.NEXTCLOUD, Keeping.WEBDAV -> FileShelf(mirror(from.keeping))
                Keeping.FOLDER -> from.folder?.let { FolderShelf(appContext.contentResolver, it) }
                Keeping.NOWHERE -> null
            }
            val new = shelf()
            var brought = false
            if (from.bring && old != null && new != null) {
                _moving.value = Moving.Running
                val failed = copyAll(old, new)
                _moving.value = Moving.Done(failed)
                brought = failed == 0
            }
            if (leavingServer && (wentUp || brought)) {
                afterSync {
                    syncDir(from.keeping).deleteRecursively()
                    forget(from.keeping)
                    if (!preferences.keeping.isServer) preferences.lastSync = 0
                    _sync.value = SyncState.Idle
                }
            }
            afterEdit()
        }
    }

    /**
     * Copy every file from [from] to [to], and return how many could not be. A file already
     * there the same is passed over; a note there that differs keeps its place, and the one
     * coming in is kept beside it as "(other copy)", the way [save] keeps one it cannot merge.
     * A recording or scan of the same name already there is left as it is.
     */
    private fun copyAll(from: Shelf, to: Shelf): Int {
        var failed = 0
        val there = runCatching { to.files().toSet() }.getOrDefault(emptySet())
        for (path in runCatching { from.files() }.getOrDefault(emptyList())) {
            if (path == PINS) continue
            val ok = runCatching {
                val bytes = from.readBytes(path) ?: error("unreadable")
                synchronized(notesLock) {
                    val note = isNote(path.substringAfterLast('/'))
                    when {
                        path !in there && note -> to.write(path, bytes.toString(Charsets.UTF_8))
                        path !in there -> to.writeBytes(path, bytes, "application/octet-stream")
                        note && !(to.readBytes(path) contentEquals bytes) ->
                            to.write(otherCopy(to, path), bytes.toString(Charsets.UTF_8))
                        else -> Unit
                    }
                }
            }.isSuccess
            if (!ok) failed++
        }
        val pins = runCatching { readPins(from) }.getOrDefault(emptySet())
        if (pins.isNotEmpty()) runCatching { editPins { it.addAll(pins) } }
        return failed
    }

    private fun otherCopy(shelf: Shelf, path: String): String {
        val folder = path.substringBeforeLast('/', "")
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val name = path.substringAfterLast('/')
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "md")
        var candidate = "$prefix$stem (other copy).$ext"
        var n = 2
        while (shelf.exists(candidate)) {
            candidate = "$prefix$stem (other copy $n).$ext"
            n++
        }
        return candidate
    }

    fun read(path: String): String? = shelf()?.read(path)

    /**
     * Change a note from outside its screen, as a reminder's Done does: [edit] is given what
     * the note says now and returns what it should say. A note open on the screen sees it, as
     * it sees what a sync brings. Returns false when the note is not there.
     */
    fun change(path: String, edit: (String) -> String): Boolean {
        val shelf = shelf() ?: return false
        synchronized(notesLock) {
            val now = shelf.read(path) ?: return false
            val after = edit(now)
            if (after != now) shelf.write(path, after)
        }
        _changed.value++
        afterEdit()
        return true
    }

    /** The folder new notes go in, as the reader set it in settings. */
    val newFolder: String get() = preferences.newFolder

    /**
     * A path for a new note in [folder], named for [title] or, with none, for the moment, and
     * never one already taken.
     */
    fun newPath(folder: String, title: String = ""): String {
        val name = fileNameFor(title).ifEmpty {
            SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
        }
        return free(folder, name)
    }

    private fun free(folder: String, name: String, except: String? = null): String {
        val shelf = shelf()
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        var candidate = "$prefix$name.md"
        var n = 2
        while (candidate != except && shelf?.exists(candidate) == true) {
            candidate = "$prefix$name $n.md"
            n++
        }
        return candidate
    }

    /** What [save] did. [path] is where the note is now; [text] what it now says. */
    data class Saved(val path: String, val text: String, val merged: Boolean)

    /**
     * Save what the editor holds.
     *
     * [loaded] is the note as the editor last read or wrote it. If the file has moved on since,
     * because a sync brought in someone else's edit, the two are merged rather than the editor's
     * copy going over the top of theirs. If they cannot be merged, the editor's copy keeps the
     * name and the other is kept beside it, so the person typing does not lose their place.
     *
     * A new title renames the file. An empty note that was never anything else is not kept.
     */
    fun save(path: String, loaded: String, text: String, title: String): Saved {
        val shelf = shelf() ?: return Saved(path, text, false)
        synchronized(notesLock) {
            var now = text
            var merged = false
            val onDisk = shelf.read(path)
            if (onDisk != null && onDisk != loaded && onDisk != text) {
                val together = merge(loaded, text, onDisk)
                if (together != null) {
                    now = together
                    merged = true
                } else {
                    val name = path.substringAfterLast('/').substringBeforeLast('.')
                    shelf.write(free(path.substringBeforeLast('/', ""), "$name (other copy)"), onDisk)
                }
            }

            if (onDisk == null && now.isBlank() && title.isBlank()) {
                return Saved(path, now, false)
            }

            var at = path
            val wanted = fileNameFor(title)
            val current = path.substringAfterLast('/').substringBeforeLast('.')
            if (wanted.isNotEmpty() && wanted != current) {
                val target = free(path.substringBeforeLast('/', ""), wanted, except = path)
                if (onDisk != null) shelf.rename(path, target)
                at = target
            }
            if (onDisk == null || now != onDisk || at != path) shelf.write(at, now)
            if (at != path) repin(path, at)
            return Saved(at, now, merged)
        }
    }

    /**
     * Put a note away: moved, with the recordings and scans beside it that it links, into the
     * archive folder, under the folder it was in, so it can go back to the same place. Returns
     * where it went.
     */
    fun archive(path: String): String? = relocate(path) { folder ->
        if (folder.isEmpty()) ARCHIVE_FOLDER else "$ARCHIVE_FOLDER/$folder"
    }

    /** Bring an archived note back to the folder it was archived from. */
    fun unarchive(path: String): String? = relocate(path) { folder ->
        folder.removePrefix(ARCHIVE_FOLDER).trimStart('/')
    }

    private fun relocate(path: String, to: (String) -> String): String? {
        val shelf = shelf() ?: return null
        val folder = path.substringBeforeLast('/', "")
        val target = to(folder)
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val targetPrefix = if (target.isEmpty()) "" else "$target/"
        val name = path.substringAfterLast('/')
        val moved = free(target, name.substringBeforeLast('.'))
        synchronized(notesLock) {
            val text = shelf.read(path) ?: return null
            // The attachments go first and keep their names, so the note's links still find them.
            for (attachment in embeds(text)) {
                if (shelf.exists(prefix + attachment) && !shelf.exists(targetPrefix + attachment)) {
                    runCatching { shelf.move(prefix + attachment, targetPrefix + attachment) }
                }
            }
            shelf.move(path, moved)
        }
        // A note put away is not one to keep at the top; brought back, it is pinned again by hand.
        repin(path, null)
        // Its reminders go with it.
        Reminders.moved(appContext, path, moved)
        afterEdit()
        return moved
    }

    /**
     * Keep a scan: the PDF beside a new note that shows it, named for the moment, and return
     * the note's path. Null when there is nowhere to keep it.
     */
    fun saveScan(pdf: ByteArray): String? {
        val shelf = shelf() ?: return null
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
        val folder = preferences.newFolder
        val notePath = newPath(folder, "Scan $stamp")
        val stem = notePath.substringAfterLast('/').substringBeforeLast('.')
        shelf.writeBytes(inFolder(folder, "$stem.pdf"), pdf, "application/pdf")
        shelf.write(notePath, "![[$stem.pdf]]\n")
        afterEdit()
        return notePath
    }

    /**
     * Keep pictures shared in from another app: each copied beside a new note that shows them,
     * named for the moment the way scans and recordings are, and return the note's path. A
     * picture in a form other than JPEG, PNG or WebP is kept as a JPEG. Null when there is
     * nowhere to keep them, or none of them could be read.
     */
    fun savePictures(uris: List<Uri>): String? {
        val shelf = shelf() ?: return null
        val stamp = SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())
        val folder = preferences.newFolder
        val notePath = newPath(folder, "Picture $stamp")
        val stem = notePath.substringAfterLast('/').substringBeforeLast('.')
        val names = mutableListOf<String>()
        for (uri in uris) {
            val (bytes, ext) = runCatching { pictureBytes(uri) }.getOrNull() ?: continue
            var name = if (names.isEmpty()) "$stem.$ext" else "$stem ${names.size + 1}.$ext"
            var n = names.size + 2
            while (shelf.exists(inFolder(folder, name))) name = "$stem ${n++}.$ext"
            shelf.writeBytes(inFolder(folder, name), bytes, if (ext == "jpg") "image/jpeg" else "image/$ext")
            names += name
        }
        if (names.isEmpty()) return null
        shelf.write(notePath, names.joinToString("\n\n", postfix = "\n") { "![[$it]]" })
        afterEdit()
        return notePath
    }

    private fun pictureBytes(uri: Uri): Pair<ByteArray, String> {
        val resolver = appContext.contentResolver
        val bytes = resolver.openInputStream(uri)!!.use { it.readBytes() }
        val ext = when (resolver.getType(uri)?.lowercase()) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> null
        }
        if (ext != null) return bytes to ext
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("not a picture")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return out.toByteArray() to "jpg"
    }

    /** Whether there is somewhere to keep notes yet: a server signed in to, or a folder. */
    fun isSetUp(): Boolean = shelf() != null

    /**
     * Another app's note, [path] already checked by [com.wanderwildwood.oboegaki.capture.capturePath]:
     * made, or its text replaced, folders and all. It is an edit like any other here, so it is
     * synced, and merged on the server's side with anything changed there.
     */
    fun put(path: String, text: String) {
        val shelf = shelf() ?: error("Notes has nowhere to keep notes.")
        synchronized(notesLock) {
            if (shelf.read(path) != text) shelf.write(path, text)
        }
        afterEdit()
    }

    /** Another app's note taken away: that file, and nothing beside it. */
    fun remove(path: String) {
        val shelf = shelf() ?: error("Notes has nowhere to keep notes.")
        synchronized(notesLock) {
            if (shelf.exists(path)) shelf.delete(path)
        }
        repin(path, null)
        afterEdit()
    }

    /**
     * Put [text] as a paragraph under the line [after] in the note at [path], if the note is
     * still there and the line still in it. Used for a recording's words, which arrive long after
     * the note was made and perhaps after it was edited, so it goes under the recording wherever
     * that now is rather than at a remembered position.
     */
    fun addUnder(path: String, after: String, text: String) {
        val shelf = shelf() ?: return
        synchronized(notesLock) {
            val now = shelf.read(path) ?: return
            if (text in now) return
            val lines = now.split('\n').toMutableList()
            val at = lines.indexOfFirst { it.trim() == after }
            if (at < 0) return
            lines.add(at + 1, "")
            lines.add(at + 2, text)
            shelf.write(path, lines.joinToString("\n"))
        }
        _changed.value++
    }

    /**
     * Delete a note, and the recordings and scans it holds that sit beside it: `![[name]]` links
     * to files in the same folder. Anything another note also links stays.
     */
    fun delete(path: String) {
        val shelf = shelf() ?: return
        val text = shelf.read(path).orEmpty()
        val folder = path.substringBeforeLast('/', "")
        val prefix = if (folder.isEmpty()) "" else "$folder/"
        val held = embeds(text).map { prefix + it }
        shelf.delete(path)
        repin(path, null)
        val others = _list.value.filter { it.path != path }
        for (attachment in held) {
            val linkedElsewhere = others.any { note -> embeds(note.text).any { prefix + it == attachment || it == attachment } }
            if (!linkedElsewhere) runCatching { shelf.delete(attachment) }
        }
        refresh()
    }

    fun afterEdit() {
        // Off the main thread: listing a folder reads every note in it, and a folder that is a
        // mount of a server reads each one over the network.
        refresh()
        syncNow()
    }
}
