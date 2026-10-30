package com.wanderwildwood.oboegaki.desktop

import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * The parts of Desktop Sync that decide who may read and change the notes, and what happens when
 * two edits meet. Nothing here touches Android, so each rule can be tested without a phone: they
 * are the last thing that should only be exercised by hand. See docs/DESKTOP-SYNC.md.
 */

// ------------------------------------------------------------------------------------ token

/** 24 characters of Crockford's alphabet: nothing that reads as another (no I, L, O, U). */
const val TOKEN_LENGTH = 24
private const val TOKEN_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

fun newToken(random: SecureRandom = SecureRandom()): String =
    buildString(TOKEN_LENGTH) { repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) } }

/**
 * Compares without stopping at the first character that differs, so how long the answer takes
 * says nothing about how much of the token was right. The length is not a secret.
 */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var difference = 0
    for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
    return difference == 0
}

/** The token as typed in a link: case does not matter, the alphabet has no lowercase. */
fun tokenMatches(supplied: String?, token: String): Boolean {
    if (supplied == null || token.isEmpty()) return false
    return constantTimeEquals(supplied.uppercase(), token.uppercase())
}

/** An `Authorization: Bearer` header, which a program sends and so must match exactly. */
fun bearerMatches(header: String?, token: String): Boolean {
    if (header == null || token.isEmpty()) return false
    val prefix = "Bearer "
    if (!header.startsWith(prefix)) return false
    return constantTimeEquals(header.substring(prefix.length), token)
}

// ----------------------------------------------------------------------------------- pairing

/**
 * A six-digit code the browser can exchange for the token, once.
 *
 * Reading 24 characters off an e-ink screen and typing them is tedious; six digits is not. It is
 * the one thing on the server that answers without a token, so it is bounded every way that
 * matters: three minutes, five tries, one use, and it exists only after someone asked for it on
 * the phone. [clock] counts milliseconds that only go forward (the phone's uptime clock, not the
 * wall clock, which jumps).
 */
class Pairing(private val clock: () -> Long, private val random: SecureRandom = SecureRandom()) {

    companion object {
        const val LIFETIME_MS = 3 * 60 * 1000L
        const val MAX_ATTEMPTS = 5
    }

    private var digits: String? = null
    private var expiresAt = 0L
    private var attemptsLeft = 0
    private var lockedOut = false

    /** What a dialog shows: the code and how many milliseconds it has left. */
    data class Shown(val code: String, val leftMs: Long)

    /**
     * The code to show now. One that has run out or been used is replaced by a new one, so a
     * dialog left open never shows a dead code; only one code is ever valid. After too many
     * wrong tries there is none (null) until [issue] is asked for, on the phone, by hand.
     */
    @Synchronized
    fun show(): Shown? {
        if (lockedOut) return null
        val code = current() ?: if (lockedOut) return null else issue()
        return Shown(code, expiresAt - clock())
    }

    /** A new code, replacing any outstanding one. */
    @Synchronized
    fun issue(): String {
        lockedOut = false
        val code = (0 until 6).map { random.nextInt(10) }.joinToString("")
        digits = code
        expiresAt = clock() + LIFETIME_MS
        attemptsLeft = MAX_ATTEMPTS
        return code
    }

    @Synchronized
    fun current(): String? {
        if (digits == null) return null
        val now = clock()
        if (now >= expiresAt || expiresAt - now > LIFETIME_MS) clear()
        return digits
    }

    /** True if [supplied] is the outstanding code. A right code is spent; so is one that has had too many wrong tries. */
    @Synchronized
    fun redeem(supplied: String?): Boolean {
        val expected = current() ?: return false
        val given = supplied?.filter { it.isDigit() } ?: return false
        if (given.length != expected.length || !constantTimeEquals(given, expected)) {
            attemptsLeft -= 1
            if (attemptsLeft <= 0) {
                clear()
                lockedOut = true
            }
            return false
        }
        clear()
        return true
    }

    @Synchronized
    fun clear() {
        digits = null
        expiresAt = 0
        attemptsLeft = 0
    }
}

// ---------------------------------------------------------------------------------- VPN check

/** A network and how much of an address is the network part of it. */
class Subnet(val address: InetAddress, val prefix: Int) {
    fun contains(peer: InetAddress): Boolean {
        val a = address.address
        val b = peer.address
        if (a.size != b.size) return false
        if (prefix < 0 || prefix > a.size * 8) return false
        val whole = prefix / 8
        for (i in 0 until whole) if (a[i] != b[i]) return false
        val rest = prefix % 8
        if (rest == 0) return true
        val mask = (0xFF shl (8 - rest)) and 0xFF
        return (a[whole].toInt() and mask) == (b[whole].toInt() and mask)
    }

    override fun toString() = "${address.hostAddress}/$prefix"
}

/** Tailscale's fixed range: 100.64.0.0/10. */
fun isTailscale(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4 || parts[0] != "100") return false
    val second = parts[1].toIntOrNull() ?: return false
    return second in 64..127
}

private fun literalAddress(addr: String): InetAddress? {
    if (!Regex("^[0-9a-f.:]+$").matches(addr)) return null
    return runCatching { InetAddress.getByName(addr) }.getOrNull()
}

/**
 * Whether a peer may talk to the server at all, with "VPN only" on: this phone itself, a
 * Tailscale address, or an address in the network of a VPN the phone has up ([vpnNets], already
 * stripped of any that overlap the Wi-Fi). Anything else, including something that is not an
 * address, is no.
 */
