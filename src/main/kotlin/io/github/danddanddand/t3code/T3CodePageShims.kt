package io.github.danddanddand.t3code

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.network.CefRequest

/**
 * Tells the T3 Code page about features the IDE's embedded Chromium reports but cannot deliver, so the app hides
 * the controls for them instead of offering a button that fails.
 *
 * Picture-in-picture: JCEF exposes `requestPictureInPicture` and reports `document.pictureInPictureEnabled`, but it
 * has no picture-in-picture window, so every request is rejected with "Picture-in-Picture is not available". T3 Code
 * offers its preview pop-out button whenever `pictureInPictureEnabled` is true, and hides it when it is false.
 */
internal object T3CodePageShims {
    // Defined on Document.prototype so the app's own feature check reads it; idempotent, so reloads and repeat runs are safe.
    private val SCRIPT = """
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

    fun install(target: JBCefBrowser) {
        target.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            // Load start covers the app's first render; load end repeats it in case the page's context was not ready yet.
            override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) =
                apply(browser, frame)

            override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) = apply(browser, frame)
        }, target.cefBrowser)
    }

    /** JCEF callbacks run over an RPC link that an escaping exception would reset, so nothing may leave here. */
    private fun apply(browser: CefBrowser?, frame: CefFrame?) {
        try {
            if (frame?.isMain != true) return
            frame.executeJavaScript(SCRIPT, frame.url ?: browser?.url ?: "", 0)
        } catch (e: Throwable) {
            thisLogger().warn("Could not apply T3 Code page shims", e)
        }
    }
}
