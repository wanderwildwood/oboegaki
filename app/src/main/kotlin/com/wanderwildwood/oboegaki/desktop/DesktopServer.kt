package com.wanderwildwood.oboegaki.desktop

import android.content.Context
import android.util.Base64
import android.util.Log
import com.wanderwildwood.oboegaki.notes.Line
import com.wanderwildwood.oboegaki.notes.Notes
import com.wanderwildwood.oboegaki.notes.TRASH_DAYS
import com.wanderwildwood.oboegaki.notes.trashIsStale
import com.wanderwildwood.oboegaki.notes.addTask
import com.wanderwildwood.oboegaki.notes.cleanFolder
import com.wanderwildwood.oboegaki.notes.embeds
import com.wanderwildwood.oboegaki.notes.folders
import com.wanderwildwood.oboegaki.notes.lines
import com.wanderwildwood.oboegaki.notes.note
import com.wanderwildwood.oboegaki.notes.notesLock
import com.wanderwildwood.oboegaki.notes.toggle
import com.wanderwildwood.oboegaki.notes.withNoteReminder
import com.wanderwildwood.oboegaki.notes.withoutReminder
import com.wanderwildwood.oboegaki.remind.Reminders
import com.wanderwildwood.oboegaki.remind.TASK
import com.wanderwildwood.oboegaki.remind.isTask
import com.wanderwildwood.oboegaki.sync.merge
import com.wanderwildwood.oboegaki.tasks.Due
import com.wanderwildwood.oboegaki.tasks.Fields
import com.wanderwildwood.oboegaki.tasks.Tasks
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val TAG = "oboegaki-desktop"

/**
 * Desktop Sync's server: a small HTTP + WebSocket server inside the app that serves its own page
 * and the notes and tasks behind it to a browser on the same VPN. No cloud, no relay.
 *
 * Every request is checked twice before it is looked at: where it came from ([vpnOnly]), then
 * the token. All changes go through [Notes] and [Tasks], the objects the screens use; nothing
 * here opens the notes folder or the task files itself. Nothing a note says is ever logged.
 * The rules are in docs/DESKTOP-SYNC.md.
 */
