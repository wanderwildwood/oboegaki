package com.wanderwildwood.oboegaki.desktop

import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * The browsers that have paired with Desktop Sync. Each one holds its own session token, made
 * when its six-digit code was accepted. The phone keeps only a SHA-256 of the token, with a
 * label, the day it was made and the day it was last used; the token itself is shown once, to the
 * browser that asked, and is never written down on the phone or logged. Nothing here touches
 * Android, so the rules can be tested on a desk. See docs/DESKTOP-SYNC.md.
 */
class PairedBrowsers(
    private val now: () -> Long,
    private val random: SecureRandom = SecureRandom(),
    initial: String = "",
    private val save: (String) -> Unit = {},
) {

    companion object {
        const val MAX_BROWSERS = 8
        const val EXPIRES_MS = 30L * 24 * 60 * 60 * 1000
        const val TOKEN_BYTES = 32
        const val LABEL_MAX = 40

        /** A "last used" time is written to storage at most this often. */
        private const val SAVE_EVERY_MS = 60L * 60 * 1000
    }

    /** What the settings screen shows. The hash never leaves this class. */
    data class Browser(val id: String, val label: String, val created: Long, val lastUsed: Long)

    private class Entry(val id: String, val hash: String, val label: String, val created: Long, var lastUsed: Long, var savedAt: Long)

    private val entries = ArrayList<Entry>()
    private val _list = MutableStateFlow<List<Browser>>(emptyList())

    /** The paired browsers, most recently used first. */
    val list: StateFlow<List<Browser>> = _list

    /** Told which browsers were just removed (or expired), so their open connections can be closed. */
    @Volatile var onRemoved: ((Set<String>) -> Unit)? = null

    init {
        entries.addAll(parse(initial))
        // Anything already out of date is gone before it can be used.
        val gone = expireLocked()
        if (gone.isNotEmpty()) persistLocked()
        publishLocked()
    }

    /** Makes a new session token for a browser that has just proved itself with the code, and returns it. */
    fun issue(label: String): String {
        val token: String
        val removed = HashSet<String>()
        synchronized(this) {
            removed += expireLocked()
            val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            token = bytes.joinToString("") { "%02x".format(it) }
            val t = now()
            // Room for the new one: the browser unused for longest goes.
            while (entries.size >= MAX_BROWSERS) {
                val oldest = entries.minByOrNull { it.lastUsed } ?: break
                entries.remove(oldest)
                removed += oldest.id
            }
            entries.add(Entry(newId(), hash(token), cleanLabel(label), t, t, t))
            persistLocked()
            publishLocked()
        }
        if (removed.isNotEmpty()) onRemoved?.invoke(removed)
        return token
    }

    /**
     * The id of the browser this token belongs to, or null. Looks at every entry, whatever it finds,
     * so the time taken says nothing about how much was right. A token unused for 30 days is gone.
     */
    fun check(token: String?): String? {
        if (token.isNullOrEmpty() || token.length > 200) return null
        val given = hash(token)
        var removed: Set<String> = emptySet()
        var found: String? = null
        synchronized(this) {
            removed = expireLocked()
            var hit: Entry? = null
            for (e in entries) if (constantTimeEquals(e.hash, given)) hit = e
            if (hit != null) {
                val t = now()
                hit.lastUsed = t
                found = hit.id
                if (t - hit.savedAt >= SAVE_EVERY_MS) {
                    hit.savedAt = t
                    persistLocked()
                }
                publishLocked()
            } else if (removed.isNotEmpty()) {
                persistLocked()
                publishLocked()
            }
        }
        if (removed.isNotEmpty()) onRemoved?.invoke(removed)
        return found
    }

    /** True if there was such a browser. Its token stops working at once. */
    fun remove(id: String): Boolean {
        val had: Boolean
        synchronized(this) {
            had = entries.removeAll { it.id == id }
            if (had) {
                persistLocked()
                publishLocked()
            }
        }
        if (had) onRemoved?.invoke(setOf(id))
        return had
    }

    fun removeAll() {
        val ids: Set<String>
        synchronized(this) {
            ids = entries.map { it.id }.toSet()
            entries.clear()
            persistLocked()
            publishLocked()
        }
        if (ids.isNotEmpty()) onRemoved?.invoke(ids)
    }

    /** Drops what has gone 30 days unused and refreshes the list; for a screen that is about to show it. */
    fun refresh() {
        val removed: Set<String>
        synchronized(this) {
            removed = expireLocked()
            if (removed.isNotEmpty()) persistLocked()
            publishLocked()
        }
        if (removed.isNotEmpty()) onRemoved?.invoke(removed)
    }

    // ------------------------------------------------------------------ inside

    private fun expireLocked(): Set<String> {
        val t = now()
        val old = entries.filter { t - it.lastUsed > EXPIRES_MS || it.lastUsed - t > EXPIRES_MS }
        if (old.isEmpty()) return emptySet()
        entries.removeAll(old.toSet())
        return old.map { it.id }.toSet()
    }

    private fun publishLocked() {
        _list.value = entries.sortedByDescending { it.lastUsed }.map { Browser(it.id, it.label, it.created, it.lastUsed) }
    }

    private fun persistLocked() {
        save(entries.joinToString("\n") { "${it.id}\t${it.hash}\t${it.created}\t${it.lastUsed}\t${it.label}" })
    }

    private fun parse(text: String): List<Entry> = text.lineSequence().mapNotNull { line ->
        val p = line.split('\t', limit = 5)
        if (p.size < 5) return@mapNotNull null
        val created = p[2].toLongOrNull() ?: return@mapNotNull null
        val used = p[3].toLongOrNull() ?: return@mapNotNull null
        if (p[1].length != 64) return@mapNotNull null
        Entry(p[0], p[1], cleanLabel(p[4]), created, used, used)
    }.take(MAX_BROWSERS).toList()

    private fun newId(): String {
        while (true) {
            val id = ByteArray(4).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            if (entries.none { it.id == id }) return id
        }
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** A label that is safe to store and show: no control characters, no tabs, at most 40 characters. */
fun cleanLabel(raw: String?): String {
    val s = (raw ?: "").filter { !it.isISOControl() }.trim().take(PairedBrowsers.LABEL_MAX).trim()
    return s.ifEmpty { "A browser" }
}

/** "Firefox on Linux" from a User-Agent, which is all a browser tells; unknown parts are left out. */
fun browserLabel(userAgent: String?): String {
    val ua = userAgent ?: return "A browser"
    val browser = when {
        "Edg/" in ua || "EdgA/" in ua -> "Edge"
        "OPR/" in ua -> "Opera"
        "Firefox/" in ua -> "Firefox"
        "Chrome/" in ua || "CriOS/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> null
    }
    val system = when {
        "Android" in ua -> "Android"
        "iPhone" in ua || "iPad" in ua -> "iOS"
        "Windows" in ua -> "Windows"
        "Mac OS X" in ua || "Macintosh" in ua -> "Mac"
        "CrOS" in ua -> "ChromeOS"
        "Linux" in ua || "X11" in ua -> "Linux"
        else -> null
    }
    return when {
        browser != null && system != null -> "$browser on $system"
        browser != null -> browser
        system != null -> "A browser on $system"
        else -> "A browser"
    }
}
