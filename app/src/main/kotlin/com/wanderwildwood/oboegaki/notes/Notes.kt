package com.wanderwildwood.oboegaki.notes

import android.content.Context
import com.wanderwildwood.oboegaki.sync.NextcloudRemote
import com.wanderwildwood.oboegaki.sync.Refused
import com.wanderwildwood.oboegaki.sync.Sync
import com.wanderwildwood.oboegaki.sync.Unreachable
import com.wanderwildwood.oboegaki.sync.merge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * The notes, wherever they are kept, and the one place the screens go to change them.
 *
 * With Nextcloud the app works on a copy kept in its own storage, and [sync] brings that copy
 * and the server together. Nothing waits on the network to open, save or list a note, so a
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

    private val nextcloudDir get() = File(appContext.filesDir, "nextcloud")
    private val mirror get() = File(nextcloudDir, "notes")

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        preferences = Preferences(appContext)
    }

    /** Where the notes are, or null before the reader has chosen. */
    fun shelf(): Shelf? = when (preferences.keeping) {
        Keeping.NOWHERE -> null
        Keeping.NEXTCLOUD -> FileShelf(mirror)
        Keeping.FOLDER -> preferences.folder?.let { FolderShelf(appContext.contentResolver, it) }
    }

    fun refresh() {
        scope.launch { reload() }
    }

    private fun reload() {
        _list.value = runCatching { shelf()?.list() }.getOrNull().orEmpty()
            .sortedByDescending { it.modified }
    }

    /**
     * Bring the copy here and the Nextcloud folder together, if that is where the notes are
     * kept. One at a time: a second ask while one is running is the same ask.
     */
    fun syncNow() {
        if (preferences.keeping != Keeping.NEXTCLOUD) return
        val account = preferences.account ?: return
        scope.launch {
            if (!syncing.tryLock()) return@launch
            try {
                _sync.value = SyncState.Running
                val remote = NextcloudRemote(account, preferences.remoteFolder)
                val sync = Sync(
                    notes = mirror,
                    base = File(nextcloudDir, "base"),
                    stateFile = File(nextcloudDir, "state"),
                    guard = { synchronized(notesLock) { it() } },
                )
                val result = sync.run(remote)
                preferences.lastSync = System.currentTimeMillis()
                _sync.value = SyncState.Idle
                if (result.received > 0 || result.kept.isNotEmpty()) _changed.value++
            } catch (_: Unreachable) {
                _sync.value = SyncState.Unreachable
            } catch (_: Refused) {
                _sync.value = SyncState.SignedOut
            } catch (e: Exception) {
                _sync.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                syncing.unlock()
                reload()
            }
        }
    }

    /** Wait for any sync in progress to finish, then run [block]. */
    private suspend fun afterSync(block: () -> Unit) = syncing.withLock { block() }

    /** Stop keeping notes in Nextcloud on this phone. What is on the server is not touched. */
    fun signOut() {
        scope.launch {
            afterSync {
                nextcloudDir.deleteRecursively()
                preferences.account = null
                preferences.keeping = Keeping.NOWHERE
                preferences.lastSync = 0
                _sync.value = SyncState.Idle
            }
            reload()
        }
    }

    /**
     * Point at another folder on the Nextcloud. The copy here is of the old folder, so it is
     * synced once more and then let go, and the new folder is fetched fresh.
     */
    fun changeRemoteFolder(folder: String) {
        scope.launch {
            afterSync { nextcloudDir.deleteRecursively() }
            preferences.remoteFolder = folder
            _list.value = emptyList()
            syncNow()
        }
    }

    fun read(path: String): String? = shelf()?.read(path)

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
            return Saved(at, now, merged)
        }
    }

    fun delete(path: String) {
        shelf()?.delete(path)
        reload()
    }

    fun afterEdit() {
        reload()
        syncNow()
    }
}
