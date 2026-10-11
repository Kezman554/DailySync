package com.personalmorningalarm.data.remote

/**
 * The "host:port" rules shared by every Pi service address the app stores —
 * [AlfredSettings] (the vault-api) and [DashboardSettings] (the dashboard). One
 * set of rules, parameterised by each service's default port, so the two settings
 * can't drift into accepting different things.
 */
internal object HostAddress {

    /** A hostname or IPv4 address, then a port. IPv6 isn't supported. */
    private val HOST_PORT = Regex("""^[A-Za-z0-9.\-]+:(\d{1,5})$""")

    /**
     * Whether [input] normalises to an address we could actually build a URL
     * from. A bad address doesn't crash anything — the request just fails — but
     * it fails at the moment it's needed, so settings rejects it up front.
     */
    fun isValid(input: String, defaultPort: Int): Boolean {
        val port = HOST_PORT.matchEntire(normalise(input, defaultPort))
            ?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return port in 1..65535
    }

    /**
     * Tolerates what people actually type: a pasted scheme, a trailing slash or
     * path, surrounding spaces, or a bare host with no port (which gets
     * [defaultPort]). Blank input falls back to the default host on [defaultPort].
     */
    fun normalise(input: String, defaultPort: Int): String {
        val trimmed = input.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .trim()
        if (trimmed.isEmpty()) return defaultHost(defaultPort)
        return if (trimmed.contains(':')) trimmed else "$trimmed:$defaultPort"
    }

    /** Every Pi service lives on the same box; only the port differs. */
    fun defaultHost(defaultPort: Int): String = "$DEFAULT_ADDRESS:$defaultPort"

    private const val DEFAULT_ADDRESS = "192.168.1.100"
}
