package dev.sweep.assistant.startup

import com.intellij.ide.BrowserUtil
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationInfo
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
import kotlinx.coroutines.launch
import java.awt.event.KeyEvent
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.KeyStroke

class SweepStartupActivity :
    ProjectActivity,
    DumbAware {
    companion object {
        private val logger = Logger.getInstance(SweepStartupActivity::class.java)
        private val stepCounter = AtomicInteger(0)

        // Deadlock detection: if no log for 30s, dump thread info
        @Volatile
        private var lastLogTime = 0L
        private val DEADLOCK_TIMEOUT_MS = 30_000L

        @Volatile
        private var deadlockCheckerScheduled = false
    }

    private fun logStep(step: String, detail: String = "") {
        val thread = Thread.currentThread()
        val isEdt = ApplicationManager.getApplication().isDispatchThread
        val counter = stepCounter.incrementAndGet()
        lastLogTime = System.currentTimeMillis()

        // Get IDE version info
        val ideVersion = try {
            ApplicationInfo.getInstance().fullApplicationName + " " + ApplicationInfo.getInstance().fullVersion
        } catch (e: Exception) {
            "unknown"
        }

        // Get plugin version
        val pluginVersion = try {
            dev.sweep.assistant.utils.getCurrentSweepPluginVersion()
        } catch (e: Exception) {
            "unknown"
        }

        val msg =
            "[SweepStartup step=$counter] $step | thread=${thread.name} isEdt=$isEdt | ide=$ideVersion plugin=$pluginVersion | $detail"
        logger.info(msg)
        println(msg)
        System.out.flush()
        System.err.flush()

        // Check for deadlock (no log for 30s) - more frequent checking
        if (counter % 5 == 0) { // Check every 5 steps for better detection
            scheduleDeadlockChecker()
        }
    }

    private fun scheduleDeadlockChecker() {
        if (deadlockCheckerScheduled) return
        deadlockCheckerScheduled = true

        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            deadlockCheckerScheduled = false
            val elapsed = System.currentTimeMillis() - lastLogTime
            if (elapsed > DEADLOCK_TIMEOUT_MS) {
                logger.error("[SweepStartup] DEADLOCK DETECTED! Last log was ${elapsed}ms ago. Dumping threads:")
                dumpThreadInfo()
            }
        }, DEADLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private fun dumpThreadInfo() {
        val sb = StringBuilder()
        sb.appendLine("=== SWEEP PLUGIN THREAD DUMP ===")

        // Get plugin version safely
        val pluginVersion: String = try {
            dev.sweep.assistant.utils.getCurrentSweepPluginVersion() ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        sb.appendLine("Plugin Version: $pluginVersion")

        // Get IDE info safely
        val ideInfo: String = try {
            ApplicationInfo.getInstance().fullApplicationName + " " + ApplicationInfo.getInstance().fullVersion
        } catch (e: Exception) {
            "unknown"
        }
        sb.appendLine("IDE: $ideInfo")
        sb.appendLine("Time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date())}")

        val threads = Thread.getAllStackTraces().entries
        sb.appendLine("\nAll Threads (${threads.size} total):")
        for ((t, stack) in threads.sortedBy { it.key.name }) {
            val sweepRelated = t.name.contains("Sweep", ignoreCase = true) ||
                    t.name.contains("pool", ignoreCase = true) ||
                    t.name.contains("EDT", ignoreCase = true) ||
                    t.name.contains("write", ignoreCase = true) ||
                    t.name.contains("read", ignoreCase = true) ||
                    t.state == Thread.State.BLOCKED ||
                    t.state == Thread.State.WAITING ||
                    t.state == Thread.State.TIMED_WAITING
            if (sweepRelated || t.isAlive) {
                sb.appendLine("Thread: ${t.name} (id=${t.id}, state=${t.state}, daemon=${t.isDaemon})")
                for (elem in stack.take(10)) {
                    sb.appendLine("  at $elem")
                }
                sb.appendLine()
            }
        }

        // Also dump current thread info
        sb.appendLine("\nCurrent Thread Info:")
        sb.appendLine("  Name: ${Thread.currentThread().name}")
        sb.appendLine("  ID: ${Thread.currentThread().id}")
        sb.appendLine("  State: ${Thread.currentThread().state}")
        sb.appendLine("  isEDT: ${ApplicationManager.getApplication().isDispatchThread}")

        logger.error(sb.toString())
        System.err.println(sb.toString())
    }

    override suspend fun execute(project: Project) {
        // ProjectActivity may be called while the IDE is flushing startup events under
        // write-intent. Never block here and never wait for an invokeLater to finish.
        logStep("SCHEDULE_startup_background", "project=${project.name}")
        scheduleDeadlockChecker()

        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (project.isDisposed) {
                logStep("STARTUP_SKIPPED_project_disposed")
                return@schedule
            }

            logStep("BACKGROUND_startup_sequence_BEGIN", "project=${project.name}")
            try {
                runStartupSequence(project)
            } catch (e: Throwable) {
                logger.error("[SweepStartup] CRITICAL: Startup sequence failed", e)
            }
            logStep("BACKGROUND_startup_sequence_END", "project=${project.name}")
        }, 3, TimeUnit.SECONDS)

        logStep("SCHEDULE_startup_background_DONE", "project=${project.name}")
    }

    private fun runStartupSequence(project: Project) {
        lastLogTime = System.currentTimeMillis()
        logStep("START", "project=${project.name}")

        // Handle Full Line completion conflicts - delay by 5 seconds for IDE subsystems to settle
        // CRITICAL: DO NOT call invokeLater from this pooled task if runStartupSequence is still on EDT
        // Read settings directly on pooled thread, use invokeLater ONLY for handleFullLineCompletionConflicts
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            logStep("SCHEDULED_5S_task_START")
            // Read settings on pooled thread - SweepSettings is app-level, already initialized
            val disableConflicts = try {
                SweepSettings.getInstance().disableConflictingPlugins
            } catch (e: Throwable) {
                logStep("SCHEDULED_5S_settings_ERROR: ${e.message}")
                false
            }
            logStep("SCHEDULED_5S_settings_READY", "disableConflicts=$disableConflicts")

            if (!project.isDisposed) {
                // Schedule UI work on EDT - only this specific UI operation
                ApplicationManager.getApplication().invokeLater {
                    logStep("SCHEDULED_5S_invokeLater_START")
                    try {
                        if (!project.isDisposed) {
                            handleFullLineCompletionConflicts(project, disableConflicts)
                        }
                    } catch (e: Throwable) {
                        logStep("SCHEDULED_5S_invokeLater_ERROR: ${e.message}")
                    }
                    logStep("SCHEDULED_5S_invokeLater_END")
                }
            }
            logStep("SCHEDULED_5S_task_END")
        }, 5, TimeUnit.SECONDS)
        logStep("SCHEDULED_delayed_5s_task")

        // Install VimMotionGhostTextHandler to handle VIM motion with ghost text
        logStep("VimMotionGhostTextService.getInstance_START")
        try {
            VimMotionGhostTextService.getInstance()
            logStep("VimMotionGhostTextService.getInstance_END")
        } catch (e: Exception) {
            logStep("VimMotionGhostTextService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize VimMotionGhostTextService", e)
        }

        // Register actions dynamically (must be on EDT via invokeLater)
        ApplicationManager.getApplication().invokeLater {
            logStep("invokeLater_actionReg_START")
            try {
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

                // Commit message feature removed for local-only build

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
            } catch (e: Exception) {
                logStep("invokeLater_actionReg_ERROR: ${e.message}")
                logger.warn("Failed to register actions", e)
            }
        }

        // Initialize application-level services
        logStep("RipgrepManager.getInstance_START")
        try {
            RipgrepManager.getInstance() // Initialize ripgrep manager on startup
            logStep("RipgrepManager.getInstance_END")
        } catch (e: Exception) {
            logStep("RipgrepManager.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize RipgrepManager", e)
        }

        // Initialize project-level services
        logStep("SweepProjectService.getInstance_START")
        try {
            SweepProjectService.getInstance(project)
            logStep("SweepProjectService.getInstance_END")
        } catch (e: Exception) {
            logStep("SweepProjectService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize SweepProjectService", e)
        }

        logStep("FeatureFlagService.getInstance_START")
        try {
            FeatureFlagService.getInstance(project)
            logStep("FeatureFlagService.getInstance_END")
        } catch (e: Exception) {
            logStep("FeatureFlagService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize FeatureFlagService", e)
        }

        logStep("ProjectFilesCache.getInstance_START")
        try {
            ProjectFilesCache.getInstance(project)
            logStep("ProjectFilesCache.getInstance_END")
        } catch (e: Exception) {
            logStep("ProjectFilesCache.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize ProjectFilesCache", e)
        }

        logStep("EntitiesCache.getInstance_START")
        try {
            EntitiesCache.getInstance(project)
            logStep("EntitiesCache.getInstance_END")
        } catch (e: Exception) {
            logStep("EntitiesCache.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize EntitiesCache", e)
        }

        logStep("TerminalManagerService.getInstance_START")
        try {
            TerminalManagerService.getInstance(project)
            logStep("TerminalManagerService.getInstance_END")
        } catch (e: Exception) {
            logStep("TerminalManagerService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize TerminalManagerService", e)
        }

        logStep("EditorSelectionManager.getInstance_START")
        try {
            EditorSelectionManager.getInstance(project)
            logStep("EditorSelectionManager.getInstance_END")
        } catch (e: Exception) {
            logStep("EditorSelectionManager.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize EditorSelectionManager", e)
        }

        logStep("SweepNonProjectFilesService.getInstance_START")
        try {
            SweepNonProjectFilesService.getInstance(project)
            logStep("SweepNonProjectFilesService.getInstance_END")
        } catch (e: Exception) {
            logStep("SweepNonProjectFilesService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize SweepNonProjectFilesService", e)
        }

        // SweepCommitMessageService removed in local build

        logStep("RecentEditsTracker.getInstance_START")
        try {
            // Do not force this service during project startup. Its constructor wires
            // editor listeners and reads persisted app settings; doing that while IDE
            // startup is flushing can block EDT in service initialization.
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
        } catch (e: Exception) {
            logStep("RecentEditsTracker.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize RecentEditsTracker", e)
        }

        logStep("IdeaVimIntegrationService.getInstance_START")
        try {
            IdeaVimIntegrationService.getInstance(project).configureIdeaVimIntegration()
            logStep("IdeaVimIntegrationService.getInstance_END")
        } catch (e: Exception) {
            logStep("IdeaVimIntegrationService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize IdeaVimIntegrationService", e)
        }

        logStep("SweepMcpService.getInstance_START")
        try {
            SweepMcpService.getInstance(project)
            logStep("SweepMcpService.getInstance_END")
        } catch (e: Exception) {
            logStep("SweepMcpService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize SweepMcpService", e)
        }

        logStep("SweepColorChangeService.getInstance_START")
        try {
            SweepColorChangeService.getInstance(project)
            logStep("SweepColorChangeService.getInstance_END")
        } catch (e: Exception) {
            logStep("SweepColorChangeService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize SweepColorChangeService", e)
        }

        logStep("OSNotificationService.getInstance_START")
        try {
            OSNotificationService.getInstance(project)
            logStep("OSNotificationService.getInstance_END")
        } catch (e: Exception) {
            logStep("OSNotificationService.getInstance_ERROR: ${e.message}")
            logger.warn("Failed to initialize OSNotificationService", e)
        }

        // CRITICAL: Pre-initialize project-level services used by Sweep tool window factory.
        // Must run on pooled thread (not EDT) to avoid flushNow deadlock.
        // IMPORTANT: Only services WITHOUT Swing components in constructors can be safely
        // pre-initialized here. UI-creating services (ChatComponent, SweepComponent, etc.)
        // must be initialized on EDT via invokeLater in the tool window factory.
        logStep("PREINIT_toolWindow_services_START")
        try {
            SweepConstantsService.getInstance(project)
            logStep("PREINIT_SweepConstantsService_DONE")
            TabManager.getInstance(project)
            logStep("PREINIT_TabManager_DONE")
            RecentlyUsedFiles.getInstance(project)
            logStep("PREINIT_RecentlyUsedFiles_DONE")
            SweepGhostText.getInstance(project)
            logStep("PREINIT_SweepGhostText_DONE")
        } catch (e: Exception) {
            logStep("PREINIT_toolWindow_services_ERROR: ${e.message}")
            logger.warn("Failed to pre-initialize tool window services", e)
        }
        logStep("PREINIT_toolWindow_services_END")

        logStep("All_services_initialized")

        // CRITICAL FIX: Show tool window AFTER all services are pre-initialized.
        // Previously this invokeLater was queued BEFORE service init, creating a race condition
        // where EDT could show the tool window before services were ready, causing getInstance()
        // on EDT during flushNow write-lock → deadlock.
        ApplicationManager.getApplication().invokeLater {
            logStep("invokeLater_toolWindow_START")
            try {
                if (project.isDisposed) {
                    logStep("invokeLater_toolWindow_SKIPPED_project_disposed")
                    return@invokeLater
                }
                val savedVisibility = SweepMetaData.getInstance().isToolWindowVisible

                val toolWindowManager = ToolWindowManager.getInstance(project)
                val toolWindow = toolWindowManager.getToolWindow(SweepConstants.TOOLWINDOW_NAME)

                if (toolWindow == null) {
                    // CRITICAL: Tool window not registered yet - this is the root cause of many freeze issues
                    // This can happen when plugin is installed but IDE hasn't fully registered the tool window
                    logStep("invokeLater_toolWindow_NOT_REGISTERED", "scheduling retry")
                    logger.warn("[SweepStartup] Tool window not registered yet, scheduling retry")

                    // Schedule retry on pooled thread to avoid EDT blocking
                    ApplicationManager.getApplication().executeOnPooledThread {
                        Thread.sleep(2000) // Wait 2 seconds for tool window registration
                        if (!project.isDisposed) {
                            ApplicationManager.getApplication().invokeLater {
                                if (project.isDisposed) return@invokeLater
                                try {
                                    val tw = ToolWindowManager.getInstance(project)
                                        .getToolWindow(SweepConstants.TOOLWINDOW_NAME)
                                    if (tw != null && savedVisibility) {
                                        tw.show()
                                        logStep("invokeLater_toolWindow_RETRY_SUCCESS")
                                    } else {
                                        logStep("invokeLater_toolWindow_RETRY_FAILED", "toolWindow=${tw != null}")
                                    }
                                } catch (retryError: Exception) {
                                    logStep("invokeLater_toolWindow_RETRY_ERROR: ${retryError.message}")
                                    logger.warn("[SweepStartup] Failed to show tool window on retry", retryError)
                                }
                            }
                        }
                    }
                    return@invokeLater
                }

                if (savedVisibility) {
                    toolWindow.show()
                    logStep("invokeLater_toolWindow_SHOWN")
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
            } catch (e: Exception) {
                logStep("invokeLater_toolWindow_ERROR: ${e.message}")
                logger.warn("Failed to initialize tool window visibility", e)
            }
            logStep("invokeLater_toolWindow_END")
        }

        // Auto-start local autocomplete server if enabled and not already running
        try {
            if (SweepSettings.getInstance().autocompleteLocalMode) {
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

        // Send installation telemetry event on first run
        try {
            val metaData = SweepMetaData.getInstance()
            if (!metaData.hasSeenInstallationTelemetryEvent) {
                val gatewayModeName = SweepConstants.GATEWAY_MODE.name
                TelemetryService.getInstance().sendUsageEvent(
                    EventType.INSTALL_SWEEP,
                    userProperties = mapOf("gatewayMode" to gatewayModeName),
                )
                metaData.hasSeenInstallationTelemetryEvent = true
            }
        } catch (e: Exception) {
            logStep("telemetry_ERROR: ${e.message}")
            logger.warn("Failed to send installation telemetry", e)
        }

        try {
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
        } catch (e: Exception) {
            logStep("authServer_ERROR: ${e.message}")
            logger.warn("Failed to start auth server", e)
        }

        // Register appropriate status bar widget based on gateway mode
        try {
            when (SweepConstants.GATEWAY_MODE) {
                SweepConstants.GatewayMode.CLIENT -> {
                    // Frontend mode: simple widget that only opens settings
                    ApplicationManager.getApplication().invokeLater {
                        logStep("invokeLater_statusBar_START")
                        try {
                            val statusBar = WindowManager.getInstance().getStatusBar(project)
                            statusBar?.addWidget(
                                FrontendStatusBarWidgetFactory().createWidget(project),
                                "before Position"
                            )

                            // Only start auth flow and open browser if user is not authenticated
                            if (!SweepSettings.getInstance().hasBeenSet) {
                                SweepAuthServer.start(project)
                                BrowserUtil.browse("https://app.sweep.dev", project)
                            }
                        } catch (e: Exception) {
                            logStep("invokeLater_statusBar_ERROR: ${e.message}")
                            logger.warn("Failed to register status bar widget", e)
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
        } catch (e: Exception) {
            logStep("statusBar_ERROR: ${e.message}")
            logger.warn("Failed to register status bar widget", e)
        }

        // Untrack plugin state files from VCS
        try {
            untrackIdeaFile(project, "GhostTextManager_v2.xml")
            untrackIdeaFile(project, "UnifiedUserActionsTrackerManager.xml")
        } catch (e: Exception) {
            logStep("untrackVcs_ERROR: ${e.message}")
            logger.warn("Failed to untrack VCS files", e)
        }

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
        try {
            if (SweepSettings.getInstance().baseUrl.isNotBlank()) {
                // Launch in background coroutine - logStartupData is suspend, runStartupSequence is now non-suspend
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        logStartupData()
                    } catch (e: Throwable) {
                        logger.warn("Failed to send startup health check", e)
                    }
                }
            }
        } catch (e: Exception) {
            logStep("healthCheck_ERROR: ${e.message}")
            logger.warn("Failed to send startup health check", e)
        }

        // Migrate privacy settings from SweepConfig to SweepMetaData
        try {
            val metaData = SweepMetaData.getInstance()
            val isNewUser = metaData.chatsSent == 0 && metaData.autocompleteAcceptCount == 0
            val oldPrivacyMode = if (isNewUser) false else SweepConfig.getInstance(project).isOldPrivacyModeEnabled()
            if (!metaData.hasPrivacyModeBeenUpdatedFromProject) {
                metaData.privacyModeEnabled = oldPrivacyMode
                metaData.hasPrivacyModeBeenUpdatedFromProject = true
            }
        } catch (e: Exception) {
            logStep("privacyMigration_ERROR: ${e.message}")
            logger.warn("Failed to migrate privacy settings", e)
        }

        // Show Gateway onboarding notification if needed
        try {
            GatewayOnboardingService.getInstance().showGatewayOnboardingIfNeeded(project)
        } catch (e: Exception) {
            logStep("gatewayOnboarding_ERROR: ${e.message}")
            logger.warn("Failed to show gateway onboarding", e)
        }

        // Check for dual plugin installation (Cloud + Enterprise)
        checkForDualPluginInstallation(project)

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
        // Cloud/Enterprise dual installation check disabled for local build
        // Intentionally do not warn or react to dual installations in local mode.
        // Kept as a no-op to preserve local usage without cloud dependencies.
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
