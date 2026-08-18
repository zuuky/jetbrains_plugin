package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
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
 * - next-edit 服务地址（大模型服务地址 / 测试连接）
 * - commit message 大模型（URL、模型 / 测试连接）
 */
class SweepSettingsConfigurable(
    private val project: Project,
) : Configurable {
    private val settings = SweepSettings.getInstance()

    // 设置页面根组件，作为测试结果对话框的父窗口（保证弹窗显示在当前设置页之上）
    private lateinit var uiRoot: JPanel

    // Next-edit fields
    private var nextEditEnabledCheckBox: JCheckBox? = null
    private var acceptWordOnRightArrowCheckBox: JCheckBox? = null
    private var showAutocompleteBadgeCheckBox: JCheckBox? = null
    private var debounceSpinner: JSpinner? = null
    private var exclusionPatternsField: JTextField? = null
    private var excludeGitignoreCheckBox: JCheckBox? = null
    private var disableConflictingPluginsCheckBox: JCheckBox? = null

    // Server fields
    private var localPortField: JTextField? = null
    private var remoteUrlField: JTextField? = null
    private var serverStatusLabel: JLabel? = null

    // Commit message fields
    private var commitMessageUrlField: JTextField? = null
    private var commitMessageModelField: JTextField? = null
    private var useCustomizedCommitMessagesCheckBox: JCheckBox? = null

    override fun getDisplayName(): String = "Sweep"

    override fun createComponent(): JComponent {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(16, 20, 16, 20)
        uiRoot = panel

        // ------- 标题 -------
        panel.add(
            JLabel("Sweep 设置").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )
        panel.add(verticalSpace(4))
        panel.add(
            JLabel("配置本地 next-edit 自动补全，以及生成 commit message 使用的大模型信息。").apply {
                font = font.deriveFont(13f)
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )
        panel.add(verticalSpace(16))

        // ------- Next-Edit 自动补全 -------
        panel.add(createNextEditSection())
        panel.add(verticalSpace(12))

        // ------- Next-Edit 服务 -------
        panel.add(createServerSection())
        panel.add(verticalSpace(12))

        // ------- Commit Message LLM -------
        panel.add(createCommitMessageSection())
        panel.add(verticalSpace(16))

        // ------- 底部提示 -------
        panel.add(
            JLabel("<html><font color='gray'>提示：Tab 接受补全 / Esc 拒绝补全的快捷键可在 Settings - Keymap 中搜索“Accept Edit Completion”/“Reject Edit Completion”自定义。</font></html>").apply {
                font = font.deriveFont(12f)
                alignmentX = Component.LEFT_ALIGNMENT
            },
        )

        // 打开页面时后台刷新一次服务器状态
        ApplicationManager.getApplication().executeOnPooledThread {
            val healthy = LocalAutocompleteServerManager.getInstance().isServerHealthy()
            val url = LocalAutocompleteServerManager.getInstance().getServerUrl()
            // 指定模态状态：设置页是模态对话框，必须在该模态范围内执行，否则会被挂起到关闭设置页之后
            ApplicationManager.getApplication().invokeLater(
                { updateServerStatus(healthy, url) },
                ModalityState.stateForComponent(panel),
            )
        }

        return panel
    }

    // ===================================================================
    // 各分区
    // ===================================================================

    private fun createNextEditSection(): JPanel {
        val card = card("Next-Edit 自动补全")
        var row = 0

        nextEditEnabledCheckBox =
            JCheckBox("启用 next-edit 自动补全", settings.nextEditPredictionFlagOn).apply {
                addActionListener { settings.nextEditPredictionFlagOn = isSelected }
            }
        addCheckBoxRow(card, nextEditEnabledCheckBox!!, row++)

        acceptWordOnRightArrowCheckBox =
            JCheckBox("按 Alt + Right 接受下一个词", settings.acceptWordOnRightArrow).apply {
                addActionListener { settings.acceptWordOnRightArrow = isSelected }
            }
        addCheckBoxRow(card, acceptWordOnRightArrowCheckBox!!, row++)

        showAutocompleteBadgeCheckBox =
            JCheckBox("在补全提示旁显示 “Tab to accept” 徽章", settings.showAutocompleteBadge).apply {
                addActionListener { settings.showAutocompleteBadge = isSelected }
            }
        addCheckBoxRow(card, showAutocompleteBadgeCheckBox!!, row++)

        disableConflictingPluginsCheckBox =
            JCheckBox("自动禁用冲突的补全插件（Copilot / Tabnine 等）", settings.disableConflictingPlugins).apply {
                addActionListener { settings.disableConflictingPlugins = isSelected }
            }
        addCheckBoxRow(card, disableConflictingPluginsCheckBox!!, row++)

        addSpacerRow(card, row++)

        val initialDebounce = settings.getEffectiveDebounceMs().toInt()
        debounceSpinner =
            JSpinner(SpinnerNumberModel(initialDebounce, 10, 1000, 10)).apply {
                maximumSize = Dimension(160, preferredSize.height)
            }.also { spinner ->
                spinner.addChangeListener { settings.autocompleteDebounceMs = (spinner.value as Int).toLong() }
            }
        addFieldRow(card, "防抖延迟（毫秒）", debounceSpinner!!, row++)

        exclusionPatternsField =
            JTextField(settings.allAutocompleteExclusionPatterns().sorted().joinToString(", "), 30).apply {
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            applyExclusionPatterns()
                        }
                    },
                )
            }
        addFieldRow(card, "排除的文件模式", exclusionPatternsField!!, row++)

        excludeGitignoreCheckBox =
            JCheckBox("自动排除项目 .gitignore 中的文件与文件夹", settings.excludeGitignorePatterns).apply {
                addActionListener { settings.excludeGitignorePatterns = isSelected }
            }
        addCheckBoxRow(card, excludeGitignoreCheckBox!!, row++)
        addHintRow(
            card,
            "上方模式支持文件与文件夹（如 .env、node_modules、build/**），多个用逗号分隔。勾选后自动并入 .gitignore 中的模式。",
            row++,
        )

        return card
    }

    private fun createServerSection(): JPanel {
        val card = card("Next-Edit 服务")
        var row = 0

        remoteUrlField =
            JTextField(settings.autocompleteRemoteUrl, 40).apply {
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            settings.autocompleteRemoteUrl = text.trim()
                        }
                    },
                )
            }
        addFieldRow(card, "Next-Edit 服务地址（大模型服务地址）", remoteUrlField!!, row++)

        localPortField =
            JTextField(settings.autocompleteLocalPort.toString(), 8).apply {
                maximumSize = Dimension(120, preferredSize.height)
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
        addFieldRow(card, "本地端口（高级）", localPortField!!, row++)
        addHintRow(
            card,
            "填写服务地址后插件直接使用该地址；留空则自动启用本机服务并监听上方端口（默认 8006）。",
            row++,
        )

        // 状态 + 按钮行
        serverStatusLabel =
            JLabel("——").apply {
                foreground = JBColor.GRAY
            }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttons.add(
            JButton("测试连接").apply {
                addActionListener { testAutocompleteServer() }
            },
        )
        buttons.add(
            JButton("在终端启动本地服务器").apply {
                addActionListener {
                    LocalAutocompleteServerManager.getInstance().startServerInTerminal(project)
                }
            },
        )

        val statusContainer = JPanel(FlowLayout(FlowLayout.LEFT, 0, 3))
        statusContainer.add(serverStatusLabel!!)

        // “状态：xx | 按钮”
        val statusBar = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0))
        statusBar.add(statusContainer)
        statusBar.add(buttons)
        addFullRow(card, statusBar, row++)
        addHintRow(card, "“测试连接”检查 next-edit 服务（远程地址或本地服务）的 /health 端点是否可达。", row++)

        return card
    }

    private fun createCommitMessageSection(): JPanel {
        val card = card("生成 Commit Message 的大模型")
        var row = 0

        commitMessageUrlField =
            JTextField(settings.commitMessageUrl, 40).apply {
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            settings.commitMessageUrl = text.trim()
                        }
                    },
                )
            }
        addFieldRow(
            card,
            "LLM 服务地址",
            commitMessageUrlField!!,
            row++,
            trailing =
                JButton("测试连接").apply {
                    addActionListener { testCommitMessageServer() }
                },
        )
        addHintRow(
            card,
            "OpenAI 兼容端点，如 http://llm-server:8000。生成 commit message 时调用该服务的 /v1/chat/completions。",
            row++,
        )

        commitMessageModelField =
            JTextField(settings.commitMessageModel, 25).apply {
                addFocusListener(
                    object : java.awt.event.FocusAdapter() {
                        override fun focusLost(e: java.awt.event.FocusEvent?) {
                            settings.commitMessageModel = text.trim()
                        }
                    },
                )
            }
        addFieldRow(card, "模型名称", commitMessageModelField!!, row++)

        useCustomizedCommitMessagesCheckBox =
            JCheckBox("参考最近的提交风格（最近 10 条 commit message）", settings.useCustomizedCommitMessages).apply {
                addActionListener { settings.useCustomizedCommitMessages = isSelected }
            }
        addCheckBoxRow(card, useCustomizedCommitMessagesCheckBox!!, row++)

        addHintRow(card, "“测试连接”检查 LLM 服务是否可达（依次尝试 /v1/models、/health、根路径）。", row++)

        return card
    }

    // ===================================================================
    // GridBagLayout 行辅助（两列：标签 | 控件，控件列水平填充）
    // ===================================================================

    private fun card(title: String): JPanel =
        JPanel(GridBagLayout()).apply {
            border =
                BorderFactory.createCompoundBorder(
                    BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title),
                    JBUI.Borders.empty(10, 12, 8, 12),
                )
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun rowConstraints(row: Int): GridBagConstraints =
        GridBagConstraints().apply {
            gridy = row
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            insets = Insets(3, 0, 3, 0)
        }

    /** 单行、跨两列：CheckBox 等。 */
    private fun addCheckBoxRow(
        card: JPanel,
        checkBox: JCheckBox,
        row: Int,
    ) {
        val c = rowConstraints(row)
        c.gridx = 0
        c.gridwidth = GridBagConstraints.REMAINDER
        c.weightx = 1.0
        card.add(checkBox, c)
    }

    /** 两列行：label（固定宽度）+ field（填充），可附尾随组件（如测试按钮）。 */
    private fun addFieldRow(
        card: JPanel,
        labelText: String,
        field: JComponent,
        row: Int,
        trailing: JComponent? = null,
    ) {
        val label = JLabel(labelText)
        label.font = JBUI.Fonts.label().deriveFont(13f)

        val c = rowConstraints(row)
        c.gridx = 0
        c.weightx = 0.0
        c.insets = Insets(3, 0, 3, 10)
        card.add(label, c)

        c.gridx = 1
        c.weightx = 1.0
        c.insets = Insets(3, 0, 3, 0)
        card.add(field, c)

        if (trailing != null) {
            c.gridx = 2
            c.weightx = 0.0
            c.insets = Insets(3, 8, 3, 0)
            c.fill = GridBagConstraints.NONE
            card.add(trailing, c)
        }
    }

    /** 蓝色说明行（跨两列）。 */
    private fun addHintRow(
        card: JPanel,
        text: String,
        row: Int,
    ) {
        val hint =
            JLabel("<html><font color='gray'>$text</font></html>").apply {
                font = font.deriveFont(12f)
            }
        val c = rowConstraints(row)
        c.gridx = 0
        c.gridwidth = GridBagConstraints.REMAINDER
        c.weightx = 1.0
        card.add(hint, c)
    }

    /** 空白行。 */
    private fun addSpacerRow(
        card: JPanel,
        row: Int,
    ) {
        val c = rowConstraints(row)
        c.gridx = 0
        c.gridwidth = GridBagConstraints.REMAINDER
        c.weightx = 1.0
        card.add(Box.createVerticalStrut(4), c)
    }

    /** 跨两列放入任意组件（如按钮行）。 */
    private fun addFullRow(
        card: JPanel,
        component: JComponent,
        row: Int,
    ) {
        val c = rowConstraints(row)
        c.gridx = 0
        c.gridwidth = GridBagConstraints.REMAINDER
        c.weightx = 1.0
        card.add(component, c)
    }

    private fun verticalSpace(height: Int): Component = Box.createRigidArea(Dimension(0, height))

    // ===================================================================
    // 行为
    // ===================================================================

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

    private fun updateServerStatus(
        healthy: Boolean,
        url: String,
    ) {
        serverStatusLabel?.text = if (healthy) "服务器运行中：$url" else "服务器未运行：$url"
        serverStatusLabel?.foreground =
            if (healthy) JBColor(Color(0, 128, 0), Color(80, 200, 80)) else JBColor.RED
    }

    private fun testAutocompleteServer() {
        val manager = LocalAutocompleteServerManager.getInstance()
        val serverUrl = manager.getServerUrl()

        // 立即反馈，避免“点了没反应”
        serverStatusLabel?.text = "正在测试 $serverUrl ..."
        serverStatusLabel?.foreground = JBColor.GRAY

        ApplicationManager.getApplication().executeOnPooledThread {
            val healthy = manager.isServerHealthy()
            val message = if (healthy) "服务器运行正常：$serverUrl" else "无法连接服务器：$serverUrl"
            // 指定模态状态：保证在模态的设置页打开期间立即刷新状态并弹出结果
            ApplicationManager.getApplication().invokeLater(
                {
                    updateServerStatus(healthy, serverUrl)
                    showTestResult(message, "Next-Edit 服务器（测试连接）", isError = !healthy)
                },
                ModalityState.stateForComponent(uiRoot),
            )
        }
    }

    private fun testCommitMessageServer() {
        // 读取输入框中当前填写的地址（即使尚未失焦保存，也按输入框内容测试）
        val url = (commitMessageUrlField?.text?.trim() ?: settings.commitMessageUrl).trim().trimEnd('/')

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = probeCommitMessageServer(url)
            // 指定模态状态：保证在模态的设置页打开期间立即弹出结果
            ApplicationManager.getApplication().invokeLater(
                { showTestResult(result, "Commit Message LLM（测试连接）", isError = !result.startsWith("连接成功")) },
                ModalityState.stateForComponent(uiRoot),
            )
        }
    }

    /**
     * 以当前设置页为父窗口弹出模态测试结果对话框（保证一定显示在设置页之上）。
     */
    private fun showTestResult(
        message: String,
        title: String,
        isError: Boolean,
    ) {
        val icon = if (isError) Messages.getErrorIcon() else Messages.getInformationIcon()
        Messages.showMessageDialog(uiRoot, message, title, icon)
    }

    /**
     * Probes the commit message LLM service.
     * Tries OpenAI-compatible /v1/models first, falls back to /health then the root URL.
     */
    private fun probeCommitMessageServer(url: String): String {
        if (url.isBlank()) return "配置错误：请先填写 LLM 服务地址。"
        val endpoints = listOf("$url/v1/models", "$url/health", url)
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
            } catch (_: Exception) {
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
        } catch (_: Exception) {
            ""
        }
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
        excludeGitignoreCheckBox?.isSelected = settings.excludeGitignorePatterns
        disableConflictingPluginsCheckBox?.isSelected = settings.disableConflictingPlugins
        useCustomizedCommitMessagesCheckBox?.isSelected = settings.useCustomizedCommitMessages
        exclusionPatternsField?.text = settings.allAutocompleteExclusionPatterns().sorted().joinToString(", ")
        debounceSpinner?.value = settings.getEffectiveDebounceMs().toInt()
    }
}
