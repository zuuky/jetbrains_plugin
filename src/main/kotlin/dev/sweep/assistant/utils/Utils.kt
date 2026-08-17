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

/**
 * Custom implementation to replace deprecated IconUtil.colorize.
 * Creates a colored version of the given icon by applying a color overlay.
 */
fun colorizeIcon(
    icon: Icon,
    color: java.awt.Color,
): Icon {
    val width = icon.iconWidth
    val height = icon.iconHeight

    val image = java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g2d = image.createGraphics()

    // Draw the original icon
    icon.paintIcon(null, g2d, 0, 0)

    // Apply color overlay
    g2d.composite = java.awt.AlphaComposite.SrcAtop
    g2d.color = color
    g2d.fillRect(0, 0, width, height)

    g2d.dispose()
    return javax.swing.ImageIcon(image)
}

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

fun isIDEDarkMode(): Boolean {
    try {
        return !com.intellij.ui.JBColor.isBright()
    } catch (e: Throwable) {
        logger.warn("Error detecting IDE theme: ${e.message}")
        return true
    }
}

fun getCurrentSweepPluginVersion(): String? =
    PluginManagerCore.getPlugin(PluginId.getId(SweepConstants.PLUGIN_ID))?.version

fun getApplicationVersion(): String =
    try {
        ApplicationInfo.getInstance().fullVersion
    } catch (e: Exception) {
        logger.warn("Error getting application version: ${e.message}")
        "unknown"
    }

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

fun <T> measureTimeAndLog(
    description: String,
    block: () -> T,
): T {
    val startTime = System.currentTimeMillis()
    val result = block()
    val endTime = System.currentTimeMillis()
    val duration = endTime - startTime
    logger.debug("$description took ${duration}ms")
    return result
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
