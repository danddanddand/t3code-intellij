package io.github.danddanddand.t3code

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.net.URI

/**
 * Keeps the pane on the T3 Code server. The pane has no address bar or back button, so following a link to
 * another site would strand the user on it. Like the T3 Code desktop app, the plugin opens anything that leaves
 * the server (links, new-window links, mailto: and friends) in the system browser instead.
 */
internal object T3CodeExternalLinks {
    private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]", "::1")
    private val IN_PANE_SCHEMES = setOf("about", "data", "blob", "javascript", "devtools", "chrome", "chrome-error")

    fun install(target: JBCefBrowser, serverUrl: () -> String) {
        target.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                browser: CefBrowser?, frame: CefFrame?, request: CefRequest?, userGesture: Boolean, isRedirect: Boolean,
            ): Boolean = guarded { frame?.isMain == true && openOutside(request?.url, serverUrl()) }
        }, target.cefBrowser)

        target.jbCefClient.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser?, frame: CefFrame?, targetUrl: String?, targetFrameName: String?): Boolean =
                guarded {
                    // A new window has nowhere to go in a tool window: show off-server links outside, same-server ones in the pane.
                    if (!targetUrl.isNullOrBlank() && !openOutside(targetUrl, serverUrl())) browser?.loadURL(targetUrl)
                    true
                }
        }, target.cefBrowser)
    }

    /** JCEF callbacks run over an RPC link that an escaping exception would reset; fail open instead. */
    private inline fun guarded(block: () -> Boolean): Boolean =
        try {
            block()
        } catch (e: Throwable) {
            thisLogger().warn("T3 Code link handling failed", e)
            false
        }

    /** Returns true (and hands the URL to the system browser) when [url] should not load in the pane. */
    private fun openOutside(url: String?, serverUrl: String): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in IN_PANE_SCHEMES) return false
        if ((scheme == "http" || scheme == "https") && sameServer(uri, serverUrl)) return false
        thisLogger().info("Opening $url in the system browser instead of the T3 Code pane")
        BrowserUtil.browse(url)
        return true
    }

    private fun sameServer(uri: URI, serverUrl: String): Boolean {
        val server = runCatching { URI(serverUrl.trim()) }.getOrNull() ?: return false
        if (port(uri) != port(server)) return false
        val host = uri.host?.lowercase() ?: return false
        val serverHost = server.host?.lowercase() ?: return false
        return host == serverHost || (host in LOOPBACK && serverHost in LOOPBACK)
    }

    private fun port(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
}
