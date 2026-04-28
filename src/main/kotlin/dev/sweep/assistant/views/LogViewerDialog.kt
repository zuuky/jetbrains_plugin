package dev.sweep.assistant.views

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.utils.withSweepFont
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.*

class LogViewerDialog(
    private val project: Project,
) : DialogWrapper(project) {
    private lateinit var logContent: String
    private lateinit var logPath: Path
    private val pluginLogPrefix = "sweep"

    init {
        title = "Sweep Plugin Logs"
        isModal = true
        loadLogs()
        init()
    }

    private fun loadLogs() {
        val logBuilder = StringBuilder()

        // Try to find IntelliJ log directory
        val ideaLogPath = System.getProperty("idea.log.path")
        if (ideaLogPath != null) {
            val logDir = File(ideaLogPath)
            if (logDir.exists() && logDir.isDirectory) {
                // Find log files related to sweep
                logDir.listFiles()?.filter { it.name.endsWith(".log") }?.sortedByDescending { it.lastModified() }
                    ?.forEach { file ->
                        try {
                            val content = Files.readString(file.toPath())
                            // Filter for sweep-related logs
                            val sweepLines = content.lines().filter { line ->
                                line.contains(pluginLogPrefix, ignoreCase = true) ||
                                        line.contains("sweep", ignoreCase = true) ||
                                        line.contains("dev.sweep", ignoreCase = true)
                            }
                            if (sweepLines.isNotEmpty()) {
                                logBuilder.appendLine("=== ${file.name} ===")
                                logBuilder.appendLine(sweepLines.joinToString("\n"))
                                logBuilder.appendLine()
                            }
                        } catch (e: Exception) {
                            Logger.getInstance(LogViewerDialog::class.java)
                                .warn("Failed to read log file: ${file.name}", e)
                        }
                    }
            }
        }

        // Also get plugin's own logger output if available
        logBuilder.appendLine("=== Recent Plugin Activity ===")
        logBuilder.appendLine("(Note: Check IDEA log files above for complete logs)")
        logBuilder.appendLine()

        // If no logs found, show a message
        if (logBuilder.isBlank()) {
            logBuilder.appendLine("No Sweep plugin logs found.")
            logBuilder.appendLine()
            logBuilder.appendLine("Logs are typically stored in:")
            logBuilder.appendLine("  - Windows: %USERPROFILE%\\.jetbrains<version>\\logs\\")
            logBuilder.appendLine("  - macOS: ~/Library/Logs/JetBrains<version>/")
            logBuilder.appendLine("  - Linux: ~/.cache/JetBrains<version>/logs/")
        }

        logContent = logBuilder.toString()
        logPath = ideaLogPath?.let { Paths.get(it) } ?: Paths.get("")
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 8))
        panel.border = JBUI.Borders.empty(12)

        // Create header with info
        val headerPanel = JPanel(BorderLayout(10, 0))
        headerPanel.isOpaque = false

        val infoLabel = com.intellij.ui.components.JBLabel(
            "Sweep Plugin Logs (read-only)"
        )
        infoLabel.withSweepFont(project, scale = 1f)
        headerPanel.add(infoLabel, BorderLayout.WEST)

        val copyButton = JButton("Copy All", AllIcons.Actions.Copy).apply {
            toolTipText = "Copy all logs to clipboard"
            addActionListener {
                val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
                val selection = StringSelection(logContent)
                clipboard.setContents(selection, selection)
                Messages.showInfoMessage(project, "Logs copied to clipboard!", "Copied")
            }
        }
        headerPanel.add(copyButton, BorderLayout.EAST)

        panel.add(headerPanel, BorderLayout.NORTH)

        // Create log content text area
        val textArea = JTextArea(logContent).apply {
            isEditable = false
            font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
            withSweepFont(project, scale = 1f)
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(4)
            background = java.awt.Color(250, 250, 250)
            foreground = java.awt.Color(50, 50, 50)
        }

        // Wrap in scroll pane
        val scrollPane = JBScrollPane(textArea).apply {
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            preferredSize = Dimension(700, 500)
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        // Footer with open log folder button
        val footerPanel = JPanel(BorderLayout())
        footerPanel.isOpaque = false
        footerPanel.border = JBUI.Borders.emptyTop(8)

        if (logPath.toString().isNotEmpty() && Files.exists(logPath)) {
            val openFolderButton = JButton("Open Log Folder", AllIcons.General.Information).apply {
                toolTipText = "Open the log directory in file explorer"
                horizontalAlignment = SwingConstants.RIGHT
                addActionListener {
                    try {
                        val desktop = java.awt.Desktop.getDesktop()
                        if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
                            desktop.open(logPath.toFile())
                        }
                    } catch (e: Exception) {
                        Logger.getInstance(LogViewerDialog::class.java).warn("Failed to open log folder", e)
                    }
                }
            }
            footerPanel.add(openFolderButton, BorderLayout.EAST)
        }

        panel.add(footerPanel, BorderLayout.SOUTH)

        return panel
    }

    companion object {
        fun show(project: Project) {
            val dialog = LogViewerDialog(project)
            dialog.show()
        }
    }
}