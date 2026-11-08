package io.github.danddanddand.t3code

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
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
                    .comment("The T3 Code desktop app listens on http://127.0.0.1:3773/ by default.", MAX_LINE_LENGTH_WORD_WRAP)
            }
            row("t3 CLI:") {
                textField().bindText(settings::cliPath).columns(40)
                    .comment("Command or absolute path used for pairing, for example ~/.local/bin/t3.", MAX_LINE_LENGTH_WORD_WRAP)
            }
            row("Pairing token TTL:") {
                textField().bindText(settings::pairTtl).columns(10)
            }
            row {
                checkBox("Pair automatically when the page asks for a pairing token")
                    .bindSelected(settings::autoPair)
                    .comment("Runs the t3 CLI's pair command whenever the server shows its pairing form. " +
                        "Pair with T3 Code in the gear menu does the same on demand.", MAX_LINE_LENGTH_WORD_WRAP)
            }
        }
        group("Embedded Browser") {
            row {
                checkBox("Send T3 Code shortcuts to the pane while it has focus")
                    .bindSelected(settings::forwardShortcuts)
                    .comment(
                        "Shortcuts both apps use, such as ${shortcut("K")} and ${shortcut("Shift+A")}, go to T3 Code instead " +
                            "of the IDE while the pane has focus. IDE shortcuts that T3 Code does not use keep working. " +
                            "Includes your own bindings from T3 Code's keybindings.json; changes apply when the pane reloads.",
                        MAX_LINE_LENGTH_WORD_WRAP,
                    )
            }
            row {
                checkBox("Do not suggest in-process JCEF as a fallback")
                    .bindSelected(settings::ignoreJcefModeAdvice)
                    .comment(
                        "The plugin keeps IntelliJ 2026.2's out-of-process browser from dropping its connection (JetBrains JBR-10027). " +
                            "Only if that fails, on builds before 2026.2.4, does it suggest in-process JCEF instead.",
                        MAX_LINE_LENGTH_WORD_WRAP,
                    )
            }
        }
        group("Health Monitoring") {
            row("Ping interval (seconds):") {
                intTextField(1..600).bindIntText(settings::healthCheckIntervalSeconds)
            }
            row("Freeze timeout (seconds):") {
                intTextField(5..3600).bindIntText(settings::healthCheckTimeoutSeconds)
            }.rowComment("The browser is reported as frozen when the page has not answered a ping for this long.", MAX_LINE_LENGTH_WORD_WRAP)
            row {
                checkBox("Show a notification when the browser crashes or freezes").bindSelected(settings::notifyOnFailure)
            }
            row {
                checkBox("Write verbose JCEF diagnostics to the IDE log folder (takes effect after restart)")
                    .bindSelected(settings::diagnosticLogging)
                    .comment("Log file: ${FileUtil.getLocationRelativeToUserHome(T3CodeDiagnostics.jcefLogPath.toString())}", MAX_LINE_LENGTH_WORD_WRAP)
            }
        }
    }

    private fun shortcut(keys: String) = if (SystemInfo.isMac) "Cmd+$keys" else "Ctrl+$keys"
}
