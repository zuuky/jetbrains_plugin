package dev.sweep.assistant.settings

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import dev.sweep.assistant.services.LocalAutocompleteServerManager
import java.awt.*
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.*

/**
 * Settings page (Settings -> Tools -> Sweep) for configuring:
 * - next-edit 自动补全（开关、防抖、徽章、排除文件）
 * - next-edit 服务器（本地端口 / 远程 GPU 服务器 / 测试连接）
 * - commit message 大模型（URL、模型 / 测试连接）
 */
class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()

    // Next-edit fields
    private var nextEditEnabledCheckBox: JCheckBox? = null
    private var acceptWordOnRightArrowCheckBox: JCheckBox? = null
    private var showAutocompleteBadgeCheckBox: JCheckBox? = null
    private var debounceSpinner: JSpinner? = null
    private var exclusionPatternsField: JTextField? = null
    private var disableConflictingPluginsCheckBox: JCheckBox? = null

    // Server fields
    private var localModeCheckBox: JCheckBox? = null
    private var localPortField: JTextField? = null
    private var remoteUrlField: JTextField? = null
    private var serverStatusLabel: JLabel? = null

    // Commit message fields
    private var commitMessageUrlField: JTextField? = null
    private var commitMessageModelField: JTextField? = null
    private var useCustomizedCommitMessagesCheckBox: JCheckBox? = null

    override fun getDisplayName(): String = "Sweep"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(20)

        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        panel.add(centerPanel, BorderLayout.CENTER)

        val title = JLabel("Sweep 设置")
        title.font = title.font.deriveFont(Font.BOLD, 18f)
        title.alignmentX = Component.LEFT_ALIGNMENT
        centerPanel.add(title)
        centerPanel.add(verticalSpace(6))

        val subtitle = JLabel("配置本地 next-edit 自动补全与生成 commit message 使用的大模型信息。")
        subtitle.font = subtitle.font.deriveFont(13f)
        subtitle.foreground = JBColor.GRAY
        subtitle.alignmentX = Component.LEFT_ALIGNMENT
        centerPanel.add(subtitle)
        centerPanel.add(verticalSpace(18))

        // ============ Next-Edit (Autocomplete) ============
        centerPanel.add(sectionTitle("Next-Edit 自动补全"))
        centerPanel.add(verticalSpace(4))

        nextEditEnabledCheckBox =
            JCheckBox("启用 next-edit 自动补全", settings.nextEditPredictionFlagOn).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { settings.nextEditPredictionFlagOn = isSelected }
            }
        centerPanel.add(nextEditEnabledCheckBox!!)

        acceptWordOnRightArrowCheckBox =
            JCheckBox("按 Alt+Right 接受下一个词", settings.acceptWordOnRightArrow).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { settings.acceptWordOnRightArrow = isSelected }
            }
        centerPanel.add(acceptWordOnRightArrowCheckBox!!)

        showAutocompleteBadgeCheckBox =
            JCheckBox("在补全提示旁显示 “Tab to accept” 徽章", settings.showAutocompleteBadge).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { settings.showAutocompleteBadge = isSelected }
            }
        centerPanel.add(showAutocompleteBadgeCheckBox!!)

        disableConflictingPluginsCheckBox =
            JCheckBox("自动禁用冲突的补全插件（Copilot / Tabnine 等）", settings.disableConflictingPlugins).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { settings.disableConflictingPlugins = isSelected }
            }
        centerPanel.add(disableConflictingPluginsCheckBox!!)

        centerPanel.add(verticalSpace(8))

        // Debounce
        val initialDebounce = settings.getEffectiveDebounceMs().toInt()
        debounceSpinner =
            JSpinner(SpinnerNumberModel(initialDebounce, 10, 1000, 10)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(180, preferredSize.height)
                addChangeListener { settings.autocompleteDebounceMs = (value as Int).toLong() }
            }
        centerPanel.add(formRow("防抖延迟（毫秒）", debounceSpinner!!, "输入停顿多久后触发补全请求"))

        // Exclusion patterns
        val effectivePatterns = settings.allAutocompleteExclusionPatterns().sorted().joinToString(", ")
        exclusionPatternsField =
            JTextField(effectivePatterns).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            applyExclusionPatterns()
                        }
                    },
                )
            }
        centerPanel.add(
            formRow(
                "排除的文件模式",
                exclusionPatternsField!!,
                "匹配这些模式的文件不会触发自动补全，多个模式用逗号分隔（如 .env, *.min.js）",
            ),
        )

        centerPanel.add(verticalSpace(18))

        // ============ Autocomplete Server ============
        centerPanel.add(sectionTitle("Next-Edit 服务器"))
        centerPanel.add(verticalSpace(4))

        localModeCheckBox =
            JCheckBox("本地模式", settings.autocompleteLocalMode).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener {
                    settings.autocompleteLocalMode = isSelected
                    updateServerFieldsEnabledState()
                }
            }
        centerPanel.add(localModeCheckBox!!)

        localPortField =
            JTextField(settings.autocompleteLocalPort.toString(), 8).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(180, preferredSize.height)
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            val port =
                                text.trim().toIntOrNull()?.coerceIn(1, 65535) ?: SweepSettings.DEFAULT_AUTOCOMPLETE_PORT
                            settings.autocompleteLocalPort = port
                            text = port.toString()
                        }
                    },
                )
            }
        centerPanel.add(formRow("本地端口", localPortField!!, "本地 uvx sweep-autocomplete 服务监听端口"))

        remoteUrlField =
            JTextField(settings.autocompleteRemoteUrl, 30).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            settings.autocompleteRemoteUrl = text.trim()
                        }
                    },
                )
            }
        centerPanel.add(
            formRow(
                "远程服务器 URL",
                remoteUrlField!!,
                "可选：远程 GPU 服务器地址（如 http://gpu-server:8006）。留空则使用本地服务。",
            ),
        )

        // Server status + test buttons row
        val serverButtonsRow = JPanel()
        serverButtonsRow.layout = BoxLayout(serverButtonsRow, BoxLayout.X_AXIS)
        serverButtonsRow.alignmentX = Component.LEFT_ALIGNMENT

        val statusLabel = JLabel("")
        statusLabel.font = statusLabel.font.deriveFont(12f)
        statusLabel.foreground = JBColor.GRAY
        serverStatusLabel = statusLabel
        serverButtonsRow.add(statusLabel)
        serverButtonsRow.add(Box.createHorizontalGlue())

        val checkServerButton = JButton("测试连接").apply {
            addActionListener { testAutocompleteServer() }
        }
        serverButtonsRow.add(checkServerButton)

        val startServerButton = JButton("在终端启动本地服务器").apply {
            addActionListener {
                LocalAutocompleteServerManager.getInstance().startServerInTerminal(project)
            }
        }
        serverButtonsRow.add(Box.createRigidArea(Dimension(8, 0)))
        serverButtonsRow.add(startServerButton)

        centerPanel.add(serverButtonsRow)
        centerPanel.add(verticalSpace(6))
        centerPanel.add(
            hintLabel("“测试连接”检查 next-edit 服务器（远程或本地）的 /health 端点是否可达。"),
        )

        centerPanel.add(verticalSpace(18))

        // ============ Commit Message LLM ============
        centerPanel.add(sectionTitle("生成 Commit Message 的大模型"))
        centerPanel.add(verticalSpace(4))

        commitMessageUrlField =
            JTextField(settings.commitMessageUrl, 30).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            settings.commitMessageUrl = text.trim()
                        }
                    },
                )
            }
        centerPanel.add(
            formRow(
                "LLM 服务地址",
                commitMessageUrlField!!,
                "OpenAI 兼容的 LLM 端点，如 http://llm-server:8000。生成 commit message 时调用该服务的 /v1/chat/completions。",
            ),
        )

        commitMessageModelField =
            JTextField(settings.commitMessageModel, 20).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            settings.commitMessageModel = text.trim()
                        }
                    },
                )
            }
        centerPanel.add(formRow("模型名称", commitMessageModelField!!, "用于生成 commit message 的模型名"))

        useCustomizedCommitMessagesCheckBox =
            JCheckBox("参考最近的提交风格（最近 10 条 commit message）", settings.useCustomizedCommitMessages).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { settings.useCustomizedCommitMessages = isSelected }
            }
        centerPanel.add(useCustomizedCommitMessagesCheckBox!!)

        val commitTestRow = JPanel()
        commitTestRow.layout = BoxLayout(commitTestRow, BoxLayout.X_AXIS)
        commitTestRow.alignmentX = Component.LEFT_ALIGNMENT
        commitTestRow.add(JLabel(""))
        commitTestRow.add(Box.createHorizontalGlue())
        val testCommitButton = JButton("测试连接").apply {
            addActionListener { testCommitMessageServer() }
        }
        commitTestRow.add(testCommitButton)
        centerPanel.add(commitTestRow)
        centerPanel.add(verticalSpace(6))
        centerPanel.add(
            hintLabel("“测试连接”检查 LLM 服务是否可达（尝试访问 /v1/models 或 /health 端点）。"),
        )

        centerPanel.add(verticalSpace(18))
        centerPanel.add(
            hintLabel(
                "提示：Tab 接受补全 / Esc 拒绝补全的快捷键可在 Settings - Keymap 中搜索 “Accept Edit Completion” / “Reject Edit Completion” 自定义。",
            ),
        )

        updateServerFieldsEnabledState()

        // Kick off a background status refresh when the page opens
        ApplicationManager.getApplication().executeOnPooledThread {
            val healthy = LocalAutocompleteServerManager.getInstance().isServerHealthy()
            val url = LocalAutocompleteServerManager.getInstance().getServerUrl()
            ApplicationManager.getApplication().invokeLater {
                if (healthy) {
                    serverStatusLabel?.text = "服务器运行中：$url"
                    serverStatusLabel?.foreground = JBColor(Color(0, 128, 0), Color(80, 200, 80))
                } else {
                    serverStatusLabel?.text = "服务器未运行：$url"
                    serverStatusLabel?.foreground = JBColor.RED
                }
            }
        }

        return panel
    }

    private fun updateServerFieldsEnabledState() {
        val localMode = localModeCheckBox?.isSelected ?: true
        localPortField?.isEnabled = localMode
        remoteUrlField?.isEnabled = localMode
    }

    private fun applyExclusionPatterns() {
        val patterns =
            exclusionPatternsField
                ?.text
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet() ?: emptySet()
        settings.updateAutocompleteExclusionPatterns(patterns)
    }

    private fun testAutocompleteServer() {
        val serverUrl = LocalAutocompleteServerManager.getInstance().getServerUrl()
        ApplicationManager.getApplication().executeOnPooledThread {
            val healthy = LocalAutocompleteServerManager.getInstance().isServerHealthy()
            ApplicationManager.getApplication().invokeLater {
                showNotification(
                    title = "Next-Edit 服务器",
                    content = if (healthy) "服务器运行正常：$serverUrl" else "无法连接服务器：$serverUrl",
                    type = if (healthy) NotificationType.INFORMATION else NotificationType.WARNING,
                    group = "Sweep Autocomplete",
                )
            }
        }
    }

    private fun testCommitMessageServer() {
        val url = settings.commitMessageUrl.trim().trimEnd('/')
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = probeCommitMessageServer(url)
            ApplicationManager.getApplication().invokeLater {
                showNotification(
                    title = "Commit Message LLM",
                    content = result,
                    type = if (result.startsWith("连接成功")) NotificationType.INFORMATION else NotificationType.WARNING,
                    group = "Sweep Commit Message",
                )
            }
        }
    }

    /**
     * Probes the commit message LLM service.
     * Tries OpenAI-compatible /v1/models first, falls back to /health then the root URL.
     */
    private fun probeCommitMessageServer(url: String): String {
        if (url.isBlank()) return "配置错误：请先填写 LLM 服务地址。"
        val endpoints = listOf("$url/v1/models", "$url/health", "$url")
        for (endpoint in endpoints) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URI(endpoint).toURL().openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                val code = connection.responseCode
                if (code in 200..299) {
                    val body = connection.inputStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
                    val modelSummary = extractModelSummary(body)
                    return if (modelSummary.isNotBlank()) {
                        "连接成功：${endpoint}\n可用模型：$modelSummary"
                    } else {
                        "连接成功：${endpoint}"
                    }
                }
            } catch (e: Exception) {
                // Try next endpoint
            } finally {
                connection?.disconnect()
            }
        }
        return "无法连接 LLM 服务：$url（请确认地址、端口与网络配置）"
    }

    private fun extractModelSummary(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val json = com.google.gson.JsonParser.parseString(body)
            val data = json.asJsonObject?.getAsJsonArray("data") ?: return ""
            data.take(8).mapNotNull { it.asJsonObject?.get("id")?.asString }.joinToString(", ")
        } catch (e: Exception) {
            ""
        }
    }

    private fun showNotification(
        title: String,
        content: String,
        type: NotificationType,
        group: String,
    ) {
        try {
            val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(group)
            notificationGroup.createNotification(title, content, type).notify(project)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(null, content, title, JOptionPane.INFORMATION_MESSAGE)
        }
    }

    private fun sectionTitle(text: String): JLabel {
        val label = JLabel(text)
        label.font = label.font.deriveFont(Font.BOLD, 14f)
        label.alignmentX = Component.LEFT_ALIGNMENT
        return label
    }

    private fun verticalSpace(height: Int): Component = Box.createRigidArea(Dimension(0, height))

    private fun formRow(
        labelText: String,
        field: JComponent,
        tooltip: String? = null,
    ): JPanel {
        val row = JPanel()
        row.layout = BoxLayout(row, BoxLayout.Y_AXIS)
        row.alignmentX = Component.LEFT_ALIGNMENT
        val label = JLabel(labelText)
        label.font = label.font.deriveFont(13f)
        if (tooltip != null) {
            label.toolTipText = tooltip
            field.toolTipText = tooltip
        }
        row.add(label)
        row.add(Box.createRigidArea(Dimension(0, 3)))
        row.add(field)
        row.add(Box.createRigidArea(Dimension(0, 6)))
        return row
    }

    private fun hintLabel(text: String): JLabel =
        JLabel("<html><font color='gray'>$text</font></html>").apply {
            font = font.deriveFont(12f)
            alignmentX = Component.LEFT_ALIGNMENT
        }

    override fun isModified(): Boolean = false

    override fun apply() {
        applyExclusionPatterns()
    }

    override fun reset() {
        localPortField?.text = settings.autocompleteLocalPort.toString()
        remoteUrlField?.text = settings.autocompleteRemoteUrl
        commitMessageUrlField?.text = settings.commitMessageUrl
        commitMessageModelField?.text = settings.commitMessageModel
        nextEditEnabledCheckBox?.isSelected = settings.nextEditPredictionFlagOn
        acceptWordOnRightArrowCheckBox?.isSelected = settings.acceptWordOnRightArrow
        showAutocompleteBadgeCheckBox?.isSelected = settings.showAutocompleteBadge
        disableConflictingPluginsCheckBox?.isSelected = settings.disableConflictingPlugins
        localModeCheckBox?.isSelected = settings.autocompleteLocalMode
        useCustomizedCommitMessagesCheckBox?.isSelected = settings.useCustomizedCommitMessages
        exclusionPatternsField?.text = settings.allAutocompleteExclusionPatterns().sorted().joinToString(", ")
        debounceSpinner?.value = settings.getEffectiveDebounceMs().toInt()
        updateServerFieldsEnabledState()
    }
}
