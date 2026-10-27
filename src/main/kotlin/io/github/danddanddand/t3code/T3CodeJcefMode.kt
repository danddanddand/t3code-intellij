package io.github.danddanddand.t3code

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.registry.Registry

/**
 * IntelliJ 2026.2 runs Chromium in a separate cef_server process. Its client mistakes a routine idle-thread
 * expiry in its handler pool for a lost connection and shuts the whole browser stack down about two minutes
 * after the pane opens (JetBrains JBR-10027, unresolved). Running JCEF in-process sidesteps it.
 */
object T3CodeJcefMode {
    private const val REGISTRY_KEY = "ide.browser.jcef.out-of-process.enabled"
    const val ISSUE_URL = "https://youtrack.jetbrains.com/issue/JBR-10027"

    val isOutOfProcess: Boolean
        get() = System.getProperty("jcef.remote.enabled")?.let { it.trim().equals("true", ignoreCase = true) }
            ?: Registry.`is`(REGISTRY_KEY, true)

    /** Builds 262.* (2026.2) are the ones confirmed to drop the connection; later builds may carry a fix. */
    val isAffectedBuild: Boolean
        get() = ApplicationInfo.getInstance().build.baselineVersion == 262

    val shouldOfferSwitch: Boolean
        get() = isOutOfProcess && isAffectedBuild && !T3CodeSettings.instance.ignoreJcefModeAdvice

    fun switchToInProcessAndRestart() {
        Registry.get(REGISTRY_KEY).setValue(false)
        ApplicationManager.getApplication().restart()
    }
}
