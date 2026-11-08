package io.github.danddanddand.t3code

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest
import java.awt.datatransfer.DataFlavor
import java.util.UUID

/**
 * Adjusts browser features that the IDE's embedded Chromium reports but cannot deliver, so T3 Code either hides the
 * control or gets a working implementation instead of one that fails.
 *
 * - Picture-in-picture: JCEF exposes `requestPictureInPicture` and reports `document.pictureInPictureEnabled`, but has
 *   no picture-in-picture window, so every request fails with "Picture-in-Picture is not available". T3 Code hides its
 *   preview pop-out button when `pictureInPictureEnabled` is false, so the shim reports false.
 * - Clipboard reads: `navigator.clipboard.readText()` needs a permission prompt JCEF cannot show, so it never settles
 *   (the terminal's Paste menu item did nothing). On T3 Code's own pages the shim answers it from the IDE clipboard.
 */
internal class T3CodePageShims private constructor(
    private val query: JBCefJSQuery,
    private val serverUrl: () -> String,
) {
    // The bridge function is visible to every frame, including MCP app iframes; only the main page's script knows this.
    private val nonce = UUID.randomUUID().toString()

    private val clipboardScript = """
        (function () {
          try {
            var clipboard = navigator.clipboard;
            if (!clipboard || clipboard.__t3codeReadText) return;
            var readText = function () {
              if (!document.hasFocus()) {
                return Promise.reject(new DOMException("Document is not focused.", "NotAllowedError"));
              }
              return new Promise(function (resolve, reject) {
                ${query.inject(
                    "'$READ_TEXT:$nonce'",
                    "function (text) { resolve(text); }",
                    "function (code, message) { reject(new DOMException(message || 'The clipboard is unavailable.', 'NotAllowedError')); }",
                )}
              });
            };
            Object.defineProperty(clipboard, "readText", { configurable: true, writable: true, value: readText });
            clipboard.__t3codeReadText = true;
          } catch (e) {}
        })();
    """.trimIndent()

    init {
        query.addHandler { request ->
            if (request != "$READ_TEXT:$nonce") return@addHandler JBCefJSQuery.Response(null, ERR_REFUSED, "Not allowed.")
            try {
                // An empty or non-text clipboard reads as "", as it does in a browser.
                JBCefJSQuery.Response(CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor).orEmpty())
            } catch (e: Throwable) {
                thisLogger().warn("Could not read the IDE clipboard for T3 Code", e)
                JBCefJSQuery.Response(null, ERR_UNAVAILABLE, "The clipboard is unavailable.")
            }
        }
    }

    /** JCEF callbacks run over an RPC link that an escaping exception would reset, so nothing may leave here. */
    private fun apply(browser: CefBrowser?, frame: CefFrame?) {
        try {
            if (frame?.isMain != true) return
            val url = frame.url ?: browser?.url ?: ""
            frame.executeJavaScript(PIP_SCRIPT, url, 0)
            if (T3ServerUrls.sameServer(url, serverUrl())) frame.executeJavaScript(clipboardScript, url, 0)
        } catch (e: Throwable) {
            thisLogger().warn("Could not apply T3 Code page shims", e)
        }
    }

    companion object {
        private const val READ_TEXT = "readText"
        private const val ERR_REFUSED = 1
        private const val ERR_UNAVAILABLE = 2

        // Defined on Document.prototype so the app's own feature check reads it; idempotent, so repeat runs are safe.
        private val PIP_SCRIPT = """
            (function () {
              try {
                var proto = Document.prototype;
                var current = Object.getOwnPropertyDescriptor(proto, "pictureInPictureEnabled");
                if (current && current.get && current.get.__t3codeShim) return;
                var get = function () { return false; };
                get.__t3codeShim = true;
                Object.defineProperty(proto, "pictureInPictureEnabled", { configurable: true, enumerable: true, get: get });
              } catch (e) {}
            })();
        """.trimIndent()

        fun install(target: JBCefBrowser, parent: Disposable, serverUrl: () -> String) {
            val query = JBCefJSQuery.create(target as com.intellij.ui.jcef.JBCefBrowserBase)
            Disposer.register(parent, query)
            val shims = T3CodePageShims(query, serverUrl)
            target.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                // Load start covers the app's first render; load end repeats it in case the page's context was not ready.
                override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) =
                    shims.apply(browser, frame)

                override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) = shims.apply(browser, frame)
            }, target.cefBrowser)
        }
    }
}
