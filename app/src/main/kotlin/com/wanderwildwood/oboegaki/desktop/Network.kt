package com.wanderwildwood.oboegaki.desktop

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * What the phone's networks look like to Desktop Sync: which addresses the page can be reached
 * at, and which VPN networks count as "a VPN" for the VPN-only check. Read from the system's
 * own record of its networks, never from a request.
 */
object Network {

    private const val CACHE_MS = 5_000L

    /** The VPN networks a peer may come from, and whether one of the phone's VPNs is Tailscale. */
    private class Seen(val nets: List<Subnet>, val tailscale: Boolean)

    @Volatile private var cached: Pair<Long, Seen>? = null

    /** Read at most every few seconds, so a burst of requests asks once. */
    private fun seen(context: Context): Seen {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { (at, seen) -> if (now - at < CACHE_MS) return seen }
        val seen = read(context)
        cached = now to seen
        return seen
    }

    /** The VPN networks a peer may come from. */
    fun vpnSubnets(context: Context): List<Subnet> = seen(context).nets

    /**
     * Whether the phone is on Tailscale now: a VPN interface of its own holds an address in
     * 100.64/10. Only then is that range taken for Tailscale; on mobile data without the VPN it is
     * the carrier's shared network.
     */
    fun onTailscale(context: Context): Boolean = seen(context).tailscale

    fun peerAllowed(ip: String?, context: Context): Boolean = seen(context).let { isAllowedPeer(ip, it.nets, it.tailscale) }

    private fun read(context: Context): Seen {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return Seen(emptyList(), false)
        return runCatching {
            fun subnetsOf(vpn: Boolean): List<Subnet> = cm.allNetworks
                .filter { network ->
                    val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == vpn &&
                        (vpn || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
                }
                .mapNotNull { cm.getLinkProperties(it)?.interfaceName }
                .distinct()
                .flatMap { iface ->
                    NetworkInterface.getByName(iface)?.interfaceAddresses.orEmpty().mapNotNull { ia ->
                        val addr = ia.address ?: return@mapNotNull null
                        if (addr.isLoopbackAddress || addr.isLinkLocalAddress) null else Subnet(addr, ia.networkPrefixLength.toInt())
                    }
                }
            val vpn = subnetsOf(vpn = true)
            Seen(trustedVpnNets(vpn, subnetsOf(vpn = false)), com.wanderwildwood.oboegaki.desktop.onTailscale(vpn))
        }.getOrNull() ?: Seen(emptyList(), false)
    }

    private fun isPrivate(ip: String): Boolean =
        ip.startsWith("192.168.") || ip.startsWith("10.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(ip)

    private fun addressesOn(cm: ConnectivityManager, transport: Int): List<String> = runCatching {
        cm.allNetworks
            .filter { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@filter false
                caps.hasTransport(transport) && (transport == NetworkCapabilities.TRANSPORT_VPN || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            }
            .mapNotNull { cm.getLinkProperties(it)?.interfaceName }
            .distinct()
            .flatMap { iface ->
                NetworkInterface.getByName(iface)?.inetAddresses?.asSequence()
                    ?.filterIsInstance<Inet4Address>()
                    ?.filterNot { it.isLoopbackAddress }
                    ?.mapNotNull { it.hostAddress }
                    ?.toList().orEmpty()
            }
            .distinct()
    }.getOrDefault(emptyList())

    /** A way to reach the phone: what it is called here, and the address. */
    data class Where(val label: String, val address: String, val viaVpn: Boolean)

    /** The addresses the page can be opened at, VPN ones first. Cellular addresses are never offered. */
    fun reachable(context: Context): List<Where> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
        val out = LinkedHashMap<String, Where>()
        val trusted = vpnSubnets(context)
        for (a in addressesOn(cm, NetworkCapabilities.TRANSPORT_VPN)) {
            if (isTailscale(a)) out.putIfAbsent(a, Where("Tailscale", a, true))
            else {
                val lit = runCatching { java.net.InetAddress.getByName(a) }.getOrNull()
                if (lit != null && trusted.any { it.contains(lit) }) out.putIfAbsent(a, Where("VPN", a, true))
            }
        }
        for (a in addressesOn(cm, NetworkCapabilities.TRANSPORT_WIFI)) if (!isTailscale(a) && isPrivate(a)) out.putIfAbsent(a, Where("Wi-Fi", a, false))
        for (a in addressesOn(cm, NetworkCapabilities.TRANSPORT_ETHERNET)) if (!isTailscale(a) && isPrivate(a)) out.putIfAbsent(a, Where("Wired", a, false))
        return out.values.toList()
    }
}
