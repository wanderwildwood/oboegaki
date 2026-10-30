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

    @Volatile private var cached: Pair<Long, List<Subnet>>? = null

    /** The VPN networks a peer may come from, cached for a few seconds so a burst of requests asks once. */
    fun vpnSubnets(context: Context): List<Subnet> {
        val now = android.os.SystemClock.elapsedRealtime()
        cached?.let { (at, nets) -> if (now - at < CACHE_MS) return nets }
        val nets = read(context)
        cached = now to nets
        return nets
    }

    fun peerAllowed(ip: String?, context: Context): Boolean = isAllowedPeer(ip, vpnSubnets(context))

    private fun read(context: Context): List<Subnet> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
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
            trustedVpnNets(subnetsOf(vpn = true), subnetsOf(vpn = false))
        }.getOrNull().orEmpty()
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
