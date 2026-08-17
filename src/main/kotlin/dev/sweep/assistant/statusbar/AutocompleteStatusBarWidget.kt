package dev.sweep.assistant.statusbar

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import com.intellij.vcsUtil.showAbove
import dev.sweep.assistant.services.AutocompleteSnoozeService
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import dev.sweep.assistant.services.SweepProjectService
import dev.sweep.assistant.settings.SweepSettings
import kotlinx.coroutines.*
import java.awt.AlphaComposite
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseEvent
import javax.swing.Icon

/**
 * Status bar widget showing the next-edit autocomplete server status.
 * In local mode it checks the local/remote autocomplete server health directly.
 */
class AutocompleteStatusBarWidget(
    private val project: Project,
) : StatusBarWidget,
    StatusBarWidget.IconPresentation,
    Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteStatusBarWidget::class.java)
        const val ID = "SweepAutocompleteStatus"
        private const val CHECK_INTERVAL_MS = 900000L // Check every 15 minutes
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isAlive = true
    private var clickHandler: Consumer<MouseEvent>? = null
    private val snoozeService = AutocompleteSnoozeService.getInstance(project)
    private val snoozeStateListener = { updateWidget() }

    init {
        Disposer.register(SweepProjectService.getInstance(project), this)
        snoozeService.addSnoozeStateListener(snoozeStateListener)
        startHealthCheck()
    }

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: com.intellij.openapi.wm.StatusBar) {
        // Widget is installed
    }

    override fun dispose() {
        snoozeService.removeSnoozeStateListener(snoozeStateListener)
        scope.cancel()
    }

    // IconPresentation implementation
    override fun getIcon(): Icon? {
        val baseIcon = IconLoader.getIcon("/icons/sweep16x16.svg", javaClass)
        if (snoozeService.isAutocompleteSnooze() || !isAlive) {
            return object : Icon {
                override fun paintIcon(
                    c: java.awt.Component?,
                    g: Graphics?,
                    x: Int,
                    y: Int,
                ) {
                    g?.let { graphics ->
                        if (graphics is Graphics2D) {
                            val originalComposite = graphics.composite
                            graphics.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
                            baseIcon.paintIcon(c, graphics, x, y)
                            graphics.composite = originalComposite
                        } else {
                            baseIcon.paintIcon(c, graphics, x, y)
                        }
                    } ?: baseIcon.paintIcon(c, g, x, y)
                }

                override fun getIconWidth(): Int = baseIcon.iconWidth

                override fun getIconHeight(): Int = baseIcon.iconHeight
            }
        }
        return baseIcon
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = clickHandler

    override fun getTooltipText(): String {
        val settings = SweepSettings.getInstance()
        return if (settings.autocompleteLocalMode) {
            val remoteUrl = settings.autocompleteRemoteUrl
            if (remoteUrl.isNotBlank()) {
                "Sweep Autocomplete: Remote ($remoteUrl) - Click for options"
            } else {
                "Sweep Autocomplete: Local Mode - Click for options"
            }
        } else if (snoozeService.isAutocompleteSnooze()) {
            "Sweep Autocomplete: Snoozed (${snoozeService.formatRemainingTime()} remaining) - Click for options"
        } else if (isAlive) {
            "Sweep Autocomplete: Online - Click for options"
        } else {
            "Sweep Autocomplete: Offline - Click for options"
        }
    }

    private fun startHealthCheck() {
        clickHandler =
            Consumer { event ->
                showPopupMenu(event)
            }

        scope.launch {
            while (isActive) {
                isAlive = performHealthCheck()
                updateWidget()
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private fun showPopupMenu(event: MouseEvent) {
        scope.launch {
            isAlive = performHealthCheck()
        }
        updateWidget()

        val menuItems = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        val isLocalMode = SweepSettings.getInstance().autocompleteLocalMode

        val accessStatus =
            when {
                isLocalMode -> if (isAlive) "Local Mode" else "Local Mode (Offline)"
                snoozeService.isAutocompleteSnooze() -> {
                    val remaining = snoozeService.formatRemainingTime()
                    "🔄 Snoozed ($remaining remaining)"
                }
                else -> if (isAlive) "Online" else "Offline"
            }

        if (snoozeService.isAutocompleteSnooze()) {
            val remaining = snoozeService.formatRemainingTime()
            menuItems.add("Unsnooze ($remaining remaining)")
            actions.add { snoozeService.unsnooze() }
        } else {
            val snoozeOptions =
                listOf(
                    "Snooze for 5 minutes" to AutocompleteSnoozeService.SNOOZE_5_MINUTES,
                    "Snooze for 15 minutes" to AutocompleteSnoozeService.SNOOZE_15_MINUTES,
                    "Snooze for 30 minutes" to AutocompleteSnoozeService.SNOOZE_30_MINUTES,
                    "Snooze for 1 hour" to AutocompleteSnoozeService.SNOOZE_1_HOUR,
                    "Snooze for 2 hours" to AutocompleteSnoozeService.SNOOZE_2_HOURS,
                )

            snoozeOptions.forEach { (label, duration) ->
                menuItems.add(label)
                actions.add {
                    snoozeService.snoozeAutocomplete(duration)
                }
            }

            if (!isAlive) {
                menuItems.add("Retry Connection")
                actions.add { scope.launch { isAlive = performHealthCheck() } }
            }
        }

        val popupStep =
            object : BaseListPopupStep<String>("Sweep Autocomplete\n($accessStatus)", menuItems) {
                override fun onChosen(
                    selectedValue: String?,
                    finalChoice: Boolean,
                ): PopupStep<*>? {
                    if (finalChoice) {
                        val index = menuItems.indexOf(selectedValue)
                        if (index >= 0 && index < actions.size) {
                            actions[index].invoke()
                        }
                    }
                    return PopupStep.FINAL_CHOICE
                }

                override fun isSelectable(value: String?): Boolean = true
            }

        val popup = JBPopupFactory.getInstance().createListPopup(popupStep)
        popup.showAbove(event.component)
    }

    private fun updateWidget() {
        ApplicationManager.getApplication().invokeLater {
            WindowManager.getInstance().getStatusBar(project)?.updateWidget(ID)
        }
    }

    private suspend fun performHealthCheck(): Boolean =
        withContext(Dispatchers.IO) {
            try {
                LocalAutocompleteServerManager.getInstance().isServerHealthy()
            } catch (e: Exception) {
                logger.warn("Failed to check autocomplete server health: ${e.message}")
                false
            }
        }
}
