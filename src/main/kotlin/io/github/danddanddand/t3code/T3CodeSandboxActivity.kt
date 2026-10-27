package io.github.danddanddand.t3code

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Lets the sandbox harness (see sandbox/run-sandbox.sh) drive the plugin without clicks:
 * -Dt3code.sandbox.autoOpen=true opens the tool window, -Dt3code.sandbox.url overrides the server.
 */
class T3CodeSandboxActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        System.getProperty("t3code.sandbox.url")?.takeIf { it.isNotBlank() }?.let { T3CodeSettings.instance.serverUrl = it }
        if (!System.getProperty("t3code.sandbox.autoOpen").toBoolean()) return
        ToolWindowManager.getInstance(project).invokeLater {
            ToolWindowManager.getInstance(project).getToolWindow("T3 Code")?.show()
        }
    }
}
