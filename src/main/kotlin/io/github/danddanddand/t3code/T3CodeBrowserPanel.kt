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
    @Volatile private var lastAutoRelaunchMillis = 0L
    @Volatile private var lastAutoPairMillis = 0L
    @Volatile private var pairing = false

    init {
        Disposer.register(parent, this)
        cardPanel.add(statusPanel, CARD_STATUS)
        setContent(cardPanel)
    }

    fun start() {
        // Must run before anything touches JBCefApp: creating a browser here would load Chromium in-process and crash the IDE.
        if (T3CodeJcefMode.isInProcessOnCrashingBuild) {
            thisLogger().warn("Not starting the embedded browser: in-process JCEF crashes this IDE build")
            showStatus(
                "Embedded browser blocked",
                T3CodeJcefMode.crashingBuildExplanation,
                retry = if (T3CodeJcefMode.isForcedByVmOption) null
                    else "Switch Back and Restart IDE" to { T3CodeJcefMode.switchToOutOfProcessAndRestart() },
            )
            return
        }
        if (!JBCefApp.isSupported()) {
            showStatus(
                "Embedded browser unavailable",
                "JCEF is not supported by this IDE runtime. Use the bundled JetBrains Runtime, or check that the " +
                    "'Web Browser (JCEF)' bundled plugin is enabled.",
                retry = null,
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

    // A new cef_server can only come from JBCefApp.restartJCEF. JBCefApp exposes it as the RestartJCEFActionId
    // action, but registers that action only in internal mode, so outside it the method is called reflectively
    // with the same arguments the action passes.
    private fun relaunchJcef() {
        val action = ActionManager.getInstance().getAction("RestartJCEFActionId")
        if (action != null) {
            val event = AnActionEvent.createEvent(DataContext.EMPTY_CONTEXT, null, "T3CodeToolWindow", ActionUiKind.NONE, null)
            ActionUtil.performAction(action, event)
            return
        }
        runCatching {
            val method = JBCefApp::class.java.getDeclaredMethod(
                "restartJCEF", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
            method.isAccessible = true
            method.invoke(JBCefApp.getInstance(), false, true) as Boolean
        }.onSuccess { restarted ->
            thisLogger().info("Relaunched JCEF via JBCefApp.restartJCEF: $restarted")
        }.onFailure {
            thisLogger().warn("Could not relaunch JCEF; new browser may fail until the IDE restarts", it)
        }
    }

    fun pair() {
        if (pairing) return
        pairing = true
        showStatus("Pairing", "Running <code>t3 pair</code> to connect this pane to the T3 Code server…", retry = null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = T3Pairing.pair(settings)
            invokeLaterIfAlive {
                pairing = false
                when (result) {
                    is T3Pairing.Result.Paired -> {
                        T3CodeNotifier.info(project, "T3 Code", "Pairing token minted, completing pairing in the embedded browser.")
                        loadUrl(result.url)
                    }
                    is T3Pairing.Result.Failed -> showStatus(
                        "Pairing failed",
                        result.message.replace("\n", "<br>") +
                            "<br><br>Alternatively, open the page without pairing and paste a token into its pairing form: " +
                            "run <code>t3 pair</code> in a terminal, or mint one in the T3 Code desktop app.",
                        retry = "Try Again" to { pair() },
                        secondary = "Open Without Pairing" to { goHome() },
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
        T3CodeJcefKeepAlive.apply()
        pageLoaded = false
        failureReported = false
        lastPongMillis = System.currentTimeMillis()

        try {
            installPing(created, disposable)
            installFailureHandlers(created)
            T3CodeExternalLinks.install(created) { settings.serverUrl }
        } catch (e: Throwable) {
            tearDownBrowser()
            browserCreationFailed(e)
            return
        }

        cardPanel.add(created.component, CARD_BROWSER)
        // JCEF may build the component's children lazily, so install once the pane is laid out.
        T3CodeFileDrop(project, created).let { drop -> drop.install(); invokeLaterIfAlive { if (browser === created) drop.install() } }
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
        query.addHandler { answer ->
            lastPongMillis = System.currentTimeMillis()
            if (answer == PONG_NEEDS_PAIRING) autoPairIfDue()
            null
        }
        // The server answers every path with its SPA, so the only sign that the pane is unpaired is the pairing form itself.
        pingScript = query.inject(
            "(function(){var t=(document.body&&document.body.innerText)||'';" +
                "return t.indexOf('$PAIRING_FORM_TITLE')>=0?'$PONG_NEEDS_PAIRING':'pong'})()"
        )
    }

    /** Runs `t3 pair` for the user when the page shows the pairing form, so the first visit never needs the gear menu. */
    private fun autoPairIfDue() {
        if (!settings.autoPair || pairing || disposed) return
        val now = System.currentTimeMillis()
        // A token the server rejects brings the form straight back; do not mint a new one every tick.
        if (now - lastAutoPairMillis < AUTO_PAIR_MIN_GAP_MILLIS) return
        lastAutoPairMillis = now
        thisLogger().info("Pairing form detected; running t3 pair automatically")
        invokeLaterIfAlive { pair() }
    }

    /** Pings right after a load, since the SPA renders the pairing form a moment after onLoadEnd. */
    private fun scheduleEarlyPings() {
        listOf(500L, 2000L).forEach { delay ->
            AppExecutorUtil.getAppScheduledExecutorService().schedule({ ping() }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun ping() {
        val target = browser ?: return
        val script = pingScript ?: return
        if (!pageLoaded || failureReported || disposed) return
        runCatching { target.cefBrowser.executeJavaScript(script, target.cefBrowser.url ?: "", 0) }
            .onFailure { thisLogger().warn("Ping to embedded browser failed", it) }
    }

    private var pingScript: String? = null

    // Out-of-process JCEF delivers these callbacks over its RPC link and may pass nulls (an ad iframe's load error
    // arrived with errorCode = null). An exception thrown here goes back into that link and resets it, after which the
    // page stops painting, so every parameter is nullable and every body is guarded.
    private fun installFailureHandlers(target: JBCefBrowser) {
        target.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) = guarded {
                if (frame?.isMain == true) {
                    pageLoaded = true
                    lastPongMillis = System.currentTimeMillis()
                    scheduleEarlyPings()
                }
            }

            override fun onLoadError(
                cefBrowser: CefBrowser?, frame: CefFrame?, errorCode: CefLoadHandler.ErrorCode?, errorText: String?, failedUrl: String?,
            ) = guarded {
                if (frame?.isMain != true || errorCode == null || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return@guarded
                // Only the T3 Code server matters; off-server pages never load in the pane.
                reportFailure(
                    "Page failed to load",
                    "$errorCode (${errorText.orEmpty()}) while loading ${failedUrl.orEmpty()}. The T3 Code server may have stopped.",
                )
            }
        }, target.cefBrowser)

        target.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onRenderProcessTerminated(
                cefBrowser: CefBrowser?, status: CefRequestHandler.TerminationStatus?, errorCode: Int, errorString: String?,
            ) = guarded {
                reportFailure(
                    "Browser renderer crashed",
                    "Chromium terminated the T3 Code page: $status ($errorCode${if (errorString.isNullOrBlank()) "" else ", $errorString"}).",
                )
            }
        }, target.cefBrowser)
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            thisLogger().warn("T3 Code browser callback failed", e)
        }
    }

    private fun startHealthCheck() {
        healthCheck?.cancel(false)
        val interval = settings.healthCheckIntervalSeconds.toLong().coerceAtLeast(1)
        healthCheck = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(::healthTick, interval, interval, TimeUnit.SECONDS)
    }

    private fun healthTick() {
        // cef_server connects asynchronously, and a relaunch replaces its pool; idle threads only expire after 60 s.
        T3CodeJcefKeepAlive.apply()
        if (browser == null || pingScript == null || !pageLoaded || failureReported || disposed) return

        if (serverSeen && !T3CodeDiagnostics.isCefServerRunning()) {
            serverDied = true
            // JBR-10027 kills cef_server every couple of minutes; recover quietly unless it fails right after a relaunch.
            val now = System.currentTimeMillis()
            if (now - lastAutoRelaunchMillis > AUTO_RELAUNCH_MIN_GAP_MILLIS) {
                lastAutoRelaunchMillis = now
                failureReported = true
                thisLogger().info("cef_server exited; relaunching JCEF automatically")
                invokeLaterIfAlive { restart() }
                return
            }
            reportFailure(
                "Chromium server process exited",
                "The IDE's out-of-process Chromium (cef_server) is no longer running, so every embedded browser is dead. " +
                    (if (T3CodeJcefMode.isAffectedBuild && !T3CodeJcefKeepAlive.isActive) "This is JetBrains issue JBR-10027. " else "") +
                    (if (T3CodeJcefMode.shouldOfferSwitch) "Switching JCEF to in-process mode fixes it. " else "") +
                    "Restart will ask the IDE to relaunch JCEF. ${T3CodeDiagnostics.logHint()}",
                fix = if (T3CodeJcefMode.shouldOfferSwitch)
                    "Switch to In-Process JCEF and Restart IDE" to { T3CodeJcefMode.switchToInProcessAndRestart() } else null,
            )
            return
        }
        if (!serverSeen) serverSeen = T3CodeDiagnostics.isCefServerRunning()

        val silentForMillis = System.currentTimeMillis() - lastPongMillis
        val timeoutMillis = settings.healthCheckTimeoutSeconds * 1000L
        if (silentForMillis > timeoutMillis) {
            // No pongs usually means JCEF's callback link is gone, which only a JCEF relaunch repairs.
            serverDied = true
            reportFailure(
                "Browser not responding",
                "The T3 Code page has not answered a ping for ${silentForMillis / 1000} seconds. " +
                    "The embedded Chromium process has probably frozen or exited. ${T3CodeDiagnostics.logHint()}",
            )
            return
        }
        ping()
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
        val target = browser ?: return openBrowser(url)
        target.loadURL(url)
        showBrowser()
    }

    private fun showBrowser() = cards.show(cardPanel, CARD_BROWSER)

    private fun showStatus(title: String, detail: String, retry: Pair<String, () -> Unit>?, secondary: Pair<String, () -> Unit>? = null) {
        statusPanel.update(title, detail, retry, secondary)
        cards.show(cardPanel, CARD_STATUS)
    }

    private fun tearDownBrowser() {
        healthCheck?.cancel(false)
        healthCheck = null
        // Hide the browser before removing it: removing the visible card makes CardLayout re-lay out the component,
        // and a JBCefOsrComponent that is already shutting down logs "Already disposed" from its resize alarm.
        if (browser != null && !disposed) cards.show(cardPanel, CARD_STATUS)
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
        // cef_server lives about two minutes under JBR-10027; dying again sooner than this means relaunching is not helping.
        private const val AUTO_RELAUNCH_MIN_GAP_MILLIS = 60_000L
        private const val AUTO_PAIR_MIN_GAP_MILLIS = 60_000L
        /** Heading of the server's pairing form; the ping looks for it to tell an unpaired pane from a paired one. */
        private const val PAIRING_FORM_TITLE = "Pair with this environment"
        private const val PONG_NEEDS_PAIRING = "pair"
    }
}
