package dev.sweep.assistant.data

import com.intellij.openapi.application.PermanentInstallationID
import dev.sweep.assistant.utils.getDebugInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Base class for serialized requests sent to the autocomplete server.
 * Carries debug info and a stable device id.
 */
@Serializable
abstract class BaseRequest {
    @SerialName("debug_info")
    val debugInfo: String = getDebugInfo()

    @SerialName("device_id")
    val deviceId: String = PermanentInstallationID.get()
}

/**
 * Request for generating a commit message through the Sweep-style endpoint
 * (legacy; commit messages now go through the OpenAI-compatible endpoint).
 */
@Serializable
data class CommitMessageRequest(
    val context: String,
    val previous_commits: String,
    val branch: String,
    val commit_template: String? = null,
    val model: String? = null,
) : BaseRequest()
