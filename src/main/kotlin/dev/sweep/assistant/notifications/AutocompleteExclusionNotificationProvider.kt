package dev.sweep.assistant.notifications

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsConfigurable
import dev.sweep.assistant.utils.matchesExclusionPattern
import java.io.File
import java.util.function.Function
import javax.swing.JComponent

class AutocompleteExclusionNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?> =
        Function { fileEditor ->
            if (shouldShowBanner(project, file)) {
                createNotificationPanel(project, file)
            } else {
                null
            }
        }

    private fun shouldShowBanner(
        project: Project,
        file: VirtualFile,
    ): Boolean {
        val settings = SweepSettings.getInstance()

        // Don't show if user has dismissed the banner
        if (settings.hideAutocompleteExclusionBanner) {
            return false
        }

        // Check if this file matches any exclusion pattern
        val exclusionPatterns = settings.allAutocompleteExclusionPatterns()
        if (exclusionPatterns.isEmpty()) {
            return false
        }

        val fileName = File(file.path).name
        return exclusionPatterns.any { pattern ->
            matchesExclusionPattern(fileName, pattern)
        }
    }

    private fun createNotificationPanel(
        project: Project,
        file: VirtualFile,
    ): EditorNotificationPanel {
        val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)

        panel.text = "Sweep autocomplete is disabled for this file type."

        panel.createActionLabel("Don't Show Again") {
            SweepSettings.getInstance().hideAutocompleteExclusionBanner = true
            // Refresh notifications to hide this banner
            EditorNotifications.getInstance(project).updateAllNotifications()
        }

        panel.createActionLabel("Configure Excluded Files") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SweepSettingsConfigurable::class.java)
        }

        return panel
    }
}
