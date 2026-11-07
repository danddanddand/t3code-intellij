package io.github.danddanddand.t3code

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.BuildNumber
import com.intellij.openapi.util.registry.Registry

/**
 * IntelliJ 2026.2 runs Chromium in a separate cef_server process. Its client mistakes a routine idle-thread
 * expiry in its handler pool for a lost connection and shuts the whole browser stack down about two minutes
 * after the pane opens (JetBrains JBR-10027, unresolved). [T3CodeJcefKeepAlive] fixes that in place; in-process
 * JCEF is only the fallback for when that workaround cannot reach JCEF's internals. In-process works, but only on
 * builds before 2026.2.4 (262.11879): from that build on, in-process Chromium hits a native CHECK while it
 * loads and takes the whole IDE down, so there the plugin steers users back to out-of-process mode.
 */
object T3CodeJcefMode {
    private const val REGISTRY_KEY = "ide.browser.jcef.out-of-process.enabled"
    private const val SYSTEM_PROPERTY = "jcef.remote.enabled"
    const val ISSUE_URL = "https://youtrack.jetbrains.com/issue/JBR-10027"

    /** First build where loading Chromium in-process crashes the IDE (SIGTRAP in Chromium Embedded Framework). */
    private val IN_PROCESS_CRASH_BUILD = BuildNumber.fromString("262.11879")!!

    private val build: BuildNumber get() = ApplicationInfo.getInstance().build

    /** The `-Djcef.remote.enabled` VM option wins over the registry, so a registry switch cannot change it. */
    val isForcedByVmOption: Boolean get() = System.getProperty(SYSTEM_PROPERTY) != null

    val isOutOfProcess: Boolean
        get() = System.getProperty(SYSTEM_PROPERTY)?.let { it.trim().equals("true", ignoreCase = true) }
            ?: Registry.`is`(REGISTRY_KEY, true)

    /** Builds 262.* (2026.2) are the ones confirmed to drop the connection; later builds may carry a fix. */
    val isAffectedBuild: Boolean
        get() = build.baselineVersion == 262

    /** In-process JCEF is only known to work on 2026.2 builds older than [IN_PROCESS_CRASH_BUILD]. */
    val isInProcessSafe: Boolean
        get() = build < IN_PROCESS_CRASH_BUILD

    /** Starting a browser now would load Chromium in-process on a build where that crashes the IDE. */
    val isInProcessOnCrashingBuild: Boolean
        get() = !isOutOfProcess && !isInProcessSafe

    val shouldOfferSwitch: Boolean
        get() = isOutOfProcess && isAffectedBuild && isInProcessSafe && !isForcedByVmOption &&
            !T3CodeJcefKeepAlive.isActive && !T3CodeSettings.instance.ignoreJcefModeAdvice

    fun switchToInProcessAndRestart() {
        Registry.get(REGISTRY_KEY).setValue(false)
        ApplicationManager.getApplication().restart()
    }

    fun switchToOutOfProcessAndRestart() {
        Registry.get(REGISTRY_KEY).resetToDefault()
        ApplicationManager.getApplication().restart()
    }

    val crashingBuildExplanation: String
        get() = "JCEF is set to run in-process, and on this IDE build (${build.asStringWithoutProductCode()}) in-process " +
            "Chromium crashes the whole IDE as it starts. The T3 Code pane will not start the browser in this mode. " +
            if (isForcedByVmOption) "Remove <code>-D$SYSTEM_PROPERTY=false</code> from the IDE's VM options and restart."
            else "Switching back restores the registry key <code>$REGISTRY_KEY</code> to its default and restarts the IDE."
}
