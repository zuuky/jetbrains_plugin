package dev.sweep.assistant.utils

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.Icon

private val logger = Logger.getInstance("dev.sweep.assistant.utils.Utils")

class EvictingQueue<T>(
    private val maxSize: Int,
) : ConcurrentLinkedQueue<T>() {
    override fun add(element: T): Boolean {
        val result = super.add(element)
        evict()
        return result
    }

    override fun addAll(elements: Collection<T>): Boolean {
        val result = super.addAll(elements)
        evict()
        return result
    }

    /**
     * Replaces the last element added to the queue with the given element.
     * @return true if replacement was successful, false otherwise
     */
    fun replaceLast(element: T): Boolean {
        if (isEmpty()) return false
        remove(last())
        return add(element)
    }

    private fun evict() {
        while (size > maxSize) {
            poll()
        }
    }
}

class DocumentChangeListenerAdapter(
    private val listener: DocumentListener.(event: DocumentEvent) -> Unit,
) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = listener(event)
}

class CaretPositionChangedAdapter(
    private val listener: CaretListener.(event: CaretEvent) -> Unit,
) : CaretListener {
    override fun caretPositionChanged(event: CaretEvent) = listener(event)
}

fun getCurrentSweepPluginVersion(): String? =
    PluginManagerCore.getPlugin(PluginId.getId(SweepConstants.PLUGIN_ID))?.version

fun getDebugInfo(): String =
    try {
        val application = ApplicationInfo.getInstance()
        val sweepVersion = getCurrentSweepPluginVersion() ?: "unknown"
        val osName = System.getProperty("os.name")
        "${application.fullApplicationName} (${application.build}) - OS: $osName - Sweep v$sweepVersion"
    } catch (e: Exception) {
        logger.warn("Error getting IDE info: ${e.message}")
        "Unknown IDE"
    }

fun userSpecificRepoName(project: Project): String {
    val repoName = project.basePath?.let { File(it).name } ?: "unknown"
    return repoName
}

fun showNotification(
    project: Project,
    title: String,
    body: String,
    notificationGroup: String = "Sweep Autocomplete",
    notificationType: NotificationType = NotificationType.INFORMATION,
    icon: Icon? = null,
    action: NotificationAction? = null,
    action2: NotificationAction? = null,
) {
    ApplicationManager.getApplication().invokeLater {
        val group =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup(notificationGroup)

        if (group != null) {
            val notification =
                group
                    .createNotification(title, body, notificationType)
            if (icon != null) {
                notification.icon = icon
            }
            if (action != null) {
                notification.addAction(action)
            }
            if (action2 != null) {
                notification.addAction(action2)
            }
            notification.notify(project)
        } else {
            // Fallback: Log the issue but don't crash
            logger.debug("Warning: Notification group '$notificationGroup' not available yet. Skipping notification: $title")
        }
    }
}
