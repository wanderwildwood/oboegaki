package com.wanderwildwood.oboegaki.sync

/**
 * Suffixes under which unrelated people's hosts sit side by side: a tailnet under ts.net, a
 * dynamic-DNS name under duckdns.org, a company under co.uk. Under these the domain a password
 * goes to is one label deeper, so a neighbor never counts as the same server's domain.
 */
private val SHARED_SUFFIXES = setOf(
    // Tailscale, dynamic DNS and home-NAS names.
    "ts.net", "duckdns.org", "ddns.net", "dynv6.net", "dyndns.org", "no-ip.org", "noip.me",
    "myfritz.net", "synology.me", "diskstation.me", "myds.me", "freeboxos.fr", "nsupdate.info",
    "dedyn.io", "desec.io",
    // Hosting with a name per customer.
    "github.io", "netlify.app", "herokuapp.com", "pages.dev", "workers.dev",
    // Countries that sell names one level down.
    "co.uk", "org.uk", "me.uk", "ltd.uk", "plc.uk", "ac.uk", "gov.uk", "net.uk",
    "com.au", "net.au", "org.au", "co.nz", "net.nz", "org.nz",
    "co.jp", "ne.jp", "or.jp", "co.za", "co.in", "co.kr", "or.kr",
    "com.br", "com.mx", "com.ar", "com.cn", "com.tw", "com.hk", "com.sg", "com.tr",
)

/**
 * The domain a server's password may go to, from a host name: its last two labels
 * (`p12-caldav.icloud.com` and `caldav.icloud.com` are both `icloud.com`), or three under a
 * shared suffix (`box.tail1234.ts.net` is `tail1234.ts.net`, never `ts.net`). An address is
 * itself; a trailing dot and letter case are ignored.
 */
fun credentialDomain(host: String): String {
    val name = host.removeSuffix(".").lowercase()
    if (name.contains(':') || Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(name)) return name
    val labels = name.split('.')
    if (labels.size <= 2) return name
    val lastTwo = labels.takeLast(2).joinToString(".")
    return if (lastTwo in SHARED_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
}

/** Whether the password for the server at [serverHost] may be sent to [host]. */
fun sameCredentialDomain(serverHost: String, host: String): Boolean =
    credentialDomain(serverHost) == credentialDomain(host)
