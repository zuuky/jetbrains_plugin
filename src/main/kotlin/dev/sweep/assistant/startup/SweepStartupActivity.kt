package dev.sweep.assistant.startup

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.LogLevel
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.util.concurrency.AppExecutorUtil
import dev.sweep.assistant.actions.AddToContextFromProjectAction
import dev.sweep.assistant.actions.ReviewPRAction
import dev.sweep.assistant.actions.SweepCommitMessageAction
import dev.sweep.assistant.actions.SweepProblemsAction
import dev.sweep.assistant.autocomplete.edit.AcceptEditCompletionAction
import dev.sweep.assistant.autocomplete.edit.RecentEditsTracker
import dev.sweep.assistant.autocomplete.edit.RejectEditCompletionAction
import dev.sweep.assistant.autocomplete.vim.VimMotionGhostTextService
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.controllers.EditorSelectionManager
import dev.sweep.assistant.controllers.SweepGhostText
import dev.sweep.assistant.controllers.TerminalManagerService
import dev.sweep.assistant.data.ProjectFilesCache
import dev.sweep.assistant.data.RecentlyUsedFiles
import dev.sweep.assistant.entities.EntitiesCache
import dev.sweep.assistant.services.*
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.settings.SweepSettingsParser
import dev.sweep.assistant.statusbar.FrontendStatusBarWidgetFactory
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService
import dev.sweep.assistant.utils.*
import java.awt.event.KeyEvent
import java.util.concurrent.TimeUnit
import javax.swing.KeyStroke

