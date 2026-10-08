package com.wanderwildwood.oboegaki.tasks

import android.content.Context
import com.wanderwildwood.oboegaki.R
import com.wanderwildwood.oboegaki.notes.Keeping
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.SyncState
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.remind.Times
import com.wanderwildwood.oboegaki.sync.Account
import com.wanderwildwood.oboegaki.sync.Refused
import com.wanderwildwood.oboegaki.sync.Unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDateTime

/** A list and its tasks, as the Tasks page shows them. */
data class Shown(val list: TaskList, val items: List<Item>)

/**
 * The tasks, and the one place the screens go to change them.
 *
 * With the notes on a Nextcloud, the lists are that Nextcloud's task lists, the same ones its
 * Tasks app, Thunderbird or any CalDAV client shows, synced with the account Notes already
 * holds. Without one, there is one list, kept on this phone; once Notes is on a Nextcloud, its
 * tasks can be moved there.
 */
object Tasks {

    private lateinit var appContext: Context
    lateinit var store: TaskStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncing = Mutex()

    private val _shown = MutableStateFlow<List<Shown>>(emptyList())
    val shown: StateFlow<List<Shown>> = _shown

    private val _sync = MutableStateFlow<SyncState>(SyncState.Idle)
    val sync: StateFlow<SyncState> = _sync

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        Notes.init(appContext)
        store = TaskStore(File(appContext.filesDir, "tasks"))
    }

    /** The Nextcloud account the tasks sync with, when the notes are kept on one. */
    private fun account(): Account? =
        if (Notes.preferences.keeping == Keeping.NEXTCLOUD) Notes.preferences.account else null

    val onNextcloud: Boolean get() = account() != null

    /** The lists to show: the server's, then the phone's when it has anything or there is no server. */
    private fun visible(): List<TaskList> {
        val all = store.lists()
        val server = if (onNextcloud) all.filter { !it.onPhone }.sortedBy { it.name.lowercase() } else emptyList()
        val phone = all.firstOrNull { it.onPhone }
        val keepPhone = server.isEmpty() || (phone != null && store.items(phone.id).isNotEmpty())
        val phoneList = if (keepPhone) phone ?: store.phoneList(appContext.getString(R.string.tasks_on_phone)) else null
        return server + listOfNotNull(phoneList)
    }

    fun reload() {
        _shown.value = runCatching { visible().map { Shown(it, store.items(it.id)) } }.getOrDefault(emptyList())
    }

    fun refresh() {
        scope.launch { reload() }
    }

    /** The list new tasks go in: the one chosen in Settings while it is there and writable, else the first that is. */
    fun defaultList(): TaskList {
        val lists = visible().filter { !it.readOnly }
        // Until one is chosen: Nextcloud's own first list, "personal", where it has one.
        return lists.firstOrNull { it.id == Notes.preferences.tasksList }
            ?: lists.firstOrNull { it.href != null && norm(it.href).endsWith("/personal") }
            ?: lists.firstOrNull { !it.onPhone }
            ?: store.phoneList(appContext.getString(R.string.tasks_on_phone))
    }

    fun writableLists(): List<TaskList> = visible().filter { !it.readOnly }

    // ---------------------------------------------------------------- sync

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

    suspend fun syncAndWait() = withContext(Dispatchers.IO) { syncing.withLock { runSync() } }

    /**
     * One last sync with the Nextcloud account Notes holds, whatever the notes are kept in now,
     * as they are being moved off it: so a task changed offline still goes up before it is let go.
     */
    suspend fun finalSync() = withContext(Dispatchers.IO) { syncing.withLock { runSync(Notes.preferences.account) } }

    private fun runSync(account: Account? = account()) {
        if (account == null) {
            _sync.value = SyncState.Idle
            reload()
            return
        }
        try {
            _sync.value = SyncState.Running
            val result = TaskSync(
                store,
                CalDavServer(account),
                said = { field, value -> appContext.getString(R.string.tasks_conflict_kept, fieldWord(field), value) },
                dueWords = { due -> due?.let { dueText(it) } ?: appContext.getString(R.string.task_due_none) },
            ).run()
            _sync.value = if (result.failed.isEmpty()) SyncState.Idle else SyncState.Failed(result.failed.joinToString(", "))
            if (result.received > 0) Reminders.syncSoon(appContext)
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

    private fun fieldWord(field: FieldName) = appContext.getString(
        when (field) {
            FieldName.SUMMARY -> R.string.task_field_title
            FieldName.DESCRIPTION -> R.string.task_field_notes
            FieldName.DUE -> R.string.task_field_due
            FieldName.PRIORITY -> R.string.task_field_priority
        },
    )

    /** "Tomorrow 17:00" or "Thu 8 Oct", on the phone's clock. */
    fun dueText(due: Due): String = when (val at = due.local()) {
        null -> Times.relativeDay(appContext, due.day())
        else -> Times.whenShort(appContext, at)
    }

    /** Stop syncing tasks with a server this phone no longer signs in to; the server keeps them. */
    fun forgetServer() {
        for (l in store.lists().filter { !it.onPhone }) store.dropList(l.id)
        _sync.value = SyncState.Idle
        reload()
    }

    // ---------------------------------------------------------------- changes

    private fun changed() {
        reload()
        syncNow()
        Reminders.syncSoon(appContext)
    }

    /** A new task. [ring]: it rings on this phone at its due time. */
    fun add(list: String, fields: Fields, ring: Boolean): Item {
        val item = store.add(list, fields, Instant.now())
        if (ring) ringHere(item.task.uid, fields)
        changed()
        return item
    }

    /** The task as the screen left it: [from] what it began with, [to] what it holds now. */
    fun edit(item: Item, from: Fields, to: Fields, ring: Boolean): Item? {
        val after = store.edit(item.list, item.name, to, Instant.now(), from) ?: return null
        if (ring) ringHere(after.task.uid, after.task.fields) else Reminders.unclaimTask(appContext, after.task.uid)
        changed()
        return after
    }

    /**
     * Ticked or unticked. A repeating task with a plain rule moves on to its next time and stays
     * open; any other is finished.
     */
    fun tick(item: Item) {
        val now = Instant.now()
        if (!item.task.fields.done && item.task.repeats != null) {
            val next = nextOccurrence(item.text)
            if (next != null) {
                store.replace(item.list, item.name, stamped(next, now))
                changed()
                return
            }
        }
        store.edit(item.list, item.name, item.task.fields.copy(done = !item.task.fields.done), now)
        changed()
    }

    /** A reminder's Done: the task with this UID ticked, if it is still open. */
    fun doneFromReminder(uid: String) {
        val item = store.find(uid) ?: return
        if (!item.task.fields.done) tick(item)
    }

    fun delete(item: Item) {
        store.delete(item.list, item.name)
        Reminders.unclaimTask(appContext, item.task.uid)
        changed()
    }

    fun move(item: Item, to: String): Item? {
        val moved = store.move(item.list, item.name, to)
        changed()
        return moved
    }

    /** Every task kept on this phone, moved into [to] on the Nextcloud. */
    fun moveToNextcloud(to: String) {
        for (item in store.items(PHONE)) store.move(PHONE, item.name, to)
        changed()
    }

    /** A new list on the Nextcloud. Throws when the server would not make it. */
    suspend fun makeList(name: String): TaskList? = withContext(Dispatchers.IO) {
        val account = account() ?: return@withContext null
        val made = CalDavServer(account).makeList(name)
        syncing.withLock { runSync() }
        store.lists().firstOrNull { it.href != null && norm(it.href) == norm(made.href) }
    }

    fun item(list: String, name: String): Item? = store.item(list, name)

    fun listName(id: String): String? = store.list(id)?.name

    private fun ringHere(uid: String, fields: Fields) {
        val at = fields.due?.local()
        // A task due on a day, with no time, has nothing to ring at.
        if (at == null) Reminders.unclaimTask(appContext, uid) else Reminders.claimTask(appContext, uid, fields.summary, at)
    }

    /** A file whose DUE moved: LAST-MODIFIED and DTSTAMP moved on as for any other change. */
    private fun stamped(text: String, now: Instant): String {
        val ical = ICal.parse(text)
        ical.set("LAST-MODIFIED", "LAST-MODIFIED:" + utcStamp(now))
        ical.set("DTSTAMP", "DTSTAMP:" + utcStamp(now))
        return ical.toString()
    }

    /** The task's due time on the phone's clock, if it has one: what a reminder rings at. */
    fun ringTime(item: Item): LocalDateTime? = item.task.fields.due?.local()
}
