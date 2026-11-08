package io.github.danddanddand.t3code

import java.net.URI

/** Decides whether two addresses name the same T3 Code server, treating every loopback host as one. */
internal object T3ServerUrls {
    private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "::1")

    fun sameServer(url: String?, serverUrl: String?): Boolean {
        val uri = parse(url) ?: return false
        return sameServer(uri, serverUrl)
    }

    fun sameServer(uri: URI, serverUrl: String?): Boolean {
        val server = parse(serverUrl) ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        if (port(uri) != port(server)) return false
        val host = uri.host?.lowercase() ?: return false
        val serverHost = server.host?.lowercase() ?: return false
        return host == serverHost || (host in LOOPBACK && serverHost in LOOPBACK)
    }

    fun parse(url: String?): URI? = url?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { URI(it) }.getOrNull() }

    private fun port(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
