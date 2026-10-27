package io.github.danddanddand.t3code

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.ui.jcef.JBCefApp
import org.cef.CefSettings
import org.cef.misc.CefLog
import java.nio.file.Path

/**
 * JCEF only honours its logging switches at startup, and in 2026.2 Chromium runs
 * in a separate `cef_server` process whose death leaves no trace in idea.log.
 * This plugin is normally what starts JCEF, so it turns the logging on first and
 * watches for the server process so a crash is reported with evidence.
 */
object T3CodeDiagnostics {
    val jcefLogPath: Path = Path.of(PathManager.getLogPath(), "t3code-jcef.log")

    @Volatile var loggingEnabled = false
        private set

    fun enableJcefLoggingIfPossible() {
        if (loggingEnabled) return
        // The properties only matter if JCEF has not started yet; CefLog itself can be re-pointed at any time.
        System.setProperty("jcef.log.level", "verbose")
        System.setProperty("jcef.log.path", jcefLogPath.toString())
        System.setProperty("CEF_SERVER_LOG_LEVEL", "5")
        System.setProperty("jcef_debug", "true")
        runCatching { CefLog.init(jcefLogPath.toString(), CefSettings.LogSeverity.LOGSEVERITY_VERBOSE, true) }
            .onFailure { thisLogger().warn("Could not redirect CefLog to $jcefLogPath", it) }
        loggingEnabled = true
        thisLogger().info("JCEF diagnostic logging enabled (jcefAlreadyStarted=${JBCefApp.isStarted()}), log at $jcefLogPath")
    }

    /** True when the out-of-process Chromium server that JCEF started is still alive. */
    fun isCefServerRunning(): Boolean = ProcessHandle.allProcesses().anyMatch { handle ->
        handle.info().command().orElse("").contains("cef_server")
    }

    fun logHint(): String = if (loggingEnabled) "JCEF log: $jcefLogPath" else "JCEF log is at its default location under ${PathManager.getLogPath()}"
}
