package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.PermanentInstallationID
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.settings.SweepSettings
import dev.sweep.assistant.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Executes the next-edit autocomplete HTTP requests against the configured server
 * (remote URL when set, otherwise the local sweep-autocomplete server).
 */
@Service(Service.Level.PROJECT)
class AutocompleteIpResolverService(
    private val project: Project,
) : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService = project.getService(AutocompleteIpResolverService::class.java)

        private const val READ_TIMEOUT_MS = 10_000L
        private const val USER_ACTIVITY_TIMEOUT_MS = 15 * 60 * 1000L // 15 minutes
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lastUserActionTimestamp: java.util.concurrent.atomic.AtomicLong =
        java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())

    // HTTP client with connection pooling and keep-alive
    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    /**
     * Gets the shared HttpClient instance for connection pooling.
     * This allows other services to use the same connection pool.
     */
    fun getSharedHttpClient(): HttpClient = httpClient

    /**
     * Executes a next edit autocomplete request against the configured server
     * (remote URL if set, otherwise the local server).
     */
    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? =
        try {
            val isLocalMode = SweepSettings.getInstance().autocompleteLocalMode
            if (isLocalMode) {
                LocalAutocompleteServerManager.getInstance().ensureServerRunning()
            }

            val postData = encodeString(request, NextEditAutocompleteRequest.serializer())
            val postDataBytes = postData.toByteArray(Charsets.UTF_8)

            // Try to compress the request data
            val (finalData, useCompression) =
                if (CompressionUtils.isBrotliAvailable()) {
                    val compressedData = CompressionUtils.compress(postDataBytes, CompressionUtils.CompressionType.BROTLI)
                    if (compressedData.size < postDataBytes.size) {
                        val compressionRatio = CompressionUtils.calculateCompressionRatio(postDataBytes.size, compressedData.size)
                        logger.info(
                            "Request compressed: ${postDataBytes.size} -> ${compressedData.size} bytes (${String.format(
                                "%.1f",
                                compressionRatio,
                            )}% reduction)",
                        )
                        Pair(compressedData, true)
                    } else {
                        logger.info("Compression not beneficial, sending uncompressed")
                        Pair(postDataBytes, false)
                    }
                } else {
                    logger.info("Brotli not available, sending uncompressed")
                    Pair(postDataBytes, false)
                }

            val authorization = "Bearer device_id_${PermanentInstallationID.get()}"

            val httpRequestBuilder =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("${getBaseUrl()}/backend/next_edit_autocomplete"))
                    .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                    .header("Content-Type", "application/json")
                    .header("Authorization", authorization)
                    .header("X-Plugin-Version", getCurrentSweepPluginVersion() ?: "unknown")
                    .header("X-IDE-Name", ApplicationInfo.getInstance().fullApplicationName)
                    .header("X-IDE-Version", ApplicationInfo.getInstance().fullVersion)
                    .header("X-Debug-Info", getDebugInfo())

            if (useCompression) {
                httpRequestBuilder.header("Content-Encoding", CompressionUtils.CompressionType.BROTLI.encoding)
            }

            val httpRequest = httpRequestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(finalData)).build()

            val response =
                httpClient
                    .sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
                    .await()
                    .raiseForStatus()

            var result: NextEditAutocompleteResponse? = null

            if (isLocalMode) {
                // For local mode, read line-by-line to handle server crashes mid-stream gracefully
                try {
                    response.body().bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isBlank()) continue
                            try {
                                val jsonElement = defaultJson.parseToJsonElement(l)
                                if (jsonElement is JsonObject && jsonElement.containsKey("status")) {
                                    val status = jsonElement["status"]?.jsonPrimitive?.contentOrNull
                                    if (status == "error") {
                                        val errorMsg = jsonElement["error"]?.jsonPrimitive?.contentOrNull ?: "Unknown error"
                                        logger.warn("Local autocomplete server error: $errorMsg")
                                        continue
                                    }
                                }
                                result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), l)
                            } catch (e: Exception) {
                                logger.warn("Error parsing local server response: ${e.message}")
                            }
                        }
                    }
                } catch (e: java.io.IOException) {
                    // Server closed the stream (crash, broken pipe, etc.)
                    // Process whatever we got before the closure
                    logger.info("Local server stream closed: ${e.message}")
                }

                if (result != null) {
                    LocalAutocompleteServerManager.getInstance().reportSuccess()
                } else {
                    LocalAutocompleteServerManager.getInstance().reportFailure()
                }
            } else {
                response.streamJson<NextEditAutocompleteResponse>().collect {
                    result = it
                }
            }

            result
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            if (SweepSettings.getInstance().autocompleteLocalMode) {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }
            throw e
        }

    /**
     * Gets the autocomplete server base URL (remote URL if set, otherwise local server).
     */
    fun getBaseUrl(): String = LocalAutocompleteServerManager.getInstance().getServerUrl()

    /**
     * Updates the timestamp of the last user action.
     */
    fun updateLastUserActionTimestamp() {
        lastUserActionTimestamp.set(System.currentTimeMillis())
    }

    /**
     * Checks if there was user activity within the last 10 minutes.
     */
    private fun hasRecentUserActivity(): Boolean {
        val currentTime = System.currentTimeMillis()
        return (currentTime - lastUserActionTimestamp.get()) <= USER_ACTIVITY_TIMEOUT_MS
    }

    override fun dispose() {
        scope.cancel()
    }
}
