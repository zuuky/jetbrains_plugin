package dev.sweep.assistant.utils

import com.intellij.ide.actions.ShowSettingsUtilImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ex.ConfigurableWrapper
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.util.Locale.getDefault

private val logger = Logger.getInstance("PluginConflictUtils")

/**
 * Disables IntelliJ's Full Line completion by unchecking all inline completion checkboxes.
 * This effectively disables autocomplete for conflicting plugins.
 *
 * @param project The current project
 */
/**
 * Disables Full Line completion using a non-modal background task.
 * CRITICAL FIX: runWithModalProgressBlocking blocks EDT and causes IDE freeze during startup.
 * Replaced with Task.Backgroundable which runs on pooled thread and shows non-modal progress.
 */
fun disableFullLineCompletion(project: Project) {
    object : Task.Backgroundable(project, "Disabling conflicting autocomplete...", true) {
        private var result = false

        override fun run(indicator: ProgressIndicator) {
            try {
                // getConfigurables can trigger blocking I/O (e.g., Python package manager checks)
                val allConfigurables: List<Configurable> =
                    ShowSettingsUtilImpl.getConfigurables(
                        project = project,
                        withIdeSettings = true,
                        checkNonDefaultProject = false,
                    )

                val inlineCompletionConfigurable =
                    allConfigurables.find { configurable ->
                        try {
                            val name = (configurable as? ConfigurableWrapper)?.displayNameFast
                            name?.lowercase(getDefault()) == "inline completion"
                        } catch (e: Exception) {
                            false
                        }
                    }

                if (inlineCompletionConfigurable != null) {
                    val extensionPoint = (inlineCompletionConfigurable as ConfigurableWrapper).extensionPoint
                    val configurableComp = extensionPoint.createConfigurable()
                    val configurableComponent = configurableComp?.createComponent()

                    val uncheckedCount = uncheckAllCheckboxes(configurableComponent)

                    if (uncheckedCount > 0) {
                        configurableComp?.apply()
                    }

                    configurableComp?.disposeUIResources()
                    result = uncheckedCount > 0
                }
            } catch (e: Exception) {
                logger.warn("Failed to disable Full Line completion", e)
                result = false
            }
        }

        override fun onSuccess() {
            if (result) {
                logger.info("Successfully disabled Full Line completion")
            }
        }

        override fun onThrowable(error: Throwable) {
            logger.warn("Error disabling Full Line completion", error)
        }
    }.queue()
}

/**
 * Disables Full Line completion and shows a success notification with the list of conflicting plugins.
 *
 * @param project The current project
 */
/**
 * Disables Full Line completion and shows a success notification.
 * Uses Task.Backgroundable for non-modal execution.
 */
fun disableFullLineCompletionAndNotify(project: Project) {
    // Get conflicting plugins to show in notification
    val conflictingPlugins =
        SweepConstants.PLUGINS_TO_DISABLE
            .filter { PluginManagerCore.isPluginInstalled(it) && PluginManagerCore.getPlugin(it)?.isEnabled == true }

    if (conflictingPlugins.isEmpty()) {
        return
    }

    val pluginNames =
        conflictingPlugins
            .map { pluginId ->
                SweepConstants.PLUGIN_ID_TO_NAME[pluginId] ?: PluginManagerCore.getPlugin(pluginId)?.name ?: pluginId.idString
            }.joinToString(separator = ", ")

    object : Task.Backgroundable(project, "Disabling conflicting autocomplete...", true) {
        private var success = false

        override fun run(indicator: ProgressIndicator) {
            try {
                val allConfigurables: List<Configurable> =
                    ShowSettingsUtilImpl.getConfigurables(
                        project = project,
                        withIdeSettings = true,
                        checkNonDefaultProject = false,
                    )

                val inlineCompletionConfigurable =
                    allConfigurables.find { configurable ->
                        try {
                            val name = (configurable as? ConfigurableWrapper)?.displayNameFast
                            name?.lowercase(getDefault()) == "inline completion"
                        } catch (e: Exception) {
                            false
                        }
                    }

                if (inlineCompletionConfigurable != null) {
                    val extensionPoint = (inlineCompletionConfigurable as ConfigurableWrapper).extensionPoint
                    val configurableComp = extensionPoint.createConfigurable()
                    val configurableComponent = configurableComp?.createComponent()

                    val uncheckedCount = uncheckAllCheckboxes(configurableComponent)

                    if (uncheckedCount > 0) {
                        configurableComp?.apply()
                    }

                    configurableComp?.disposeUIResources()
                    success = uncheckedCount > 0
                }
            } catch (e: Exception) {
                logger.warn("Failed to disable Full Line completion", e)
                success = false
            }
        }

        override fun onSuccess() {
            if (success) {
                showNotification(
                    project = project,
                    title = "Disabled Autocomplete For Conflicting Plugins",
                    body =
                        "Sweep has disabled autocomplete for the following conflicting plugins: $pluginNames. " +
                                "If you still see conflicting autocomplete suggestions, please disable these plugins manually in Settings > Plugins.",
                    notificationGroup = "Sweep Plugin Conflicts",
                )
            }
        }

        override fun onThrowable(error: Throwable) {
            logger.warn("Error disabling Full Line completion in AndNotify", error)
        }
    }.queue()
}

/**
 * Recursively traverses a Swing component tree and unchecks all JCheckBox components.
 * This is used to programmatically disable the "Enable local Full Line completion suggestions"
 * checkboxes by directly manipulating the UI components.
 *
 * @param component The root component to start traversing from
 * @return The number of checkboxes that were unchecked
 */
private fun uncheckAllCheckboxes(component: java.awt.Component?): Int {
    if (component == null) return 0

    var count = 0

    // If this is a JCheckBox and it's selected, uncheck it
    if (component is javax.swing.JCheckBox && component.isSelected) {
        component.isSelected = false
        count++
    }

    // If this is a container, recursively process all children
    if (component is java.awt.Container) {
        for (child in component.components) {
            count += uncheckAllCheckboxes(child)
        }
    }

    return count
}
