package io.github.danddanddand.t3code

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import javax.swing.Icon

class T3CodeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        if (T3CodeSettings.instance.diagnosticLogging) T3CodeDiagnostics.enableJcefLoggingIfPossible()
        val panel = T3CodeBrowserPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // The pane itself is just the T3 client; controls live in the tool window chrome like other IDE tool windows.
        toolWindow.setTitleActions(listOf(action("Reload", AllIcons.Actions.Refresh) { panel.reload() }))
        toolWindow.setAdditionalGearActions(DefaultActionGroup(
            action("Restart Browser", AllIcons.Actions.Restart) { panel.restart() },
            action("Pair with T3 Code", AllIcons.Actions.Lightning) { panel.pair() },
            action("Go to Home Page", AllIcons.Nodes.HomeFolder) { panel.goHome() },
            action("Open DevTools", AllIcons.Toolwindows.ToolWindowDebugger) { panel.openDevTools() },
            action("Settings…", AllIcons.General.Settings) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, T3CodeConfigurable::class.java)
            },
        ))
        panel.start()
    }

    private fun action(text: String, icon: Icon, run: () -> Unit): AnAction = object : AnAction(text, null, icon), DumbAware {
        override fun actionPerformed(e: AnActionEvent) = run()
    }
}
