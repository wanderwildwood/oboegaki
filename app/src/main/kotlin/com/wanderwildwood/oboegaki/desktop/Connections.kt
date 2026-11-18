package com.wanderwildwood.oboegaki.desktop

import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * How long a connection to Desktop Sync may say nothing before it is closed: an idle keep-alive,
 * a request that never finishes, a peer that is refused. The browser opens a new one when it
 * needs it.
 */
const val REQUEST_TIMEOUT_MS = 10_000

/**
 * The start of the answer that turns a connection into a WebSocket. A browser opens a WebSocket
 * on a connection of its own, so this is the first thing written on it.
 */
private val UPGRADE = "HTTP/1.1 101".toByteArray(Charsets.US_ASCII)

/** Whether [head], the first bytes written on a connection, are the WebSocket upgrade. */
fun isUpgradeHead(head: ByteArray): Boolean = head.contentEquals(UPGRADE)

/**
 * Desktop Sync's listening socket. Each connection gets [REQUEST_TIMEOUT_MS] for every read, so
 * nobody holds one open by keeping quiet; once it has become a WebSocket the wait has no limit,
 * because the page only listens between the server's pings, and a page that stops answering
 * them is closed by the server itself.
 */
class DesktopServerSocket : ServerSocket() {
    override fun accept(): Socket {
        if (isClosed) throw SocketException("Socket is closed")
        if (!isBound) throw SocketException("Socket is not bound yet")
        val socket = DesktopConnection()
        implAccept(socket)
        return socket
    }
}

/** One connection, which takes off its read limit when the first thing written on it is the WebSocket upgrade. */
private class DesktopConnection : Socket() {

    private var watched: OutputStream? = null

    @Synchronized
    override fun getOutputStream(): OutputStream =
        watched ?: Watch(super.getOutputStream()).also { watched = it }

    private inner class Watch(out: OutputStream) : FilterOutputStream(out) {
        private val head = ByteArray(UPGRADE.size)
        private var seen = 0

        override fun write(b: Int) {
            look(byteArrayOf(b.toByte()), 0, 1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            look(b, off, len)
            out.write(b, off, len)
        }

        private fun look(b: ByteArray, off: Int, len: Int) {
            if (seen >= head.size || len <= 0) return
            val n = minOf(len, head.size - seen)
            System.arraycopy(b, off, head, seen, n)
            seen += n
            if (seen == head.size && isUpgradeHead(head)) runCatching { this@DesktopConnection.soTimeout = 0 }
        }
    }
}
