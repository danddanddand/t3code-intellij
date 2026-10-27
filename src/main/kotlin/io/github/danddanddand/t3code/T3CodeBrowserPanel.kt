package io.github.danddanddand.t3code

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandler
import org.cef.handler.CefRequestHandlerAdapter
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Owns the embedded browser's whole lifecycle. Every failure path (server down,
 * load error, renderer crash, unanswered pings) lands in [showStatus] so the
 * user always sees why the pane is empty and has a button to recover.
 */
class T3CodeBrowserPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true), Disposable {
    private val settings get() = T3CodeSettings.instance
    private val cards = CardLayout()
    private val cardPanel = JPanel(cards)
    private val statusPanel = StatusPanel()

    private var browser: JBCefBrowser? = null
    private var browserDisposable: Disposable? = null
    private var healthCheck: ScheduledFuture<*>? = null

    @Volatile private var lastPongMillis = 0L
    @Volatile private var pageLoaded = false
    @Volatile private var failureReported = false
    @Volatile private var disposed = false
    @Volatile private var serverSeen = false
    @Volatile private var serverDied = false
    private var modeAdviceShown = false

    init {
        Disposer.register(parent, this)
        cardPanel.add(statusPanel, CARD_STATUS)
        setContent(cardPanel)
    }

    fun start() {
        if (!JBCefApp.isSupported()) {
            showStatus(
                "Embedded browser unavailable",
                "JCEF is not supported by this IDE runtime. Use the bundled JetBrains Runtime, or check that the " +
                    "'Web Browser (JCEF)' bundled plugin is enabled.",
                retry = null,
            )
            return
        }
        if (T3CodeJcefMode.shouldOfferSwitch && !modeAdviceShown) {
            modeAdviceShown = true
            showStatus(
                "IDE browser mode needs a change",
                "This IDE build runs its embedded browser in a separate cef_server process, which drops the connection " +
                    "about two minutes after a page opens (JetBrains issue JBR-10027). Switching JCEF to in-process mode " +
                    "avoids it. The IDE restarts after the switch.",
                retry = "Switch and Restart IDE" to { T3CodeJcefMode.switchToInProcessAndRestart() },
                secondary = "Continue Anyway" to { settings.ignoreJcefModeAdvice = true; start() },
            )
            return
        }
        showStatus("Connecting", "Checking ${settings.serverUrl}…", retry = null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val problem = probeServer(settings.serverUrl)
            invokeLaterIfAlive {
                if (problem == null) {
                    openBrowser(settings.serverUrl)
                } else {
                    showStatus(
                        "T3 Code server not reachable",
                        "$problem<br><br>Start the T3 Code desktop app, or run <code>t3 serve</code>, then retry. " +
                            "The URL can be changed under Settings, Tools, T3 Code.",
                        retry = "Retry" to { restart() },
                    )
                }
            }
        }
    }

    fun goHome() = loadUrl(settings.serverUrl)

    fun restart() {
        thisLogger().info("Restarting T3 Code browser (serverDied=$serverDied)")
        tearDownBrowser()
        if (serverDied) {
            serverDied = false
            serverSeen = false
            relaunchJcef()
        }
        start()
    }

    // JBCefApp registers an IDE action that tears down and recreates the whole CEF app; it is the only
    // supported way to get a new cef_server once the old one is gone.
    private fun relaunchJcef() {
        val action = ActionManager.getInstance().getAction("RestartJCEFActionId")
        if (action == null) {
            thisLogger().warn("RestartJCEFActionId action not available; new browser may fail until the IDE restarts")
            return
        }
        val event = AnActionEvent.createEvent(DataContext.EMPTY_CONTEXT, null, "T3CodeToolWindow", ActionUiKind.NONE, null)
        ActionUtil.performAction(action, event)
    }

    fun pair() {
        showStatus("Pairing", "Running <code>t3 pair</code>…", retry = null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = T3Pairing.pair(settings)
            invokeLaterIfAlive {
                when (result) {
                    is T3Pairing.Result.Paired -> {
                        T3CodeNotifier.info(project, "T3 Code", "Pairing token minted, completing pairing in the embedded browser.")
                        if (browser == null) openBrowser(result.url) else loadUrl(result.url)
                    }
                    is T3Pairing.Result.Failed -> showStatus(
                        "Pairing failed",
                        result.message.replace("\n", "<br>"),
                        retry = "Try Again" to { pair() },
                        secondary = "Open Without Pairing" to { restart() },
                    )
                }
            }
        }
    }

