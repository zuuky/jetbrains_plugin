package dev.sweep.assistant.services

import com.intellij.ide.DataManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.serviceContainer.AlreadyDisposedException
import com.intellij.util.messages.MessageBusConnection
import com.intellij.vcs.commit.CommitMessageUi
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CommitMessageRequest
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.Future

@Service(Service.Level.PROJECT)
class SweepCommitMessageService(
    private val project: Project,
) : Disposable {
    private var previousMessage: String? = null
    private var commitUi: CommitMessageUi? = null
    private var lastUpdateTime: Long = 0
    private var messageBusConnection: MessageBusConnection? = null
    private val runningTasks = mutableListOf<Future<*>>()
    private var _isGenerating: Boolean = false
    val isGenerating: Boolean get() = _isGenerating

    init {
        messageBusConnection = project.messageBus.connect(this) // Connect with disposable
        messageBusConnection?.subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) {
                    if (project.isDisposed) return

                    val activeId = toolWindowManager.activeToolWindowId
                    // if they interact with commit tab
                    // note that we have a 5 minute cooldown which prevents
                    // excessive commit message creation, if we change the cool down to be less
                    // we need to change this to keep track of previous activeid
                    if (activeId == "Commit" || activeId == "Version Control") {
                        try {
                            val focusOwner = IdeFocusManager.getInstance(project).focusOwner
                            if (focusOwner != null) {
                                val dataContext = DataManager.getInstance().getDataContext(focusOwner)

                                // this will not work for 2023 but wont error anything
                                val commitMessage = VcsDataKeys.COMMIT_MESSAGE_CONTROL.getData(dataContext) as? CommitMessage ?: return

                                synchronized(runningTasks) {
                                    if (!project.isDisposed) {
                                        val task =
                                            ApplicationManager.getApplication().executeOnPooledThread {
                                                try {
                                                    updateCommitMessage(commitMessage)
                                                } catch (e: ProcessCanceledException) {
                                                    // Rethrow ProcessCanceledException as required by IntelliJ
                                                    throw e
                                                } catch (e: AlreadyDisposedException) {
                                                    // Project/service is disposed, this is expected during shutdown
                                                    logger.debug("Project disposed during commit message generation")
                                                    throw e
                                                } catch (_: CancellationException) {
                                                    // Task was cancelled, which is expected during shutdown
                                                    logger.debug("Commit message generation cancelled")
                                                } catch (e: Exception) {
                                                    logger.warn("Error updating commit message", e)
                                                }
                                            }
                                        runningTasks.add(task)
                                    }
                                }
                            }
                        } catch (e: ProcessCanceledException) {
                            // Rethrow ProcessCanceledException as required by IntelliJ
                            throw e
                        } catch (e: Exception) {
                            println("failed to initialize")
                            logger.error("Error initializing commit message service", e)
                        }
                    }
                }
            },
        )
    }

    fun updateCommitMessage(
        commitMessage: CommitMessage,
        selectedChanges: List<Change> = emptyList(),
        partialChanges: List<PartialChangeInfo> = emptyList(),
        unversionedFiles: List<FilePath> = emptyList(),
        overrideCurrentMessage: Boolean = false,
        ignoreDelay: Boolean = false,
    ) {
        if (project.isDisposed) return

        if (!canUpdate() && !ignoreDelay) {
            logger.debug("Skipping commit message update: cooldown period not elapsed")
            return
        }

        // Prevent concurrent generation requests
        synchronized(this) {
            if (_isGenerating) {
                logger.debug("Skipping commit message update: generation already in progress")
                return
            }
            _isGenerating = true
        }

        try {
            lastUpdateTime = Instant.now().toEpochMilli()

            // Check disposal before potentially long operation
            if (project.isDisposed) return

            val apiResponse = generateCommitMessage(selectedChanges, partialChanges, unversionedFiles)

            if (project.isDisposed) {
                _isGenerating = false
                return
            }

            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) {
                    _isGenerating = false
                    return@invokeLater
                }

                commitMessage.let { ui ->
                    val currentMessage = ui.text
                    if (
                        currentMessage.isBlank() ||
                        currentMessage.trim() == previousMessage?.trim() ||
                        overrideCurrentMessage
                    ) {
                        previousMessage = apiResponse
                        ui.text = apiResponse
                    }
                }
                _isGenerating = false
            }
        } catch (e: ProcessCanceledException) {
            // Rethrow ProcessCanceledException as required by IntelliJ
            _isGenerating = false
            throw e
        } catch (e: AlreadyDisposedException) {
            // Project/service is disposed, this is expected during shutdown - don't log as error
            logger.debug("Project disposed during commit message generation")
            _isGenerating = false
            throw e
        } catch (_: CancellationException) {
            // Task was cancelled, which is expected during shutdown
            logger.debug("Commit message generation cancelled")
            _isGenerating = false
        } catch (e: Exception) {
            logger.warn("Error making API call", e)
            _isGenerating = false
        }
    }

    private fun canUpdate(): Boolean {
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastUpdateTime) >= UPDATE_COOLDOWN_MS
    }

    private fun generateCommitMessage(
        selectedChanges: List<Change> = emptyList(),
        partialChanges: List<PartialChangeInfo> = emptyList(),
        unversionedFiles: List<FilePath> = emptyList(),
    ): String {
        if (project.isDisposed) return ""
        val currentBranch = getCurrentBranchName(project)
        val changeListManager = ChangeListManager.getInstance(project)
        val defaultChangeList = changeListManager.defaultChangeList
        // Only fall back to default change list if no changes AND no unversioned files are explicitly selected
        val latestChanges =
            if (selectedChanges.isNotEmpty() || unversionedFiles.isNotEmpty()) {
                selectedChanges
            } else {
                defaultChangeList.changes.toList()
            }
        // Check disposal status before generating diff
        if (project.isDisposed) return ""

        val diffString =
            ProgressManager.getInstance().runProcess<String>(
                {
                    // Use combined diff generation if we have partial changes
                    val changesDiff =
                        if (partialChanges.isNotEmpty()) {
                            generateCombinedDiffString(latestChanges, partialChanges, project)
                        } else {
                            generateDiffStringFromChanges(latestChanges, project)
                        }

                    // Add unversioned files diff
                    val unversionedDiff =
                        if (unversionedFiles.isNotEmpty()) {
                            generateDiffStringFromUnversionedFiles(unversionedFiles, project)
                        } else {
                            ""
                        }

                    changesDiff + unversionedDiff
                },
                EmptyProgressIndicator(),
            )

        val previousCommitsString =
            if (!project.isDisposed && SweepConfig.getInstance(project).shouldUseCustomizedCommitMessages()) {
                "Recent Commit Messages:\n" +
                    getRecentCommitMessages(project, maxCount = 20)
                        .filterNot { it.contains("merge pull request", ignoreCase = true) }
                        .take(10)
                        .mapIndexed { index, commit -> "${index + 1}. $commit" }
                        .joinToString("\n")
            } else {
                ""
            }
        // Optional user-provided commit message template
        // Priority: Project-specific sweep-commit-template.md > Global ~/.sweep/sweep-commit-template.md
        val commitTemplate: String? =
            try {
                SweepConfig.getInstance(project).getEffectiveCommitMessageRules()?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        var commitMessage = ""
        try {
            var connection: HttpURLConnection? = null
            try {
                // Use custom commit message URL if configured, otherwise fall back to getConnection
                val commitMessageUrl = SweepSettings.getInstance().commitMessageUrl
                val commitMessageModel = SweepSettings.getInstance().commitMessageModel

                if (commitMessageUrl.isNotBlank()) {
                    // Use OpenAI-compatible chat completions API (non-streaming)
                    val url = URI("${commitMessageUrl.trimEnd('/')}/v1/chat/completions").toURL()
                    connection = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("Authorization", "Bearer ${SweepSettings.getInstance().githubToken}")
                        connectTimeout = 10000
                        readTimeout = 30000
                    }

                    // Build system prompt
                    val systemPrompt = buildString {
                        append("You are a concise commit message generator. Generate a clear, conventional commit message based on the provided diff.")
                        if (commitTemplate != null) {
                            append("\n\nCommit message template:\n$commitTemplate")
                        }
                        if (previousCommitsString.isNotBlank()) {
                            append("\n\nRecent commit messages for style reference:\n$previousCommitsString")
                        }
                    }

                    // Build user prompt
                    val userPrompt = buildString {
                        append("Branch: ${currentBranch ?: "unknown"}\n\n")
                        append("Diff:\n$diffString")
                    }

                    // Build OpenAI-style request body using buildJsonObject
                    val requestBody = buildJsonObject {
                        if (commitMessageModel.isNotBlank()) {
                            put("model", commitMessageModel)
                        }
                        put("messages", buildJsonArray {
                            addJsonObject {
                                put("role", "system")
                                put("content", systemPrompt)
                            }
                            addJsonObject {
                                put("role", "user")
                                put("content", userPrompt)
                            }
                        })
                        put("stream", false)
                    }.toString()

                    connection.outputStream.use { os ->
                        os.write(requestBody.toByteArray())
                        os.flush()
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val responseJson = Json.parseToJsonElement(response).jsonObject
                    val choices = responseJson["choices"]?.jsonArray
                    if (!choices.isNullOrEmpty()) {
                        val message = choices[0].jsonObject["message"]?.jsonObject
                        val content = message?.get("content")?.jsonPrimitive?.contentOrNull
                        if (!content.isNullOrBlank()) {
                            return content.trim()
                        }
                    }
                    // If we get here, the response didn't contain a valid message, fall through to default
                } else {
                    // Fall back to default getConnection (uses baseUrl or autocompleteRemoteUrl)
                    connection = getConnection("backend/create_commit_message")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 30000
                }

                val commitMessageRequest =
                    CommitMessageRequest(
                        context = diffString,
                        previous_commits = previousCommitsString,
                        branch = currentBranch ?: "",
                        commit_template = commitTemplate,
                        model = commitMessageModel.takeIf { it.isNotBlank() },
                    )
                val json = Json { encodeDefaults = true }
                val postData = json.encodeToString(CommitMessageRequest.serializer(), commitMessageRequest)

                connection.outputStream.use { os ->
                    os.write(postData.toByteArray())
                    os.flush()
                }
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val newCommitMessage = json.decodeFromString<Map<String, String>>(response)["commit_message"]
                commitMessage = newCommitMessage.toString()
            } finally {
                connection?.disconnect()
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate commit message", e)
        }

        return commitMessage.trim()
    }

    companion object {
        private val logger = Logger.getInstance(SweepCommitMessageService::class.java)

        // min time between git commit message updates
        private const val UPDATE_COOLDOWN_MS = 5 * 60 * 1000

        fun getInstance(project: Project): SweepCommitMessageService = project.getService(SweepCommitMessageService::class.java)
    }

    override fun dispose() {
        // Cancel any running tasks
        synchronized(runningTasks) {
            runningTasks.forEach { it.cancel(true) }
            runningTasks.clear()
        }

        // Disconnect message bus
        messageBusConnection?.disconnect()
        messageBusConnection = null

        // Clear references
        commitUi = null
        previousMessage = null
    }
}
