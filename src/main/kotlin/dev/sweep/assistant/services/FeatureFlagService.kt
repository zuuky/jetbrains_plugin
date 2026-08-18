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
class FeatureFlagService : Disposable {
    companion object {
        fun getInstance(project: Project): FeatureFlagService = project.getService(FeatureFlagService::class.java)
    }

    private val featureFlags = ConcurrentHashMap<String, String>()

    fun isFeatureEnabled(flagKey: String): Boolean = featureFlags[flagKey] == "on"

    fun getNumericFeatureFlag(
        flagKey: String,
        defaultValue: Int,
    ): Int = featureFlags[flagKey]?.toIntOrNull() ?: defaultValue

    fun getStringFeatureFlag(
        flagKey: String,
        defaultValue: String,
    ): String = featureFlags[flagKey] ?: defaultValue

    override fun dispose() {
        featureFlags.clear()
    }
}
