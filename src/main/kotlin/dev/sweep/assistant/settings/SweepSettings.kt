package dev.sweep.assistant.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import com.intellij.util.xmlb.XmlSerializerUtil
import dev.sweep.assistant.tracking.EventType
import dev.sweep.assistant.tracking.TelemetryService

data class CustomPrompt(
    var name: String = "",
    var prompt: String = "",
    var includeSelectedCode: Boolean = true,
)

data class BYOKProviderConfig(
    var apiKey: String = "",
    var eligibleModels: List<String> = emptyList(),
)

@State(
    name = "dev.sweep.jetbrains.settings.SweepSettings",
    storages = [Storage("SweepSettings.xml")],
)
class SweepSettings : PersistentStateComponent<SweepSettings> {
    companion object {
        private const val DEFAULT_GITHUB_TOKEN = ""
        private const val DEFAULT_SWEEP_URL = ""
        private const val DEFAULT_BETA_FLAG_ON = false
        private const val DEFAULT_NEXT_EDIT_PREDICTION_ON = true
        private const val DEFAULT_ACCEPT_WORD_ON_RIGHT_ARROW = true
        private const val DEFAULT_ANTHROPIC_API_KEY = ""
        private const val DEFAULT_PLAY_NOTIFICATION_ON_STREAM_END = false
        private const val DEFAULT_DEVELOPER_MODE_ON = false

        // -1L means "unset" so project-level values can migrate in
        private const val DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS = -1L

        // Default to false - do not automatically disable conflicting autocomplete plugins
        private const val DEFAULT_DISABLE_CONFLICTING_PLUGINS = true

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

    var githubToken: String = DEFAULT_GITHUB_TOKEN
        get() = field.trim()
        set(value) {
            if (isLoadingState) {
                field = value
                return
            }
            if (value != field) {
                field = value // make sure you report recent data
                notifySettingsChanged()
                sendTelemetryLater(EventType.USER_AUTHENTICATED)
            } else {
                field = value
            }
        }

    var baseUrl: String = DEFAULT_SWEEP_URL
        get() =
            if (SweepSettingsParser.isCloudEnvironment()) {
                SweepEnvironmentConstants.Defaults.DEFAULT_BASE_URL
            } else {
                field.trim().trimEnd('/')
            }
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

    var betaFlagOn: Boolean = DEFAULT_BETA_FLAG_ON
        set(value) {
            field = value
        }

    var nextEditPredictionFlagOn: Boolean = DEFAULT_NEXT_EDIT_PREDICTION_ON
        set(value) {
            if (isLoadingState) {
                field = value
                return
            }
            if (value != field) {
                field = value
                notifySettingsChanged()
                // Send telemetry when autocomplete is disabled
                if (!value) {
                    sendTelemetryLater(EventType.AUTOCOMPLETE_DISABLED)
                }
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

    var anthropicApiKey: String = DEFAULT_ANTHROPIC_API_KEY
        get() = field.trim()
        set(value) {
            field = value
        }

    var playNotificationOnStreamEnd: Boolean = DEFAULT_PLAY_NOTIFICATION_ON_STREAM_END
        set(value) {
            field = value
        }

    var developerModeOn: Boolean = DEFAULT_DEVELOPER_MODE_ON
        set(value) {
            field = value
        }

    /**
     * Autocomplete debounce delay in milliseconds.
     * This is stored at the application level and applies to all projects.
     * A value of -1 indicates "unset" and allows a one-time migration from any existing
     * project-level setting in SweepConfig when first accessed.
     */
    var autocompleteDebounceMs: Long = DEFAULT_AUTOCOMPLETE_DEBOUNCE_MS
        set(value) {
            val clamped = value.coerceIn(10L, 1000L)
            field = clamped
            // We intentionally do not fire notifySettingsChanged here to avoid
            // excessive message bus chatter while the user drags the slider.
        }

    /**
     * Automatically disable conflicting autocomplete plugins.
     * This is stored at the application level and applies to all projects.
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

    var customPrompts: MutableList<CustomPrompt> = mutableListOf()
        set(value) {
            if (isLoadingState) {
                field = value
                return
            }
            field = value
            notifySettingsChanged()
        }

    var hasInitializedDefaultPrompts: Boolean = false

    /**
     * BYOK (Bring Your Own Key) provider configurations.
     * This is stored at the application level and applies to all projects.
     * Map of provider name -> BYOKProviderConfig (apiKey, eligibleModels)
     */
    var byokProviderConfigs: MutableMap<String, BYOKProviderConfig> = mutableMapOf()
        set(value) {
            field = value
            // Don't notify settings changed for BYOK to avoid excessive chatter
        }

    var autocompleteLocalMode: Boolean = true

    var autocompleteLocalPort: Int = 8006

    /**
     * Remote autocomplete server URL (e.g. http://gpu-server).
     * When set and autocompleteLocalMode is true, connects to this URL
     * instead of starting a local uvx sweep-autocomplete process.
     */
    var autocompleteRemoteUrl: String = "http://10.218.230.4:$autocompleteLocalPort"

    /**
     * Custom LLM URL for commit message generation (e.g. http://llm-server:8000).
     * When set, this URL is used as the base for create_commit_message API calls.
     */
    var commitMessageUrl: String = "http://10.218.230.4:8015"

    /**
     * Model name for commit message generation.
     * When set, this value is passed alongside the commit message request.
     */
    var commitMessageModel: String = "general-model"

    fun ensureDefaultPromptsInitialized() {
        var addedPrompt = false

        if (customPrompts.none { it.name == "AI Code Review" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "AI Code Review",
                    prompt = "Review each of the changes in detail for potential bugs",
                    includeSelectedCode = false,
                ),
            )
            addedPrompt = true
        }

        if (customPrompts.none { it.name == "Explain Code" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "Explain Code",
                    prompt = "Explain what the code does.",
                    includeSelectedCode = true,
                ),
            )
            addedPrompt = true
        }

        if (customPrompts.none { it.name == "Write Documentation" }) {
            customPrompts.add(
                CustomPrompt(
                    name = "Write Documentation",
                    prompt = "Please write documentation for the highlighted code.",
                    includeSelectedCode = true,
                ),
            )
            addedPrompt = true
        }

        if (addedPrompt) {
            // Trigger state save by creating a new list instance to change the reference
            customPrompts = customPrompts.toMutableList()
        }

        if (!hasInitializedDefaultPrompts || addedPrompt) {
            hasInitializedDefaultPrompts = true
        }
    }

    val useLocalMode: Boolean
        get() = SweepSettingsParser.isCloudEnvironment() && anthropicApiKey.isNotEmpty()

    /**
     * Determines if the user has configured Sweep settings if either:
     * 1. Both GitHub token and base URL have been set to non-default values, OR
     * 2. An Anthropic API key has been provided
     */
    val hasBeenSet: Boolean
        get() {
            // If autocomplete is configured with a remote URL, consider settings as set
            if (autocompleteLocalMode && autocompleteRemoteUrl.isNotBlank()) return true
            return if (SweepSettingsParser.isCloudEnvironment()) {
                githubToken != DEFAULT_GITHUB_TOKEN
            } else {
                githubToken != DEFAULT_GITHUB_TOKEN && baseUrl != DEFAULT_SWEEP_URL
            }
        }

    fun notifySettingsChanged() {
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager
                .getApplication()
                ?.messageBus
                ?.syncPublisher(SettingsChangedNotifier.TOPIC)
                ?.settingsChanged()
        }
    }

    private fun sendTelemetryLater(eventType: EventType) {
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching {
                TelemetryService.getInstance().sendUsageEvent(eventType)
            }
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

    fun initiateGitHubAuth(project: Project) {
        GitHubAuthHandler.initiateAuth(project)
    }

    override fun getState(): SweepSettings = this

    override fun loadState(state: SweepSettings) {
        isLoadingState = true
        try {
            XmlSerializerUtil.copyBean(state, this)
        } finally {
            isLoadingState = false
        }
        // Initialize default prompts after loading state
        ensureDefaultPromptsInitialized()
    }
}