    private fun openBrowser(url: String) {
        tearDownBrowser()
        val disposable = Disposer.newDisposable(this, "T3CodeBrowser")
        val created = try {
            JBCefBrowser.createBuilder().setOffScreenRendering(false).build().also { Disposer.register(disposable, it) }
        } catch (e: Throwable) {
            Disposer.dispose(disposable)
            browserCreationFailed(e)
            return
        }
        browser = created
        browserDisposable = disposable
        pageLoaded = false
        failureReported = false
        lastPongMillis = System.currentTimeMillis()

        try {
            installPing(created, disposable)
            installFailureHandlers(created)
        } catch (e: Throwable) {
            tearDownBrowser()
            browserCreationFailed(e)
            return
        }

        cardPanel.add(created.component, CARD_BROWSER)
        showBrowser()
        loadUrl(url)
        startHealthCheck()
    }

    private fun browserCreationFailed(e: Throwable) {
        thisLogger().warn("Embedded browser could not be created", e)
        serverDied = true
        failureReported = false
        reportFailure(
            "Embedded browser could not be created",
            "${e.javaClass.simpleName}: ${e.message ?: "no details"}. This usually means the IDE's Chromium server (cef_server) " +
                "is gone. Restart will ask the IDE to relaunch JCEF. ${T3CodeDiagnostics.logHint()}",
        )
    }

    private fun installPing(target: JBCefBrowser, disposable: Disposable) {
        val query = JBCefJSQuery.create(target as com.intellij.ui.jcef.JBCefBrowserBase)
        Disposer.register(disposable, query)
        query.addHandler {
            lastPongMillis = System.currentTimeMillis()
            null
        }
        pingScript = query.inject("'pong'")
    }

    private var pingScript: String? = null

    private fun installFailureHandlers(target: JBCefBrowser) {
        target.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (frame.isMain) {
                    pageLoaded = true
                    lastPongMillis = System.currentTimeMillis()
                }
            }

