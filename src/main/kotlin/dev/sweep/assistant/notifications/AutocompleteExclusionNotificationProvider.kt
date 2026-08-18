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
import dev.sweep.assistant.utils.shouldExcludeFromAutocomplete
import java.util.function.Function
import javax.swing.JComponent

class AutocompleteExclusionNotificationProvider : EditorNotificationProvider {
    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?> =
        Function { _ ->
            if (shouldShowBanner(project, file)) {
                createNotificationPanel(project)
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

        // 命中用户配置或项目 .gitignore 的排除模式（支持文件夹）
        return shouldExcludeFromAutocomplete(project, file.path)
    }

    private fun createNotificationPanel(project: Project): EditorNotificationPanel {
        val panel = EditorNotificationPanel(EditorNotificationPanel.Status.Info)

        panel.text = "Sweep autocomplete is disabled for this file (matched an exclusion pattern or .gitignore)."

        panel.createActionLabel("Don't show again") {
            SweepSettings.getInstance().hideAutocompleteExclusionBanner = true
            // Refresh notifications to hide this banner
            EditorNotifications.getInstance(project).updateAllNotifications()
        }

        panel.createActionLabel("Configure excluded files") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SweepSettingsConfigurable::class.java)
        }

        return panel
    }
}
