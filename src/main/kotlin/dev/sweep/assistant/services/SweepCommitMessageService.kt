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
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.*
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class DiffTooLargeException(val diffTokens: Int, val maxTokens: Int) : Exception(
    "Diff too large: $diffTokens tokens exceeds maximum $maxTokens tokens. Please reduce the number of files in this commit."
)

/**
 * Generates commit messages using the configured OpenAI-compatible LLM endpoint
 * (see SweepSettings.commitMessageUrl / commitMessageModel).
 */
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
                            generateDiffStringFromChanges(latestChanges, project = project)
                        }

                    val unversionedDiff =
                        if (unversionedFiles.isNotEmpty()) {
                            generateDiffStringFromUnversionedFiles(unversionedFiles, project = project)
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

        val settings = SweepSettings.getInstance()
        var previousCommitsString =
            if (settings.useCustomizedCommitMessages) {
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
                getEffectiveCommitMessageRules()?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                logger.warn("Failed to get commit message template: ${e.message}", e)
                null
            }

        val commitMessageUrl = settings.commitMessageUrl.trim().trimEnd('/')
        val commitMessageModel = settings.commitMessageModel

        if (commitMessageUrl.isBlank()) {
            logger.warn("Commit message LLM URL is not configured")
            return ""
        }

        return try {
            logger.debug("Using OpenAI endpoint: $commitMessageUrl with model: $commitMessageModel")
            generateFromOpenAiCompatibleEndpoint(
                commitMessageUrl = commitMessageUrl,
                commitMessageModel = commitMessageModel,
                branch = currentBranch ?: "unknown",
                diffString = diffString,
                previousCommitsString = previousCommitsString,
                commitTemplate = commitTemplate,
            )
        } catch (e: Exception) {
            logger.warn("Failed to generate commit message: ${e.message}", e)
            ""
        }
    }

    private fun generateFromOpenAiCompatibleEndpoint(
        commitMessageUrl: String,
        commitMessageModel: String,
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
            // 关闭推理模型的思考（reasoning），覆盖三类 OpenAI 兼容服务端：
            // - llama.cpp（最新版）：顶层 chat_template_kwargs.enable_thinking（Jinja 模板参数）
            // - DeepSeek API / 部分代理：顶层 enable_thinking
            // - sglang / vLLM：extra_body.chat_template_kwargs.enable_thinking
            put("enable_thinking", false)
            putJsonObject("chat_template_kwargs") {
                put("enable_thinking", false)
            }
            putJsonObject("extra_body") {
                putJsonObject("chat_template_kwargs") {
                    put("enable_thinking", false)
                }
            }
        }.toString()

        return postJson("$commitMessageUrl/v1/chat/completions", requestBody) { response ->
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

    private fun postJson(
        url: String,
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

    /**
     * Gets the effective commit message rules to use for commit message generation.
     * Priority: Project-specific sweep-commit-template.md > Global commit message rules (~/.sweep/sweep-commit-template.md)
     */
    private fun getEffectiveCommitMessageRules(): String? {
        // Project-specific template takes precedence
        val basePath = project.osBasePath
        if (basePath != null) {
            val projectTemplate = File("$basePath/sweep-commit-template.md")
            if (projectTemplate.exists()) {
                try {
                    return projectTemplate.readText()
                } catch (e: Exception) {
                    logger.warn("Failed to read project commit template", e)
                }
            }
        }

        // Fall back to global commit message rules
        val globalRules = File("${System.getProperty("user.home")}/.sweep/sweep-commit-template.md")
        if (globalRules.exists()) {
            try {
                return globalRules.readText()
            } catch (e: Exception) {
                logger.warn("Failed to read global commit template", e)
            }
        }

        return null
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
