package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteRequest
import dev.sweep.assistant.autocomplete.edit.NextEditAutocompleteResponse
import dev.sweep.assistant.settings.SweepMetaData
import dev.sweep.assistant.utils.*
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
class AutocompleteIpResolverService : Disposable {
    companion object {
        private val logger = Logger.getInstance(AutocompleteIpResolverService::class.java)

        fun getInstance(project: Project): AutocompleteIpResolverService = project.getService(AutocompleteIpResolverService::class.java)

        private const val READ_TIMEOUT_MS = 10_000L
    }

    // HTTP client with connection pooling and keep-alive
    private val httpClient =
        HttpClient
            .newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build()

    /**
     * Executes a next edit autocomplete request against the configured server
     * (remote URL if set, otherwise the local server).
     */
    @RequiresBackgroundThread
    suspend fun fetchNextEditAutocomplete(request: NextEditAutocompleteRequest): NextEditAutocompleteResponse? =
        try {
            // 当地址留空时确保本地服务已启动（配置了远程地址时该调用内部自动跳过）。
            LocalAutocompleteServerManager.getInstance().ensureServerRunning()

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

            val authorization = "Bearer device_id_${SweepMetaData.getInstance().getOrCreateDeviceId()}"

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

            // 统一按行解析（兼容本地/远程服务，可优雅处理服务端流中断）
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
                                    logger.warn("Autocomplete server error: $errorMsg")
                                    continue
                                }
                            }
                            result = defaultJson.decodeFromString(NextEditAutocompleteResponse.serializer(), l)
                        } catch (e: Exception) {
                            logger.warn("Error parsing autocomplete server response: ${e.message}")
                        }
                    }
                }
            } catch (e: java.io.IOException) {
                // Server closed the stream (crash, broken pipe, etc.)
                // Process whatever we got before the closure
                logger.info("Autocomplete server stream closed: ${e.message}")
            }

            if (result != null) {
                LocalAutocompleteServerManager.getInstance().reportSuccess()
            } else {
                LocalAutocompleteServerManager.getInstance().reportFailure()
            }

            result
        } catch (e: Exception) {
            logger.warn("Error fetching next edit autocomplete: ${e.message}")
            LocalAutocompleteServerManager.getInstance().reportFailure()
            throw e
        }

    /**
     * Gets the autocomplete server base URL (remote URL if set, otherwise local server).
     */
    private fun getBaseUrl(): String = LocalAutocompleteServerManager.getInstance().getServerUrl()

    /**
     * 记录最近的用户操作时间（本地构建仅保留接口，供调用方使用）。
     */
    fun updateLastUserActionTimestamp() {
        // no-op in local build
    }

    override fun dispose() {
        // 共享 HttpClient 无需额外释放
    }
}