fun isAllowedPeer(ip: String?, vpnNets: List<Subnet>): Boolean {
    val addr = ip?.substringBefore('%')?.lowercase() ?: return false
    if (addr == "127.0.0.1" || addr == "::1" || addr == "0:0:0:0:0:0:0:1") return true
    if (isTailscale(addr)) return true
    if (addr.startsWith("fd7a:115c:a1e0")) return true
    if (vpnNets.isEmpty()) return false
    val peer = literalAddress(addr) ?: return false
    return vpnNets.any { it.contains(peer) }
}

/** The VPN's networks that are safe to trust: not Tailscale's own, not overlapping [local], not tiny prefixes. */
fun trustedVpnNets(vpn: List<Subnet>, local: List<Subnet>): List<Subnet> =
    vpn.filterNot { net ->
        val host = net.address.hostAddress?.lowercase().orEmpty()
        isTailscale(host) || host.startsWith("fd7a:115c:a1e0")
    }
        // A /1 would let half the internet in.
        .filter { net -> net.prefix >= (if (net.address is Inet4Address) 8 else 32) }
        .filterNot { net -> local.any { net.contains(it.address) || it.contains(net.address) } }

// ------------------------------------------------------------------------------------ limits

object Limits {
    const val BODY_BYTES = 512 * 1024
    const val PAIR_BODY_BYTES = 512
    const val NOTE_TEXT = 200_000
    const val TITLE = 120
    const val FOLDER = 200
    const val ITEM = 3_000
    const val TASK_SUMMARY = 500
    const val TASK_DESCRIPTION = 20_000
    const val PICTURE_BYTES = 8 * 1024 * 1024
}

/** Why a request was refused, in words for the page. */
class Refusal(val status: Int, message: String) : Exception(message)

fun requireText(value: String?, name: String, max: Int, blankOk: Boolean = true): String {
    val v = value ?: throw Refusal(400, "$name is missing")
    if (v.length > max) throw Refusal(413, "$name is too long")
    if (!blankOk && v.isBlank()) throw Refusal(400, "$name is empty")
    if (v.indexOf('\u0000') >= 0) throw Refusal(400, "$name has a null character")
    return v
}

/** A note title as a file name could use it: no slashes, no control characters, not blank-only. */
fun requireTitle(value: String?): String {
    val v = requireText(value ?: "", "title", Limits.TITLE)
    if (v.any { it.code < 32 }) throw Refusal(400, "title has a control character")
    return v.trim()
}

private val REMINDER = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}""")

/** A reminder time as the app writes it, "2026-10-08 17:00", real date and clock time; "" or null for none. */
fun requireReminder(value: String?): String? {
    if (value.isNullOrEmpty()) return null
    if (!REMINDER.matches(value)) throw Refusal(400, "reminder must be like 2026-10-08 17:00")
    val ok = runCatching {
        java.time.LocalDateTime.parse(
            value,
            java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(java.time.format.ResolverStyle.STRICT),
        )
    }.isSuccess
    if (!ok) throw Refusal(400, "reminder is not a real time")
    return value
}

/** Whether [name] is a picture file name that cannot climb out of a folder. */
fun isSafePictureName(name: String): Boolean =
    name.isNotEmpty() && name.length <= 255 && '/' !in name && '\\' !in name && !name.startsWith(".") &&
        name.none { it.code < 32 } &&
        listOf("jpg", "jpeg", "png", "webp").any { name.lowercase().endsWith(".$it") }

// ---------------------------------------------------------------------------------- versions

/** The version tag of a note: the first 12 hex digits of the SHA-256 of its text. */
fun versionOf(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .take(6).joinToString("") { "%02x".format(it) }

/** The heading over the text a later write replaced. */
const val EDITED_ELSEWHERE = "Edited elsewhere"

enum class Outcome { CLEAN, MERGED, KEPT_BOTH }

/** What an edit from the browser comes to, and how. */
data class Resolved(val text: String, val outcome: Outcome)

/**
 * Put together an edit made in the browser and the note as the phone has it now.
 *
 * [started]: the browser began from the note as the phone still has it, so its text is simply
 * written. [baseText] is what the browser began from, [mine] what it sends, [current] what the
 * phone has. If both changed it, the lines each touched are merged if they are different lines
 * ([merge], the rule the sync uses); if the same lines, the browser's write is the later and
 * stands, and the text it replaced is kept under a heading at the end, so nobody's words are lost.
 */
fun resolveEdit(started: Boolean, baseText: String?, mine: String, current: String, merge: (String, String, String) -> String?): Resolved {
    if (started || mine == current || (baseText != null && baseText == current)) {
        return Resolved(mine, Outcome.CLEAN)
    }
    if (baseText != null) {
        merge(baseText, mine, current)?.let { return Resolved(it, Outcome.MERGED) }
    }
    val kept = current.trim('\n')
    val head = mine.trimEnd('\n')
    val together = if (kept.isEmpty()) mine else
        (if (head.isEmpty()) "" else head + "\n\n") + "## $EDITED_ELSEWHERE\n\n" + kept + "\n"
    return Resolved(together, Outcome.KEPT_BOTH)
}

/** Time left as "2:41", rounded up to the second, never below 0:00. */
fun countdownText(leftMs: Long): String {
    val seconds = if (leftMs <= 0) 0 else (leftMs + 999) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
