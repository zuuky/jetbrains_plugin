package dev.sweep.assistant.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Local-only feature flag store.
 *
 * In the original plugin this service polled the Sweep backend for feature flags.
 * For this local build there is no backend, so all feature flags are simply
 * absent and every accessor falls back to its default value. The API surface is
 * kept so autocomplete call sites remain unchanged.
 */
@Service(Service.Level.PROJECT)
class FeatureFlagService(
    private val project: Project,
) : Disposable {
    companion object {
        fun getInstance(project: Project): FeatureFlagService = project.getService(FeatureFlagService::class.java)

        interface FeatureFlagListener {
            fun onFeatureFlagsUpdated(flags: Map<String, String>)
        }
    }

    private val featureFlags = ConcurrentHashMap<String, String>()

    @Volatile
    private var isInitialized = false

    fun isFeatureEnabled(flagKey: String): Boolean = featureFlags[flagKey] == "on"

    fun getFeatureFlag(flagKey: String): String? = featureFlags[flagKey]

    fun getNumericFeatureFlag(
        flagKey: String,
        defaultValue: Int,
    ): Int = featureFlags[flagKey]?.toIntOrNull() ?: defaultValue

    fun getStringFeatureFlag(
        flagKey: String,
        defaultValue: String,
    ): String = featureFlags[flagKey] ?: defaultValue

    fun getAllFeatureFlags(): Map<String, String> = featureFlags.toMap()

    fun isInitialized(): Boolean = isInitialized

    fun refreshFeatureFlags() {
        // No backend to refresh from in local mode
    }

    override fun dispose() {
        featureFlags.clear()
    }
}
