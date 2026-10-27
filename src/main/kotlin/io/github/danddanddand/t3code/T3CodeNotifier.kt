package io.github.danddanddand.t3code

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object T3CodeNotifier {
    private const val GROUP_ID = "T3 Code"

    fun error(project: Project?, title: String, content: String, action: Pair<String, () -> Unit>? = null) =
        notify(project, title, content, NotificationType.ERROR, action)

    fun warn(project: Project?, title: String, content: String, action: Pair<String, () -> Unit>? = null) =
        notify(project, title, content, NotificationType.WARNING, action)

    fun info(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.INFORMATION, null)

    private fun notify(project: Project?, title: String, content: String, type: NotificationType, action: Pair<String, () -> Unit>?) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
        if (action != null) {
            notification.addAction(NotificationAction.createSimpleExpiring(action.first) { action.second() })
        }
        notification.notify(project)
    }
}