class SweepStartupActivity :
    ProjectActivity,
    DumbAware {
    companion object {
        private val logger = Logger.getInstance(SweepStartupActivity::class.java)
        private var stepCounter = 0
    }

    private fun logStep(step: String, detail: String = "") {
        val thread = Thread.currentThread()
        val isEdt = ApplicationManager.getApplication().isDispatchThread
        stepCounter++
        val msg = "[SweepStartup step=$stepCounter] $step | thread=${thread.name} isEdt=$isEdt | $detail"
        logger.info(msg)
        println(msg)
        System.out.flush()
        System.err.flush()
    }

    override suspend fun execute(project: Project) {
        // CRITICAL FIX: ProjectActivity.execute() runs on EDT under flushNow's write-intent lock.
        // Any getInstance() call here (ComponentManagerImpl) causes deadlock because it needs
        // the same write lock. We MUST move ALL blocking startup work off EDT.
        // Schedule everything to a pooled thread with 1s delay so flushNow completes first.
        logStep("SCHEDULE_startup_offload")
        ApplicationManager.getApplication().executeOnPooledThread {
            // Wait for IDE startup to fully complete
            // Larger projects or slower machines may need more time
            Thread.sleep(3000) // Increased from 1000ms to handle slower startup
            kotlinx.coroutines.runBlocking {
                runStartupSequence(project)
            }
        }
        logStep("SCHEDULE_startup_offload_DONE")
    }

    private suspend fun runStartupSequence(project: Project) {
        logStep("START", "project=${project.name}")

        // Handle Full Line completion conflicts with similar logic to plugin conflicts
        // Delay by 5 seconds to ensure IDE subsystems (like EditorColorsManager) are fully initialized
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                logStep("SCHEDULED_5S_task_START")
                if (!project.isDisposed) {
                    // CRITICAL: SweepSettings.getInstance() must NOT be called on EDT during flushNow.
                    // Read settings off EDT first, then dispatch UI work.
                    val disableConflicts = try {
                        SweepSettings.getInstance().disableConflictingPlugins
                    } catch (e: Exception) {
                        logStep("SCHEDULED_5S_settings_ERROR: ${e.message}")
                        false
                    }
                    if (!project.isDisposed) {
                        ApplicationManager.getApplication().invokeLater {
                            logStep("SCHEDULED_5S_invokeLater_START")
                            if (!project.isDisposed) {
                                handleFullLineCompletionConflicts(project, disableConflicts)
                            }
                            logStep("SCHEDULED_5S_invokeLater_END")
                        }
                    }
                }
                logStep("SCHEDULED_5S_task_END")
            },
            5,
            TimeUnit.SECONDS,
        )
        logStep("SCHEDULED_delayed_5s_task")

        // Install VimMotionGhostTextHandler to handle VIM motion with ghost text
        logStep("VimMotionGhostTextService.getInstance_START")
        VimMotionGhostTextService.getInstance()
        logStep("VimMotionGhostTextService.getInstance_END")

        // Register AddToContextFromProjectAction dynamically
        ApplicationManager.getApplication().invokeLater {
            logStep("invokeLater_actionReg_START")
            val actionManager = ActionManager.getInstance()

            // Register AddToContextFromProjectAction (only once per IDE)
            val actionId = "dev.sweep.assistant.actions.AddToContextFromProjectAction"
            val addToContextAction =
                if (actionManager.getAction(actionId) == null) {
                    val action = AddToContextFromProjectAction()
                    actionManager.unregisterAction(actionId)
                    actionManager.registerAction(actionId, action)

                    // Add to ProjectViewPopupMenu
                    actionManager.getAction("ProjectViewPopupMenu")?.let { group ->
                        if (group is DefaultActionGroup) {
                            // Only add if not already present in the group
                            if (!group.containsAction(action)) {
                                group.addAction(
                                    action,
                                    Constraints(Anchor.BEFORE, "CutCopyPasteGroup"),
                                )
                            }
                        }
                    }
                    action
                } else {
                    actionManager.getAction(actionId) as AddToContextFromProjectAction
                }
            SweepActionManager.getInstance(project).addToContextAction = addToContextAction

            // Register SweepCommitMessageAction (only once per IDE)
            val commitMessageActionId = "dev.sweep.assistant.actions.SweepCommitMessageAction"
            val commitMessageAction =
                if (actionManager.getAction(commitMessageActionId) == null) {
                    val action = SweepCommitMessageAction()
                    actionManager.unregisterAction(commitMessageActionId)
                    actionManager.registerAction(commitMessageActionId, action)

                    // Add to Vcs.MessageActionGroup
                    actionManager.getAction("Vcs.MessageActionGroup")?.let { group ->
                        if (group is DefaultActionGroup) {
                            // Only add if not already present in the group
                            if (!group.containsAction(action)) {
                                group.addAction(
                                    action,
                                    Constraints(Anchor.LAST, null),
                                )
                            }
                        }
                    }
                    action
                } else {
                    actionManager.getAction(commitMessageActionId) as SweepCommitMessageAction
                }
            SweepActionManager.getInstance(project).commitMessageAction = commitMessageAction

            // Register SweepProblemsAction (only once per IDE)
            val problemsActionId = "dev.sweep.assistant.actions.SweepProblemsAction"
            val problemsAction =
                if (actionManager.getAction(problemsActionId) == null) {
                    val action = SweepProblemsAction()
                    actionManager.unregisterAction(problemsActionId)
                    actionManager.registerAction(problemsActionId, action)

                    // Add to ProblemsView.ToolWindow.TreePopup
                    actionManager.getAction("ProblemsView.ToolWindow.TreePopup")?.let { group ->
                        if (group is DefaultActionGroup) {
                            // Only add if not already present in the group
                            if (!group.containsAction(action)) {
                                group.addAction(
                                    action,
                                    Constraints(Anchor.FIRST, null),
                                )
                            }
                        }
                    }
                    action
                } else {
                    actionManager.getAction(problemsActionId) as SweepProblemsAction
                }
            SweepActionManager.getInstance(project).problemsAction = problemsAction

            // Register ReviewPRAction (only once per IDE)
            val reviewPRActionId = "dev.sweep.assistant.actions.ReviewPRAction"
            val reviewPRAction =
                if (actionManager.getAction(reviewPRActionId) == null) {
                    val action = ReviewPRAction()
                    actionManager.unregisterAction(reviewPRActionId)
                    actionManager.registerAction(reviewPRActionId, action)
                    action
                } else {
                    actionManager.getAction(reviewPRActionId) as ReviewPRAction
                }
            SweepActionManager.getInstance(project).reviewPRAction = reviewPRAction
            logStep("invokeLater_actionReg_END")
        }

        ApplicationManager.getApplication().invokeLater {
            logStep("invokeLater_toolWindow_START")
            val savedVisibility = SweepMetaData.getInstance().isToolWindowVisible

            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)
            if (savedVisibility) {
                toolWindow?.show()
            }
            // Listen for tool window visibility changes to persist state
            project.messageBus.connect(SweepProjectService.getInstance(project)).subscribe(
                ToolWindowManagerListener.TOPIC,
                object : ToolWindowManagerListener {
                    override fun stateChanged(
                        toolWindowManager: ToolWindowManager,
                        changeType: ToolWindowManagerListener.ToolWindowManagerEventType,
                    ) {
                        if (changeType == ToolWindowManagerListener.ToolWindowManagerEventType.HideToolWindow ||
                            changeType == ToolWindowManagerListener.ToolWindowManagerEventType.ActivateToolWindow
                        ) {
                            val sweepToolWindow = toolWindowManager.getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                            if (sweepToolWindow != null) {
                                SweepMetaData.getInstance().isToolWindowVisible = sweepToolWindow.isVisible
                            }
                        }
                    }
                },
            )
            logStep("invokeLater_toolWindow_END")
        }

        // Initialize application-level services
        logStep("RipgrepManager.getInstance_START")
        RipgrepManager.getInstance() // Initialize ripgrep manager on startup
        logStep("RipgrepManager.getInstance_END")

        // Initialize project-level services
        logStep("SweepProjectService.getInstance_START")
        SweepProjectService.getInstance(project)
        logStep("SweepProjectService.getInstance_END")

        logStep("FeatureFlagService.getInstance_START")
        FeatureFlagService.getInstance(project)
        logStep("FeatureFlagService.getInstance_END")

        logStep("ProjectFilesCache.getInstance_START")
        ProjectFilesCache.getInstance(project)
        logStep("ProjectFilesCache.getInstance_END")

        logStep("EntitiesCache.getInstance_START")
        EntitiesCache.getInstance(project)
        logStep("EntitiesCache.getInstance_END")

        logStep("TerminalManagerService.getInstance_START")
        TerminalManagerService.getInstance(project)
        logStep("TerminalManagerService.getInstance_END")

        logStep("EditorSelectionManager.getInstance_START")
        EditorSelectionManager.getInstance(project)
        logStep("EditorSelectionManager.getInstance_END")

        logStep("SweepNonProjectFilesService.getInstance_START")
        SweepNonProjectFilesService.getInstance(project)
        logStep("SweepNonProjectFilesService.getInstance_END")

        logStep("SweepCommitMessageService.getInstance_START")
        SweepCommitMessageService.getInstance(project)
        logStep("SweepCommitMessageService.getInstance_END")

        logStep("RecentEditsTracker.getInstance_START")
        RecentEditsTracker.getInstance(project)
        logStep("RecentEditsTracker.getInstance_END")

        logStep("IdeaVimIntegrationService.getInstance_START")
        IdeaVimIntegrationService.getInstance(project).configureIdeaVimIntegration()
        logStep("IdeaVimIntegrationService.getInstance_END")

        logStep("SweepMcpService.getInstance_START")
        SweepMcpService.getInstance(project)
        logStep("SweepMcpService.getInstance_END")

        logStep("SweepColorChangeService.getInstance_START")
        SweepColorChangeService.getInstance(project)
        logStep("SweepColorChangeService.getInstance_END")

        logStep("OSNotificationService.getInstance_START")
        OSNotificationService.getInstance(project)
        logStep("OSNotificationService.getInstance_END")

        // CRITICAL: Pre-initialize project-level services used by Sweep tool window factory.
        // Must run on pooled thread (not EDT) to avoid flushNow deadlock.
        // IMPORTANT: Only services WITHOUT Swing components in constructors can be safely
        // pre-initialized here. UI-creating services (ChatComponent, SweepComponent, etc.)
        // must be initialized on EDT via invokeLater in the tool window factory.
        logStep("PREINIT_toolWindow_services_START")
        SweepConstantsService.getInstance(project)
        TabManager.getInstance(project)
        RecentlyUsedFiles.getInstance(project)
        SweepGhostText.getInstance(project)
        logStep("PREINIT_toolWindow_services_END")

        logStep("All_services_initialized")

        // Auto-start local autocomplete server if enabled and not already running
        if (SweepSettings.getInstance().autocompleteLocalMode) {
            ApplicationManager.getApplication().executeOnPooledThread {
                logStep("autocomplete_pooledThread_START")
                val manager = LocalAutocompleteServerManager.getInstance()
                if (!manager.isServerHealthy()) {
                    manager.startServerInTerminal(project)
                }
                logStep("autocomplete_pooledThread_END")
            }
        }

        // Send installation telemetry event on first run
        val metaData = SweepMetaData.getInstance()
        if (!metaData.hasSeenInstallationTelemetryEvent) {
            val gatewayModeName = SweepConstants.GATEWAY_MODE.name
            TelemetryService.getInstance().sendUsageEvent(
                EventType.INSTALL_SWEEP,
                userProperties = mapOf("gatewayMode" to gatewayModeName),
            )
            metaData.hasSeenInstallationTelemetryEvent = true
        }

        if (!SweepSettings.getInstance().hasBeenSet) {
            if (SweepSettingsParser.isCloudEnvironment()) {
                // Cloud: open tool window for login/settings
                ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        ToolWindowManager.getInstance(project).getToolWindow(SweepConstants.TOOLWINDOW_NAME)?.show()
                    }
                }
            } else {
                // Non-cloud: preserve existing behavior
                SweepAuthServer.start(project)
            }
        }

        // Register appropriate status bar widget based on gateway mode
        when (SweepConstants.GATEWAY_MODE) {
            SweepConstants.GatewayMode.CLIENT -> {
                // Frontend mode: simple widget that only opens settings
                ApplicationManager.getApplication().invokeLater {
                    logStep("invokeLater_statusBar_START")
                    val statusBar = WindowManager.getInstance().getStatusBar(project)
                    statusBar?.addWidget(FrontendStatusBarWidgetFactory().createWidget(project), "before Position")

                    // Only start auth flow and open browser if user is not authenticated
                    if (!SweepSettings.getInstance().hasBeenSet) {
                        SweepAuthServer.start(project)
                        BrowserUtil.browse("https://app.sweep.dev", project)
                    }
                    logStep("invokeLater_statusBar_END")
                }
            }

            SweepConstants.GatewayMode.HOST -> {
                // Backend mode: no widget
            }

            SweepConstants.GatewayMode.NA -> {
                // Widget is registered via plugin.xml extension point
            }
        }

        // Untrack plugin state files from VCS
        untrackIdeaFile(project, "GhostTextManager_v2.xml")
        untrackIdeaFile(project, "UnifiedUserActionsTrackerManager.xml")

        // Suppress KtLint plugin errors for ktlint
        // Skip when plugins with logger wrapper incompatibilities are installed
        val loggerIncompatiblePlugins =
            listOf(
                PluginId.getId("io.jmix.studio"),
            )

        val hasLoggerIncompatiblePlugin =
            loggerIncompatiblePlugins.any { pluginId ->
                PluginManagerCore.isPluginInstalled(pluginId) &&
                        PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
            }

        if (!hasLoggerIncompatiblePlugin) {
            try {
                val ktlintLogger = Logger.getInstance("com.nbadal.ktlint.KtlintAnnotator")
                ktlintLogger.setLevel(LogLevel.OFF)
            } catch (e: Throwable) {
                // Ignore - some plugin logger wrappers don't support setLevel
                Logger
                    .getInstance(SweepStartupActivity::class.java)
                    .debug("Could not set log level for ktlint logger due to plugin compatibility issue", e)
            }
        }

        // Send health check to backend (only if a base URL is configured, to avoid network timeouts)
        // logStartupData() wraps all network calls in withContext(Dispatchers.IO) - safe to call from EDT
        if (SweepSettings.getInstance().baseUrl.isNotBlank()) {
            logStartupData()
        }

        // Migrate privacy settings from SweepConfig to SweepMetaData
        val isNewUser = metaData.chatsSent == 0 && metaData.autocompleteAcceptCount == 0
        val oldPrivacyMode = if (isNewUser) false else SweepConfig.getInstance(project).isOldPrivacyModeEnabled()
        if (!metaData.hasPrivacyModeBeenUpdatedFromProject) {
            metaData.privacyModeEnabled = oldPrivacyMode
            metaData.hasPrivacyModeBeenUpdatedFromProject = true
        }
        // Show Gateway onboarding notification if needed
        GatewayOnboardingService.getInstance().showGatewayOnboardingIfNeeded(project)
        // Check for dual plugin installation (Cloud + Enterprise)
        checkForDualPluginInstallation(project)
        // Ensure accept/reject actions are bound
        ApplicationManager.getApplication().invokeLater {
            ensureEditAutocompleteActionsAreBound()
        }
        // Auto-check autocomplete health on startup when in local/remote mode
        checkAutocompleteHealthOnStartup(project)

        logStep("ALL_STARTUP_COMPLETE")
    }

    private fun checkAutocompleteHealthOnStartup(project: Project) {
        val settings = SweepSettings.getInstance()
        if (settings.autocompleteLocalMode && settings.autocompleteRemoteUrl.isNotBlank()) {
            val logger = Logger.getInstance(SweepStartupActivity::class.java)
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val manager = LocalAutocompleteServerManager.getInstance()
                    val healthy = manager.isServerHealthy()
                    if (healthy) {
                        logger.info("Autocomplete remote server is healthy: ${manager.getServerUrl()}")
                    } else {
                        logger.warn("Autocomplete remote server is not reachable: ${manager.getServerUrl()}")
                        showNotification(
                            project = project,
                            title = "Autocomplete Server Unreachable",
                            body = "Cannot connect to autocomplete server at ${manager.getServerUrl()}. Check your network and server status.",
                            notificationGroup = "Sweep Autocomplete",
                            notificationType = NotificationType.WARNING,
                        )
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to check autocomplete health on startup: ${e.message}")
                }
            }
        }
    }

    private fun checkForDualPluginInstallation(project: Project) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val cloudPluginId = PluginId.getId("dev.sweep.assistant.cloud")
            val enterprisePluginId = PluginId.getId("dev.sweep.assistant")

            val isCloudInstalled =
                PluginManagerCore.isPluginInstalled(cloudPluginId) &&
                        PluginManagerCore.getPlugin(cloudPluginId)?.isEnabled == true
            val isEnterpriseInstalled =
                PluginManagerCore.isPluginInstalled(enterprisePluginId) &&
                        PluginManagerCore.getPlugin(enterprisePluginId)?.isEnabled == true

            if (isCloudInstalled && isEnterpriseInstalled) {
                showDualInstallationWarning(project)
            }
        }
    }

    private fun showDualInstallationWarning(project: Project) {
        val notification =
            NotificationGroupManager
                .getInstance()
                .getNotificationGroup("Sweep AI Notifications")
                .createNotification(
                    title = "Multiple Sweep Versions Detected",
                    content =
                        """
                        Both Cloud and Enterprise versions of Sweep are installed.
                        Please uninstall one of them to avoid conflicts.
                        If you are unsure which version to use, please contact the Sweep team at team@sweep.dev.
                        """.trimIndent(),
                    type = NotificationType.WARNING,
                )

        notification.addAction(
            object : NotificationAction("Open Plugin Settings") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    com.intellij.ide.actions.ShowSettingsUtilImpl.showSettingsDialog(
                        project,
                        "preferences.pluginManager",
                        "Sweep",
                    )
                }
            },
        )

        notification.addAction(
            object : NotificationAction("Dismiss") {
                override fun actionPerformed(
                    e: AnActionEvent,
                    notification: com.intellij.notification.Notification,
                ) {
                    notification.expire()
                }
            },
        )

        notification.notify(project)
    }

    private fun handleFullLineCompletionConflicts(project: Project, disableConflicts: Boolean) {
        if (disableConflicts) {
            disableFullLineCompletion(project)
        } else {
            // Even when auto-disable is off, check for conflicts and notify user
            ApplicationManager.getApplication().invokeLater {
                checkAndNotifyConflictingPlugins(project, autoDisable = false)
            }
        }
    }

    private fun checkAndNotifyConflictingPlugins(
        project: Project,
        autoDisable: Boolean = false,
    ) {
        val metaData = SweepMetaData.getInstance()

        // Get only installed AND enabled conflicting plugins
        val conflictingPlugins = getConflictingPlugins()

        if (conflictingPlugins.isNotEmpty()) {
            // Double-check that plugins are actually enabled before proceeding
            val enabledConflictingPlugins =
                conflictingPlugins.filter { pluginId ->
                    PluginManagerCore.getPlugin(pluginId)?.isEnabled == true
                }

            if (enabledConflictingPlugins.isEmpty()) {
                return
            }

            // Check if user has dismissed notifications
            if (metaData.dontShowConflictNotifications) {
                return
            }

            val pluginNames =
                enabledConflictingPlugins
                    .map { pluginId ->
                        SweepConstants.PLUGIN_ID_TO_NAME[pluginId] ?: PluginManagerCore.getPlugin(pluginId)?.name
                        ?: pluginId.idString
                    }.joinToString(separator = ", ")

            if (autoDisable) {
                // Auto-disable mode: disable Full Line completion instead of plugins
                disableFullLineCompletion(project)
            } else {
                // Manual mode: show notification with option to disable
                showNotification(
                    project = project,
                    title = "Conflicting Autocomplete Plugins Detected",
                    body =
                        "Sweep detected the following potentially conflicting plugins: $pluginNames. " +
                                "These plugins may interfere with Sweep's autocomplete functionality. " +
                                "You can manage these plugins in Settings > Plugins.",
                    notificationGroup = "Sweep Plugin Conflicts",
                    notificationType = NotificationType.WARNING,
                    action =
                        object : NotificationAction("Disable autocomplete for these plugins") {
                            override fun actionPerformed(
                                e: AnActionEvent,
                                notification: com.intellij.notification.Notification,
                            ) {
                                notification.expire()
                                metaData.dontShowConflictNotifications = true
                                disableFullLineCompletionAndNotify(project)
                            }
                        },
                    action2 =
                        object : NotificationAction("Don't show again") {
                            override fun actionPerformed(
                                e: AnActionEvent,
                                notification: com.intellij.notification.Notification,
                            ) {
                                notification.expire()
                                metaData.dontShowConflictNotifications = true
                            }
                        },
                )
            }
        }
    }

    private fun getConflictingPlugins() =
        SweepConstants.PLUGINS_TO_DISABLE
            .filter { PluginManagerCore.isPluginInstalled(it) && PluginManagerCore.getPlugin(it)?.isEnabled == true }

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