class DesktopServer(
    port: Int,
    private val context: Context,
    private val token: String,
    private val pairing: Pairing,
    private val vpnOnly: () -> Boolean,
    private val page: () -> String,
) : NanoWSD(port) {

    private companion object {
        const val PING_SECONDS = 30L
        const val DEAD_PEER_MS = 95_000L
    }

    private val random = SecureRandom()
    private val sockets = Collections.newSetFromMap(ConcurrentHashMap<PushSocket, Boolean>())
    private val keepAlive = Executors.newSingleThreadScheduledExecutor()
    private var pinging: ScheduledFuture<*>? = null

    init {
        // SO_REUSEADDR, and unbound: NanoHTTPD binds it itself. So a switch off and on again, or a
        // reset, can take the port back at once instead of waiting out the old socket.
        setServerSocketFactory { java.net.ServerSocket().apply { reuseAddress = true } }
    }

    /** With "VPN only" on: this phone, Tailscale or a trusted VPN. With it off: still only private addresses. */
    private fun peerAllowed(ip: String?) = if (vpnOnly()) Network.peerAllowed(ip, context) else isPrivatePeer(ip)

    // ------------------------------------------------------------------ push

    fun browsers() = sockets.size

    /** Tell every listening browser to look again. */
    fun notifyChanged() {
        val payload = JSONObject().put("type", "changed").toString()
        sockets.toList().forEach { s -> runCatching { s.send(payload) }.onFailure { sockets.remove(s) } }
    }

    override fun stop() {
        pinging?.cancel(false)
        keepAlive.shutdownNow()
        super.stop()
    }

    /** The ping runs only while someone is listening, so an idle server costs no wakeups. */
    @Synchronized
    private fun pingIfNeeded() {
        if (sockets.isEmpty()) {
            pinging?.cancel(false)
            pinging = null
            return
        }
        if (pinging != null || keepAlive.isShutdown) return
        pinging = keepAlive.scheduleWithFixedDelay({
            val now = System.currentTimeMillis()
            sockets.toList().forEach { s ->
                when {
                    !s.isOpen -> sockets.remove(s)
                    now - s.lastPong > DEAD_PEER_MS -> {
                        runCatching { s.close(WebSocketFrame.CloseCode.GoingAway, "no pong", false) }
                        sockets.remove(s)
                    }
                    else -> runCatching { s.ping(ByteArray(0)) }.onFailure { sockets.remove(s) }
                }
            }
            pingIfNeeded()
        }, PING_SECONDS, PING_SECONDS, TimeUnit.SECONDS)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = PushSocket(handshake)

    private inner class PushSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        @Volatile var lastPong = System.currentTimeMillis()

        override fun onOpen() {
            if (!peerAllowed(handshakeRequest.remoteIpAddress)) {
                runCatching { close(WebSocketFrame.CloseCode.PolicyViolation, "no", false) }
                return
            }
            if (!tokenMatches(handshakeRequest.parameters["token"]?.firstOrNull(), token)) {
                runCatching { close(WebSocketFrame.CloseCode.PolicyViolation, "no", false) }
                return
            }
            lastPong = System.currentTimeMillis()
            sockets.add(this)
            pingIfNeeded()
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            sockets.remove(this)
        }

        // The page only listens; anything it says is ignored.
        override fun onMessage(message: WebSocketFrame) {}

        override fun onPong(pong: WebSocketFrame) {
            lastPong = System.currentTimeMillis()
        }

        override fun onException(exception: IOException) {
            sockets.remove(this)
        }
    }

    // ------------------------------------------------------------------ http

    override fun serveHttp(session: IHTTPSession): Response {
        // Before anything else, the token included: a caller off the VPN learns only that there
        // is nothing here, not that this is a running server.
        if (!peerAllowed(session.remoteIpAddress)) {
            Log.i(TAG, "refused a request from outside the VPN")
            return headed(newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, ""))
        }
        val response = try {
            route(session)
        } catch (r: Refusal) {
            json(r.status, JSONObject().put("error", r.message))
        } catch (e: Exception) {
            // The kind of failure, never what the note said.
            Log.w(TAG, "request failed: " + e.javaClass.simpleName)
            json(500, JSONObject().put("error", "the phone could not do that"))
        }
        return headed(response)
    }

    private fun status(code: Int): Response.IStatus = when (code) {
        200 -> Response.Status.OK
        304 -> Response.Status.NOT_MODIFIED
        400 -> Response.Status.BAD_REQUEST
        401 -> Response.Status.UNAUTHORIZED
        403 -> Response.Status.FORBIDDEN
        404 -> Response.Status.NOT_FOUND
        405 -> Response.Status.METHOD_NOT_ALLOWED
        409 -> Response.Status.CONFLICT
        413 -> object : Response.IStatus {
            override fun getDescription() = "413 Payload Too Large"
            override fun getRequestStatus() = 413
        }
        else -> Response.Status.INTERNAL_ERROR
    }

    private fun json(code: Int, body: JSONObject) = newFixedLengthResponse(status(code), "application/json; charset=utf-8", body.toString())

    /** No caching, nothing sniffed, no referrer, no framing. No CORS header is ever added. */
    private fun headed(r: Response): Response = r.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Cross-Origin-Resource-Policy", "same-origin")
    }

    private fun route(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/')
        val method = session.method

        if (method != Method.GET && method != Method.POST) {
            return json(405, JSONObject().put("error", "not allowed"))
        }

        if (method == Method.GET && (uri == "" || uri == "/index.html")) return servePage(session)
        if (uri == "/favicon.ico") return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")

        // The one route that answers without a token: it is how a browser gets one.
        if (uri == "/api/pair" && method == Method.POST) return pair(session)

        if (!bearerMatches(session.headers["authorization"], token)) {
            return json(401, JSONObject().put("error", "bad token"))
        }
        // A cross-origin page cannot add this header without a preflight that is never answered.
        if (method == Method.POST && session.headers["x-notes"] != "1") {
            return json(403, JSONObject().put("error", "not from the page"))
        }

        return when {
            method == Method.GET && uri == "/api/state" -> state(session)
            method == Method.GET && uri == "/api/file" -> file(session)
            method == Method.POST && uri == "/api/notes/create" -> createNote(body(session))
            method == Method.POST && uri == "/api/notes/update" -> updateNote(body(session))
            method == Method.POST && uri == "/api/notes/tick" -> tickItem(body(session))
            method == Method.POST && uri == "/api/notes/additem" -> addItem(body(session))
            method == Method.POST && uri == "/api/notes/pin" -> pinNote(body(session))
            method == Method.POST && uri == "/api/notes/archive" -> archiveNote(body(session))
            method == Method.POST && uri == "/api/notes/delete" -> deleteNote(body(session))
            method == Method.POST && uri == "/api/trash/restore" -> restoreNote(body(session))
            method == Method.POST && uri == "/api/trash/delete" -> deleteForever(body(session))
            method == Method.POST && uri == "/api/trash/empty" -> emptyTrash()
            method == Method.POST && uri == "/api/tasks/add" -> addTask(body(session))
            method == Method.POST && uri == "/api/tasks/update" -> updateTask(body(session))
            method == Method.POST && uri == "/api/tasks/tick" -> tickTask(body(session))
            method == Method.POST && uri == "/api/tasks/delete" -> deleteTask(body(session))
            else -> json(404, JSONObject().put("error", "not found"))
        }
    }

    /** The whole body as JSON, never more than [limit] bytes and never through a temp file. */
    private fun body(session: IHTTPSession, limit: Int = Limits.BODY_BYTES): JSONObject {
        val declared = session.headers["content-length"]?.toIntOrNull() ?: throw Refusal(400, "no length")
        if (declared < 0) throw Refusal(400, "bad length")
        if (declared > limit) throw Refusal(413, "too large")
        val buffer = ByteArray(declared)
        var read = 0
        val input = session.inputStream
        while (read < declared) {
            val n = input.read(buffer, read, declared - read)
            if (n <= 0) break
            read += n
        }
        if (read != declared) throw Refusal(400, "short body")
        return try {
            JSONObject(String(buffer, Charsets.UTF_8))
        } catch (e: org.json.JSONException) {
            throw Refusal(400, "not JSON")
        }
    }

    // ------------------------------------------------------------------ the page and pairing

    private fun servePage(session: IHTTPSession): Response {
        val nonce = Base64.encodeToString(ByteArray(16).also(random::nextBytes), Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
        val host = session.headers["host"]?.takeIf { Regex("^[A-Za-z0-9.\\-:\\[\\]]{1,100}$").matches(it) }
        val ws = if (host != null) " ws://$host" else ""
        val html = page().replace("__NONCE__", nonce)
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html).apply {
            addHeader(
                "Content-Security-Policy",
                "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; img-src 'self' blob: data:; " +
                    "connect-src 'self'$ws; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
            )
        }
    }

    private fun pair(session: IHTTPSession): Response {
        // A page on another site could otherwise spend the five tries in a hidden request.
        if (session.headers["x-notes-pairing"] != "1") return json(403, JSONObject().put("error", "open this page from the phone's address"))
        val code = body(session, Limits.PAIR_BODY_BYTES).optString("code").trim()
        if (!pairing.redeem(code)) {
            Log.i(TAG, "a pairing code was refused")
            // Expired, wrong, spent and never issued read the same: telling them apart helps only a guesser.
            return json(401, JSONObject().put("error", "that code is not valid. Get a fresh one on the phone."))
        }
        Log.i(TAG, "a pairing code was used")
        return json(200, JSONObject().put("token", token))
    }

    // ------------------------------------------------------------------ reading

    private fun state(session: IHTTPSession): Response {
        val ready = Notes.isSetUp()
        val pins = Notes.pins.value
        val all = Notes.list.value
        val rings = Reminders.load(context).filter { it.isTask }.map { it.path.removePrefix(TASK) }.toSet()
        val out = JSONObject()
            .put("ready", ready)
            .put("notes", JSONArray(all.map { noteJson(it, it.path in pins) }))
            .put("folders", JSONArray(folders(all)))
            .put("trash", JSONArray(Notes.trash.value.map(::trashJson)))
            .put("trashDays", TRASH_DAYS)
            .put("newFolder", Notes.newFolder)
            .put("taskLists", JSONArray(Tasks.shown.value.map { s -> taskListJson(s.list, s.items) { it.task.uid in rings } }))
            .put("defaultTaskList", runCatching { Tasks.defaultList().id }.getOrNull() ?: JSONObject.NULL)
        val text = out.toString()
        val etag = "\"" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(12).joinToString("") { "%02x".format(it) } + "\""
        if (session.headers["if-none-match"] == etag) {
            return newFixedLengthResponse(Response.Status.NOT_MODIFIED, "application/json", "").apply { addHeader("ETag", etag) }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text).apply { addHeader("ETag", etag) }
    }

    /** A picture a note embeds, by the note and the picture's name: nothing else on the phone can be asked for. */
    private fun file(session: IHTTPSession): Response {
        val id = session.parameters["id"]?.firstOrNull() ?: throw Refusal(400, "no note")
        val name = session.parameters["name"]?.firstOrNull() ?: throw Refusal(400, "no picture")
        if (!isSafePictureName(name)) throw Refusal(400, "not a picture")
        val text = noteText(id)
        if (embeds(text).none { it == name }) throw Refusal(404, "not in that note")
        val folder = id.substringBeforeLast('/', "")
        val path = if (folder.isEmpty()) name else "$folder/$name"
        val bytes = Notes.shelf()?.readBytes(path) ?: throw Refusal(404, "no such picture")
        if (bytes.size > Limits.PICTURE_BYTES) throw Refusal(413, "too large")
        val type = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }
        return newFixedLengthResponse(Response.Status.OK, type, ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    // ------------------------------------------------------------------ notes

    /** The path of a note the app has, by exactly the id it gave out. Never built from anything else. */
    private fun requireNote(o: JSONObject): String {
        val id = o.optString("id", "")
        if (id.isEmpty() || id.length > 400) throw Refusal(400, "which note?")
        if (Notes.list.value.none { it.path == id }) throw Refusal(404, "that note is gone")
        return id
    }

    private fun noteText(id: String): String {
        if (Notes.list.value.none { it.path == id }) throw Refusal(404, "that note is gone")
        return Notes.read(id) ?: throw Refusal(404, "that note is gone")
    }

    private fun noteReply(path: String, extra: JSONObject = JSONObject()): Response {
        val text = Notes.read(path) ?: throw Refusal(404, "that note is gone")
        val pinned = path in Notes.pins.value
        return json(200, extra.put("note", noteJson(note(path, text, System.currentTimeMillis()), pinned)))
    }

    private fun text(o: JSONObject, key: String, max: Int): String =
        requireText(if (o.has(key)) o.optString(key) else null, key, max).replace("\r\n", "\n").replace('\r', '\n')

    private fun createNote(o: JSONObject): Response {
        if (!Notes.isSetUp()) throw Refusal(409, "Notes has nowhere to keep notes yet")
        val title = requireTitle(if (o.has("title")) o.optString("title") else "")
        var text = text(o, "text", Limits.NOTE_TEXT)
        if (title.isBlank() && text.isBlank()) throw Refusal(400, "there is nothing to keep")
        val folderIn = if (o.has("folder")) requireText(o.optString("folder"), "folder", Limits.FOLDER) else Notes.newFolder
        val folder = cleanFolder(folderIn) ?: throw Refusal(400, "that folder name will not do")
        val reminder = requireReminder(if (o.isNull("reminder")) null else o.optString("reminder"))
        if (reminder != null) text = withNoteReminder(text, reminder)
        val saved = synchronized(notesLock) {
            val path = Notes.newPath(folder, title)
            Notes.save(path, "", text, title)
        }
        if (Notes.read(saved.path) == null) throw Refusal(400, "there is nothing to keep")
        Reminders.edited(context, saved.path, saved.path, "", saved.text)
        Notes.externalChange()
        return noteReply(saved.path)
    }

    private fun updateNote(o: JSONObject): Response {
        val id = requireNote(o)
        val title = requireTitle(if (o.has("title")) o.optString("title") else null)
        val mine = text(o, "text", Limits.NOTE_TEXT)
        val baseV = o.optString("v", "")
        val baseText = if (o.has("baseText")) text(o, "baseText", Limits.NOTE_TEXT) else null
        val setReminder = o.has("reminder")
        val reminder = if (setReminder) requireReminder(if (o.isNull("reminder")) null else o.optString("reminder")) else null

        var outcome = Outcome.CLEAN
        var path = id
        synchronized(notesLock) {
            val current = Notes.read(id) ?: throw Refusal(404, "that note is gone")
            val resolved = resolveEdit(versionOf(current) == baseV, baseText, mine, bodyOf(current), ::merge)
            outcome = resolved.outcome
            var next = withBody(current, resolved.text)
            if (setReminder) next = withNoteReminder(next, reminder)
            if (next.length > Limits.NOTE_TEXT + 10_000) throw Refusal(413, "the note would be too long")
            // [loaded] is what is on disk, so the app's own save does not merge a second time.
            val saved = Notes.save(id, current, next, title)
            path = saved.path
            Reminders.edited(context, id, saved.path, current, saved.text)
        }
        Notes.externalChange()
        return noteReply(path, JSONObject().put("conflict", when (outcome) {
            Outcome.CLEAN -> JSONObject.NULL
            Outcome.MERGED -> "merged"
            Outcome.KEPT_BOTH -> "kept-both"
        }))
    }

    private fun tickItem(o: JSONObject): Response {
        val id = requireNote(o)
        val line = o.optInt("line", -1)
        val expect = requireText(o.optString("expect", ""), "expect", Limits.ITEM)
        var stale = false
        var before = ""
        var after = ""
        Notes.change(id) { now ->
            before = now
            val l = lines(now).getOrNull(line)
            if (l !is Line.Task || withoutReminder(l.text) != expect) {
                stale = true
                now
            } else {
                toggle(now, line).also { after = it }
            }
        }
        if (stale) throw Refusal(409, "that item has changed; reloading")
        Reminders.edited(context, id, id, before, after)
        Notes.reloadNow()
        return noteReply(id)
    }

    private fun addItem(o: JSONObject): Response {
        val id = requireNote(o)
        val item = requireText(o.optString("text", ""), "item", Limits.ITEM, blankOk = false).replace('\n', ' ').trim()
        var before = ""
        var after = ""
        Notes.change(id) { now ->
            if (now.length + item.length > Limits.NOTE_TEXT) throw Refusal(413, "the note would be too long")
            before = now
            addTask(now, item).also { after = it }
        }
        Reminders.edited(context, id, id, before, after)
        Notes.reloadNow()
        return noteReply(id)
    }

    private fun pinNote(o: JSONObject): Response {
        val id = requireNote(o)
        if (o.optBoolean("pinned") != (id in Notes.pins.value)) Notes.togglePin(id)
        Notes.reloadNow()
        return noteReply(id)
    }

    private fun archiveNote(o: JSONObject): Response {
        val id = requireNote(o)
        val moved = (if (o.optBoolean("archived")) Notes.archive(id) else Notes.unarchive(id)) ?: throw Refusal(404, "that note is gone")
        Notes.reloadNow()
        return noteReply(moved, JSONObject().put("was", id))
    }

    /**
     * Delete is a move to the trash, not a removal. A request that says which version of the note
     * it was looking at is refused with 409 if the note has changed since, so a stale page cannot
     * trash words it never saw; the page reloads and asks again.
     */
    private fun deleteNote(o: JSONObject): Response {
        val id = requireNote(o)
        val current = Notes.read(id) ?: throw Refusal(404, "that note is gone")
        if (trashIsStale(if (o.has("v")) o.optString("v") else null, versionOf(current))) {
            throw Refusal(409, "that note has changed; reloading")
        }
        Notes.delete(id)
        Notes.reloadNow()
        return json(200, JSONObject().put("deleted", id).put("trash", JSONArray(Notes.trash.value.map(::trashJson))))
    }

    /** The trashed note the app has, by exactly the id it gave out. Never built from anything else. */
    private fun requireTrashed(o: JSONObject): String {
        val id = o.optString("id", "")
        if (id.isEmpty() || id.length > 64) throw Refusal(400, "which note?")
        if (Notes.trash.value.none { it.slot == id }) throw Refusal(404, "that note is not in the trash")
        return id
    }

    private fun restoreNote(o: JSONObject): Response {
        val id = requireTrashed(o)
        val path = Notes.restore(id) ?: throw Refusal(404, "that note is not in the trash")
        Notes.reloadNow()
        return noteReply(path, JSONObject().put("restored", id).put("trash", JSONArray(Notes.trash.value.map(::trashJson))))
    }

    private fun deleteForever(o: JSONObject): Response {
        val id = requireTrashed(o)
        Notes.deleteForever(id)
        Notes.reloadNow()
        return json(200, JSONObject().put("deleted", id).put("trash", JSONArray(Notes.trash.value.map(::trashJson))))
    }

    private fun emptyTrash(): Response {
        val n = Notes.emptyTrash()
        Notes.reloadNow()
        return json(200, JSONObject().put("emptied", n).put("trash", JSONArray(Notes.trash.value.map(::trashJson))))
    }

    // ------------------------------------------------------------------ tasks

    private fun requireTaskItem(o: JSONObject): com.wanderwildwood.oboegaki.tasks.Item {
        val list = o.optString("list", "")
        val name = o.optString("name", "")
        val shown = Tasks.shown.value.firstOrNull { it.list.id == list } ?: throw Refusal(404, "that list is gone")
        if (shown.list.readOnly) throw Refusal(403, "that list is read-only")
        return shown.items.firstOrNull { it.name == name } ?: throw Refusal(404, "that task is gone")
    }

    private fun taskReply(item: com.wanderwildwood.oboegaki.tasks.Item?): Response =
        json(200, JSONObject().put("task", item?.let { taskJson(it, Reminders.isTaskHere(context, it.task.uid)) } ?: JSONObject.NULL))

    private fun addTask(o: JSONObject): Response {
        val listId = if (o.has("list")) o.optString("list") else Tasks.defaultList().id
        val list = Tasks.writableLists().firstOrNull { it.id == listId } ?: throw Refusal(404, "that list cannot take tasks")
        val fields = fieldsFrom(o).copy(done = false)
        val item = Tasks.add(list.id, fields, ring = fields.due is Due.At)
        return taskReply(item)
    }

    private fun updateTask(o: JSONObject): Response {
        val item = requireTaskItem(o)
        val from = fieldsFrom(o.optJSONObject("from") ?: throw Refusal(400, "no starting point"), item.task.fields, titleNeeded = false)
        val to = fieldsFrom(o.optJSONObject("to") ?: throw Refusal(400, "nothing to change"), from)
        val ring = (if (o.has("rings")) o.optBoolean("rings") else Reminders.isTaskHere(context, item.task.uid)) && to.due is Due.At
        return taskReply(Tasks.edit(item, from, to, ring))
    }

    private fun tickTask(o: JSONObject): Response {
        val item = requireTaskItem(o)
        Tasks.tick(item)
        return taskReply(Tasks.store.item(item.list, item.name))
    }

    private fun deleteTask(o: JSONObject): Response {
        Tasks.delete(requireTaskItem(o))
        return json(200, JSONObject().put("deleted", true))
    }
}