            override fun onLoadError(
                cefBrowser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String,
            ) {
                if (!frame.isMain || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return
                reportFailure(
                    "Page failed to load",
                    "$errorCode ($errorText) while loading $failedUrl. The T3 Code server may have stopped.",
                )
            }
        }, target.cefBrowser)

        target.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onRenderProcessTerminated(
                cefBrowser: CefBrowser, status: CefRequestHandler.TerminationStatus, errorCode: Int, errorString: String,
            ) {
                reportFailure(
                    "Browser renderer crashed",
                    "Chromium terminated the T3 Code page: $status ($errorCode${if (errorString.isBlank()) "" else ", $errorString"}).",
                )
            }
        }, target.cefBrowser)
    }

    private fun startHealthCheck() {
        healthCheck?.cancel(false)
        val interval = settings.healthCheckIntervalSeconds.toLong().coerceAtLeast(1)
        healthCheck = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::healthTick, interval, interval, TimeUnit.SECONDS)
    }

    private fun healthTick() {
        val target = browser ?: return
        val script = pingScript ?: return
        if (!pageLoaded || failureReported || disposed) return

        if (serverSeen && !T3CodeDiagnostics.isCefServerRunning()) {
            serverDied = true
            reportFailure(
                "Chromium server process exited",
                "The IDE's out-of-process Chromium (cef_server) is no longer running, so every embedded browser is dead. " +
                    (if (T3CodeJcefMode.isAffectedBuild) "This is JetBrains issue JBR-10027; switching JCEF to in-process mode fixes it. " else "") +
                    "Restart will ask the IDE to relaunch JCEF. ${T3CodeDiagnostics.logHint()}",
                fix = if (T3CodeJcefMode.isOutOfProcess && T3CodeJcefMode.isAffectedBuild)
                    "Switch to In-Process JCEF and Restart IDE" to { T3CodeJcefMode.switchToInProcessAndRestart() } else null,
            )
            return
        }
        if (!serverSeen) serverSeen = T3CodeDiagnostics.isCefServerRunning()

        val silentForMillis = System.currentTimeMillis() - lastPongMillis
        val timeoutMillis = settings.healthCheckTimeoutSeconds * 1000L
        if (silentForMillis > timeoutMillis) {
            reportFailure(
                "Browser not responding",
                "The T3 Code page has not answered a ping for ${silentForMillis / 1000} seconds. " +
                    "The embedded Chromium process has probably frozen or exited. ${T3CodeDiagnostics.logHint()}",
            )
            return
        }
        runCatching { target.cefBrowser.executeJavaScript(script, target.cefBrowser.url ?: "", 0) }
            .onFailure { thisLogger().warn("Ping to embedded browser failed", it) }
    }

    private fun reportFailure(title: String, detail: String, fix: Pair<String, () -> Unit>? = null) {
        if (failureReported) return
        failureReported = true
        thisLogger().warn("T3 Code browser failure: $title. $detail")
        invokeLaterIfAlive {
            if (fix != null) showStatus(title, detail, retry = fix, secondary = "Restart Browser" to { restart() })
            else showStatus(title, detail, retry = "Restart Browser" to { restart() })
            if (settings.notifyOnFailure) {
                T3CodeNotifier.error(project, "T3 Code: $title", detail, fix ?: ("Restart Browser" to { restart() }))
            }
        }
    }

    private fun loadUrl(raw: String) {
        val url = raw.trim()
        if (url.isEmpty()) return
        pageLoaded = false
        lastPongMillis = System.currentTimeMillis()
        browser?.loadURL(url) ?: openBrowser(url)
    }

    private fun showBrowser() = cards.show(cardPanel, CARD_BROWSER)

    private fun showStatus(title: String, detail: String, retry: Pair<String, () -> Unit>?, secondary: Pair<String, () -> Unit>? = null) {
        statusPanel.update(title, detail, retry, secondary)
        cards.show(cardPanel, CARD_STATUS)
    }

    private fun tearDownBrowser() {
        healthCheck?.cancel(false)
        healthCheck = null
        browser?.let { cardPanel.remove(it.component) }
        browserDisposable?.let { Disposer.dispose(it) }
        browser = null
        browserDisposable = null
        pingScript = null
        pageLoaded = false
    }

    private fun invokeLaterIfAlive(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({ if (!disposed) action() }) { disposed }
    }

    private fun probeServer(url: String): String? = try {
        val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
            HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5)).GET().build(),
            HttpResponse.BodyHandlers.discarding(),
        )
        if (response.statusCode() in 200..399) null else "Server answered HTTP ${response.statusCode()} at $url."
    } catch (e: Exception) {
        "Could not connect to $url (${e.javaClass.simpleName}: ${e.message ?: "no details"})."
    }

    fun reload() {
        browser?.cefBrowser?.reload() ?: restart()
    }

    fun openDevTools() {
        browser?.openDevtools()
    }

    override fun dispose() {
        disposed = true
        tearDownBrowser()
    }

    private class StatusPanel : JPanel(GridBagLayout()) {
        private val title = JBLabel().apply { font = JBUI.Fonts.label().biggerOn(2f); horizontalAlignment = SwingConstants.CENTER }
        private val detail = JBLabel().apply { horizontalAlignment = SwingConstants.CENTER }
        private val primary = JButton()
        private val secondary = JButton()

        init {
            val column = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(16)
                add(title); add(Box.createVerticalStrut(8)); add(detail); add(Box.createVerticalStrut(16))
                add(JPanel().apply { add(primary); add(secondary) })
            }
            listOf(title, detail).forEach { it.alignmentX = CENTER_ALIGNMENT }
            add(column, GridBagConstraints())
        }

        fun update(titleText: String, detailHtml: String, action: Pair<String, () -> Unit>?, second: Pair<String, () -> Unit>?) {
            title.text = titleText
            detail.text = "<html><div style='text-align:center;width:360px'>$detailHtml</div></html>"
            bind(primary, action)
            bind(secondary, second)
        }

        private fun bind(button: JButton, action: Pair<String, () -> Unit>?) {
            button.actionListeners.forEach(button::removeActionListener)
            button.isVisible = action != null
            if (action != null) {
                button.text = action.first
                button.addActionListener { action.second() }
            }
        }
    }

    companion object {
        private const val CARD_BROWSER = "browser"
        private const val CARD_STATUS = "status"
    }
}
