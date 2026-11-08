package io.github.danddanddand.t3code

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

/**
 * Keeps the pane on the T3 Code server. The pane has no address bar or back button, so following a link to
 * another site would strand the user on it. Like the T3 Code desktop app, the plugin opens anything that leaves
 * the server (links, new-window links, mailto: and friends) in the system browser instead.
 */
internal object T3CodeExternalLinks {
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
                    // A new window has nowhere to go in a tool window: off-server links open outside and same-server pages
                    // load in the pane. Anything else (about:blank, javascript:, data:) is a window the page means to fill
                    // itself; loading that here would replace T3 Code with an empty page, so it is dropped.
                    when {
                        targetUrl.isNullOrBlank() || openOutside(targetUrl, serverUrl()) -> Unit
                        T3ServerUrls.sameServer(targetUrl, serverUrl()) -> browser?.loadURL(targetUrl)
                        else -> thisLogger().info("Dropped a popup for $targetUrl; it would have replaced T3 Code in the pane")
                    }
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
        val uri = T3ServerUrls.parse(url) ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme in IN_PANE_SCHEMES) return false
        if (T3ServerUrls.sameServer(uri, serverUrl)) return false
        thisLogger().info("Opening $url in the system browser instead of the T3 Code pane")
        BrowserUtil.browse(uri)
        return true
    }
}
