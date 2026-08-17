package dev.sweep.assistant.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level settings for the local autocomplete (next-edit) plugin and
 * LLM-backed commit message generation.
 *
 * Stores:
 * - next-edit autocomplete preferences (enabled, debounce, accept behavior, badge)
 * - next-edit server configuration (local port / remote URL)
 * - commit message LLM configuration (URL + model)
 * - autocomplete exclusion patterns
 */
@State(
    name = "dev.sweep.jetbrains.settings.SweepSettings",
    storages = [Storage("SweepSettings.xml")],
)
class SweepSettings : PersistentStateComponent<SweepSettings> {
    companion object {
        private const val DEFAULT_NEXT_EDIT_PREDICTION_ON = true
        private const val DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW = true
        private const val DEFAULT_DISABLE_CONFLICTING_PLUGINS = true

        // -1L means "unset" so project-level values from older versions can migrate in
        private const val DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS = -1L
        private const val DEFAULT_DEBOUNCE_MS = 10L

        const val DEFAULT_AUTOCOMPLETE_PORT = 8006
        const val DEFAULT_COMMIT_MESSAGE_URL = "http://10.218.230.4:8015"
        const val DEFAULT_COMMIT_MESSAGE_MODEL = "general-model"

        fun getInstance(): SweepSettings = ApplicationManager.getApplication().getService(SweepSettings::class.java)
    }

    @Volatile
    private var isLoadingState = false

    // Do not notify settings changed on each save, fire it in config instead
    fun interface SettingsChangedNotifier {
        fun settingsChanged()

        companion object {
            @JvmField
            val TOPIC = Topic.create("Sweep settings changed", SettingsChangedNotifier::class.java)
        }
    }

    /// Next-edit (autocomplete) preferences

    var nextEditPredictionFlagOn: Boolean = DEFAULT_NEXT_EDIT_PREDICTION_ON
        set(value) {
            if (isLoadingState) {
                field = value
                return
            }
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    var acceptWordOnRightArrow: Boolean = DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW
        set(value) {
            if (isLoadingState) {
                field = value
                return
            }
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    /**
     * Autocomplete debounce delay in milliseconds (applies to all projects).
     * A value <= 0 means "unset" and the effective default (10ms) is used.
     */
    var autocompleteDebounceMs: Long = DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS
        set(value) {
            val clamped = value.coerceIn(10L, 1000L)
            field = clamped
            // We intentionally do not fire notifySettingsChanged here to avoid
            // excessive message bus chatter while the user drags the slider.
        }

    /** Returns the effective debounce delay in milliseconds. */
    fun getEffectiveDebounceMs(): Long =
        if (autocompleteDebounceMs <= 0L) DEFAULT_DEBOUNCE_MS else autocompleteDebounceMs

    /**
     * Automatically disable conflicting autocomplete plugins (e.g. Copilot, Tabnine).
     */
    var disableConflictingPlugins: Boolean = DEFAULT_DISABLE_CONFLICTING_PLUGINS
        set(value) {
            if (isLoadingState) {
                field = value
                return
            }
            if (value != field) {
                field = value
                notifySettingsChanged()
            } else {
                field = value
            }
        }

    /// Server configuration for next-edit

    var autocompleteLocalMode: Boolean = true

    var autocompleteLocalPort: Int = DEFAULT_AUTOCOMPLETE_PORT

    /**
     * Remote autocomplete server URL (e.g. http://gpu-server:8006).
     * When set and autocompleteLocalMode is true, connects to this URL
     * instead of starting a local uvx sweep-autocomplete process.
     */
    var autocompleteRemoteUrl: String = "http://10.218.230.4:$autocompleteLocalPort"

    /** Returns the effective autocomplete server URL. Priority: remote URL > localhost. */
    fun getEffectiveAutocompleteUrl(): String {
        val remoteUrl = autocompleteRemoteUrl.trim()
        if (remoteUrl.isNotBlank()) return remoteUrl
        return "http://localhost:$autocompleteLocalPort"
    }

    /// Commit message LLM configuration

    /**
     * Custom LLM URL for commit message generation (e.g. http://llm-server:8000).
     * When set, this URL is used as the base for OpenAI-compatible /v1/chat/completions.
     */
    var commitMessageUrl: String = DEFAULT_COMMIT_MESSAGE_URL

    /**
     * Model name for commit message generation.
     */
    var commitMessageModel: String = DEFAULT_COMMIT_MESSAGE_MODEL

    /// Next-edit UI preferences

    // Show the "Tab to accept" badge next to ghost text / popup suggestions
    var showAutocompleteBadge: Boolean = false

    // Autocomplete exclusion patterns - files matching these patterns won't trigger autocomplete.
    // v2 is additive; the getter merges v1 and v2 so existing users keep their patterns and get .env added.
    var autocompleteExclusionPatterns: Set<String> = emptySet()

    var autocompleteExclusionPatternsV2: Set<String> = setOf(".env")

    fun allAutocompleteExclusionPatterns(): Set<String> =
        autocompleteExclusionPatterns + autocompleteExclusionPatternsV2

    fun updateAutocompleteExclusionPatterns(patterns: Set<String>) {
        autocompleteExclusionPatternsV2 = patterns
        autocompleteExclusionPatterns = emptySet()
    }

    // Whether to hide the autocomplete exclusion banner (user clicked "Don't show again")
    var hideAutocompleteExclusionBanner: Boolean = false

    /// Commit message customization

    // Include recent commit messages as style reference when generating a commit message
    var useCustomizedCommitMessages: Boolean = true

    /**
     * Determines if the plugin is considered "configured".
     * For this local build, settings are always considered set as long as
     * next-edit autocomplete is enabled.
     */
    val hasBeenSet: Boolean
        get() = true

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager
                .getApplication()
                ?.messageBus
                ?.syncPublisher(SettingsChangedNotifier.TOPIC)
                ?.settingsChanged()
        }
    }

    fun runNowAndOnSettingsChange(
        project: Project,
        parentDisposable: Disposable,
        callback: SweepSettings.() -> Unit,
    ) {
        this.callback()
        project.messageBus.connect(parentDisposable).subscribe(
            SettingsChangedNotifier.TOPIC,
            SettingsChangedNotifier {
                getInstance().callback()
            },
        )
    }

    override fun getState(): SweepSettings = this

    override fun loadState(state: SweepSettings) {
        isLoadingState = true
        try {
            XmlSerializerUtil.copyBean(state, this)
        } finally {
            isLoadingState = false
        }
    }
}
