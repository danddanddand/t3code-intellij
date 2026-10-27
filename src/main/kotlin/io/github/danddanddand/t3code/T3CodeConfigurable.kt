package io.github.danddanddand.t3code

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class T3CodeConfigurable : BoundConfigurable("T3 Code") {
    override fun createPanel() = panel {
        val settings = T3CodeSettings.instance
        group("Server") {
            row("Server URL:") {
                textField().bindText(settings::serverUrl).columns(40)
                    .comment("The T3 Code desktop app listens on http://127.0.0.1:3773/ by default.")
            }
            row("t3 CLI:") {
                textField().bindText(settings::cliPath).columns(40)
                    .comment("Command or absolute path used for pairing, for example ~/.local/bin/t3.")
            }
            row("Pairing token TTL:") {
                textField().bindText(settings::pairTtl).columns(10)
            }
        }
        group("Embedded Browser") {
            row {
                checkBox("Do not suggest switching JCEF to in-process mode on affected IDE builds")
                    .bindSelected(settings::ignoreJcefModeAdvice)
                    .comment("Out-of-process JCEF in IntelliJ 2026.2 drops its connection after about two minutes (JetBrains JBR-10027).")
            }
        }
        group("Health Monitoring") {
            row("Ping interval (seconds):") {
                intTextField(1..600).bindIntText(settings::healthCheckIntervalSeconds)
            }
            row("Freeze timeout (seconds):") {
                intTextField(5..3600).bindIntText(settings::healthCheckTimeoutSeconds)
                    .comment("The browser is reported as frozen when the page has not answered a ping for this long.")
            }
            row {
                checkBox("Show a notification when the browser crashes or freezes").bindSelected(settings::notifyOnFailure)
            }
            row {
                checkBox("Write verbose JCEF diagnostics to the IDE log folder (takes effect after restart)")
                    .bindSelected(settings::diagnosticLogging)
                    .comment("Log file: ${T3CodeDiagnostics.jcefLogPath}")
            }
        }
    }
}
