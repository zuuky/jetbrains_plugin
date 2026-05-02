package dev.sweep.assistant.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.serviceContainer.AlreadyDisposedException
import dev.sweep.assistant.components.SweepConfig
import dev.sweep.assistant.data.CommitMessageRequest
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.*
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class DiffTooLargeException(val diffTokens: Int, val maxTokens: Int) : Exception(
    "Diff too large: $diffTokens tokens exceeds maximum $maxTokens tokens. Please reduce the number of files in this commit."
)

@Service(Service.Level.PROJECT)
class SweepCommitMessageService(
    private val project: Project,
) {
    private val logger = Logger.getInstance(SweepCommitMessageService::class.java)
    private var previousMessage: String? = null
    private val generating = AtomicBoolean(false)
    val isGenerating: Boolean get() = generating.get()

    fun updateCommitMessage(
        commitMessage: CommitMessage,
        selectedChanges: List<Change> = emptyList(),
        partialChanges: List<PartialChangeInfo> = emptyList(),
        unversionedFiles: List<FilePath> = emptyList(),
        overrideCurrentMessage: Boolean = false,
    ) {
        if (project.isDisposed) return

        if (!generating.compareAndSet(false, true)) {
            logger.debug("Skipping commit message update: generation already in progress")
            return
        }

        try {
            if (project.isDisposed) {
                generating.set(false)
                return
            }

            val apiResponse = generateCommitMessage(selectedChanges, partialChanges, unversionedFiles)

            if (project.isDisposed || apiResponse.isBlank()) {
                generating.set(false)
                return
            }

            ApplicationManager.getApplication().invokeLater {
                try {
                    if (project.isDisposed) return@invokeLater

                    val currentMessage = commitMessage.text
                    if (
                        currentMessage.isBlank() ||
                        currentMessage.trim() == previousMessage?.trim() ||
                        overrideCurrentMessage
                    ) {
                        previousMessage = apiResponse
                        commitMessage.text = apiResponse
                    }
                } finally {
                    generating.set(false)
                }
            }
        } catch (e: ProcessCanceledException) {
            generating.set(false)
            throw e
        } catch (e: AlreadyDisposedException) {
            logger.debug("Project disposed during commit message generation")
            generating.set(false)
            throw e
        } catch (_: CancellationException) {
            logger.debug("Commit message generation cancelled")
            generating.set(false)
        } catch (e: DiffTooLargeException) {
            generating.set(false)
        } catch (e: TimeoutException) {
            generating.set(false)
            showErrorNotification(
                "Request Timeout",
                "Commit message generation timed out after 10 seconds. Please try again."
            )
        } catch (e: Exception) {
            logger.warn("Error making API call", e)
            generating.set(false)
        }
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

        var diffString =
            ProgressManager.getInstance().runProcess<String>(
                {
                    val changesDiff =
                        if (partialChanges.isNotEmpty()) {
                            generateCombinedDiffString(latestChanges, partialChanges, project)
                        } else {
                            generateDiffStringFromChanges(latestChanges, project)
                        }

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

        if (diffString.isBlank()) return ""

        if (diffString.length > MAX_INPUT_TOKENS) {
            diffString = diffString.take(MAX_INPUT_TOKENS)
        }

        var previousCommitsString =
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

        if (previousCommitsString.length > MAX_INPUT_TOKENS) {
            previousCommitsString = previousCommitsString.take(MAX_INPUT_TOKENS)
        }
        // Optional user-provided commit message template
        // Priority: Project-specific sweep-commit-template.md > Global ~/.sweep/sweep-commit-template.md
        val commitTemplate: String? =
            try {
                SweepConfig.getInstance(project).getEffectiveCommitMessageRules()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                logger.warn("Failed to get commit message template: ${e.message}", e)
                null
            }
        val settings = SweepSettings.getInstance()
        val commitMessageUrl = settings.commitMessageUrl.trimEnd('/')
        val commitMessageModel = settings.commitMessageModel

        return try {

            if (commitMessageUrl.isNotBlank()) {
                logger.debug("Using OpenAI endpoint: $commitMessageUrl with model: $commitMessageModel")
                val result = generateFromOpenAiCompatibleEndpoint(
                    commitMessageUrl = commitMessageUrl,
                    commitMessageModel = commitMessageModel,
                    token = settings.githubToken,
                    branch = currentBranch ?: "unknown",
                    diffString = diffString,
                    previousCommitsString = previousCommitsString,
                    commitTemplate = commitTemplate,
                )
                if (result.isBlank()) {
                    logger.warn("OpenAI endpoint returned empty response")
                } else {
                    logger.debug("Generated commit message: ${result.take(100)}...")
                }
                result
            } else {
                generateFromSweepEndpoint(
                    diffString = diffString,
                    previousCommitsString = previousCommitsString,
                    branch = currentBranch ?: "",
                    commitTemplate = commitTemplate,
                    commitMessageModel = commitMessageModel,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate commit message: ${e.message}", e)
            ""
        }
    }

    private fun generateFromOpenAiCompatibleEndpoint(
        commitMessageUrl: String,
        commitMessageModel: String,
        token: String,
        branch: String,
        diffString: String,
        previousCommitsString: String,
        commitTemplate: String?,
    ): String {
        val systemPrompt = buildString {
            append("You are a concise commit message generator. Generate a clear, conventional commit message based on the provided diff.")
            if (commitTemplate != null) {
                append("\n\nCommit message template:\n$commitTemplate")
            }
            if (previousCommitsString.isNotBlank()) {
                append("\n\nRecent commit messages for style reference:\n$previousCommitsString")
            }
        }

        val userPrompt = buildString {
            append("Branch: $branch\n\n")
            append("Diff:\n $diffString")
        }

        val requestBody = buildJsonObject {
            if (commitMessageModel.isNotBlank()) {
                put("model", commitMessageModel)
            }
            put(
                "messages",
                buildJsonArray {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                    addJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                },
            )
            put("stream", false)
        }.toString()

        return postJson("$commitMessageUrl/v1/chat/completions", token, requestBody) { response ->
            val choices = Json.parseToJsonElement(response).jsonObject["choices"]?.jsonArray
            choices
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                .orEmpty()
        }
    }

    private fun generateFromSweepEndpoint(
        diffString: String,
        previousCommitsString: String,
        branch: String,
        commitTemplate: String?,
        commitMessageModel: String,
    ): String {
        val request =
            CommitMessageRequest(
                context = diffString,
                previous_commits = previousCommitsString,
                branch = branch,
                commit_template = commitTemplate,
                model = commitMessageModel.takeIf { it.isNotBlank() },
            )
        val json = Json { encodeDefaults = true }
        val postData = json.encodeToString(CommitMessageRequest.serializer(), request)

        var connection: HttpURLConnection? = null
        return try {
            connection = getConnection("backend/create_commit_message").apply {
                connectTimeout = 10000
                readTimeout = 30000
            }
            connection.outputStream.use { os ->
                os.write(postData.toByteArray())
                os.flush()
            }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            json.decodeFromString<Map<String, String>>(response)["commit_message"]?.trim().orEmpty()
        } finally {
            connection?.disconnect()
        }
    }

    private fun postJson(
        url: String,
        token: String,
        body: String,
        parseResponse: (String) -> String,
    ): String {
        var connection: HttpURLConnection? = null
        val startTime = System.currentTimeMillis()
        val timeoutMs = 30_000L
        return try {
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connectTimeout = 10000
                readTimeout = 30000
            }
            connection.outputStream.use { os ->
                os.write(body.toByteArray())
                os.flush()
            }
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw TimeoutException("Request exceeded ${timeoutMs / 1000} second timeout")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..<300) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "No error details"
                logger.warn("HTTP $responseCode from $url: $errorBody")
                return ""
            }
            parseResponse(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (e: TimeoutException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to call OpenAI endpoint: ${e.message}", e)
            if (System.currentTimeMillis() - startTime > timeoutMs) {
                throw TimeoutException("Request exceeded ${timeoutMs / 1000} second timeout")
            }
            ""
        } finally {
            connection?.disconnect()
        }
    }

    private fun showErrorNotification(title: String, content: String) {
        ApplicationManager.getApplication().invokeLater {
            try {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("Sweep Commit Message")
                    .createNotification(title, content, NotificationType.WARNING)
                    .notify(project)
            } catch (e: Exception) {
                logger.warn("Failed to show notification: ${e.message}", e)
            }
        }
    }

    companion object {
        private const val MAX_INPUT_TOKENS = 50000

        private val logger = Logger.getInstance(SweepCommitMessageService::class.java)

        fun getInstance(project: Project): SweepCommitMessageService = project.getService(SweepCommitMessageService::class.java)
    }
}
