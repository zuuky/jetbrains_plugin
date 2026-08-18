package dev.sweep.assistant.startup

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.autocomplete.vim.VimMotionGhostTextService
import dev.sweep.assistant.services.IdeaVimIntegrationService
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsConfigurable
import dev.sweep.assistant.utils.disableFullLineCompletion
import dev.sweep.assistant.utils.showNotification
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.KeyStroke

/**
 * Project startup activity for the local next-edit build.
 *
 * Initializes:
 * - IdeaVim integration (Tab mapping for autocomplete acceptance)
 * - the next-edit [RecentEditsTracker] (deferred to avoid startup EDT blocking)
 * - local autocomplete server startup (when in local mode)
 * - accept/reject shortcut bindings (Tab / Esc)
 * - conflicting autocomplete plugin handling
 */
class SweepStartupActivity :
    ProjectActivity,
    DumbAware {
    companion object {
        private val logger = Logger.getInstance(SweepStartupActivity::class.java)
        private val stepCounter = AtomicInteger(0)
    }

    private fun logStep(step: String, detail: String = "") {
        val counter = stepCounter.incrementAndGet()
        logger.info("[SweepStartup step=$counter] $step | thread=${Thread.currentThread().name} | $detail")
    }

    override suspend fun execute(project: Project) {
        // ProjectActivity may be called while the IDE is flushing startup events under
        // write-intent. Never block here; defer the work to a pooled thread.
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (project.isDisposed) {
                logStep("STARTUP_SKIPPED_project_disposed")
                return@schedule
            }
            try {
                runStartupSequence(project)
            } catch (e: Throwable) {
                logger.error("[SweepStartup] CRITICAL: Startup sequence failed", e)
            }
        }, 3, TimeUnit.SECONDS)
    }

    private fun runStartupSequence(project: Project) {
        logStep("START", "project=${project.name}")

        // Handle Full Line completion conflicts - delay by 5 seconds for IDE subsystems to settle
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            val disableConflicts = try {
                SweepSettings.getInstance().disableConflictingPlugins
            } catch (e: Throwable) {
                logStep("SCHEDULED_5S_settings_ERROR: ${e.message}")
                false
            }

            if (!project.isDisposed && disableConflicts) {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (!project.isDisposed) {
                            disableFullLineCompletion(project)
                        }
                    } catch (e: Throwable) {
                        logStep("SCHEDULED_5S_invokeLater_ERROR: ${e.message}")
                    }
                }
            }
        }, 5, TimeUnit.SECONDS)
        logStep("SCHEDULED_delayed_5s_task")

        // Install VimMotionGhostTextHandler to handle VIM motion with ghost text
        try {
            VimMotionGhostTextService.getInstance()
        } catch (e: Exception) {
            logger.warn("Failed to initialize VimMotionGhostTextService", e)
        }

        // IdeaVim Tab mapping for autocomplete acceptance
        try {
            IdeaVimIntegrationService.getInstance(project).configureIdeaVimIntegration()
        } catch (e: Exception) {
            logger.warn("Failed to configure IdeaVim integration", e)
        }

        // Defer RecentEditsTracker creation: its constructor wires editor listeners and
        // reads persisted app settings, which can block EDT during IDE startup flush.
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (project.isDisposed) return@schedule
            try {
                logStep("RecentEditsTracker.deferred_getInstance_START")
                RecentEditsTracker.getInstance(project)
                logStep("RecentEditsTracker.deferred_getInstance_END")
            } catch (e: Exception) {
                logStep("RecentEditsTracker.deferred_getInstance_ERROR: ${e.message}")
                logger.warn("Failed to initialize RecentEditsTracker", e)
            }
        }, 10, TimeUnit.SECONDS)
        logStep("RecentEditsTracker.getInstance_DEFERRED")

        // 未配置服务地址时，自动启动本地 autocomplete 服务器（若尚未运行）
        try {
            if (SweepSettings.getInstance().autocompleteRemoteUrl.isBlank()) {
                ApplicationManager.getApplication().executeOnPooledThread {
                    logStep("autocomplete_pooledThread_START")
                    try {
                        val manager = LocalAutocompleteServerManager.getInstance()
                        if (!manager.isServerHealthy()) {
                            manager.startServerInTerminal(project)
                        }
                    } catch (e: Exception) {
                        logStep("autocomplete_pooledThread_ERROR: ${e.message}")
                        logger.warn("Failed to start local autocomplete server", e)
                    }
                    logStep("autocomplete_pooledThread_END")
                }
            }
        } catch (e: Exception) {
            logStep("autocomplete_check_ERROR: ${e.message}")
            logger.warn("Failed to check autocomplete settings", e)
        }

        // Ensure accept/reject actions are bound
        ApplicationManager.getApplication().invokeLater {
            try {
                ensureEditAutocompleteActionsAreBound()
            } catch (e: Exception) {
                logStep("ensureEditActions_ERROR: ${e.message}")
                logger.warn("Failed to ensure edit autocomplete actions are bound", e)
            }
        }

        // Auto-check autocomplete health on startup when in local/remote mode
        checkAutocompleteHealthOnStartup(project)

        logStep("ALL_STARTUP_COMPLETE")
    }

    private fun checkAutocompleteHealthOnStartup(project: Project) {
        // 启动时检查 next-edit 服务（配置的地址或本地服务）是否健康
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val manager = LocalAutocompleteServerManager.getInstance()
                val healthy = manager.isServerHealthy()
                if (healthy) {
                    logger.info("Autocomplete server is healthy: ${manager.getServerUrl()}")
                } else {
                    val serverUrl = manager.getServerUrl()
                    logger.warn("Autocomplete server is not reachable: $serverUrl")
                    showNotification(
                        project = project,
                        title = "Autocomplete Server Unreachable",
                        body = "Cannot connect to autocomplete server at $serverUrl. Check your network and server status.",
                        notificationGroup = "Sweep Autocomplete",
                        notificationType = NotificationType.WARNING,
                        action =
                            object : NotificationAction("Open Settings") {
                                override fun actionPerformed(
                                    e: AnActionEvent,
                                    notification: com.intellij.notification.Notification,
                                ) {
                                    notification.expire()
                                    ShowSettingsUtil.getInstance()
                                        .showSettingsDialog(project, SweepSettingsConfigurable::class.java)
                                }
                            },
                    )
                }
            } catch (e: Exception) {
                logger.warn("Failed to check autocomplete health on startup: ${e.message}")
            }
        }
    }

    private fun ensureEditAutocompleteActionsAreBound() {
        val keymap = KeymapManager.getInstance().activeKeymap
        val acceptActionId = AcceptEditCompletionAction.ACTION_ID
        val rejectActionId = RejectEditCompletionAction.ACTION_ID

        if (keymap.getShortcuts(acceptActionId).isEmpty()) {
            keymap.addShortcut(
                acceptActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), null),
            )
        }

        if (keymap.getShortcuts(rejectActionId).isEmpty()) {
            keymap.addShortcut(
                rejectActionId,
                KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), null),
            )
        }
    }
}
