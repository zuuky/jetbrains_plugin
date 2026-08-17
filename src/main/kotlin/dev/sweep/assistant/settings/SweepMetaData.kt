package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * IDE-level metadata persisted across restarts.
 * Only autocomplete / commit-message related counters are kept.
 */
@State(name = "SweepMetaData", storages = [Storage("SweepMetaData.xml")])
class SweepMetaData : PersistentStateComponent<SweepMetaData.MetaData> {
    data class MetaData(
        var ghostTextTabAcceptCount: Int = 0,
        var commitMessageButtonClicks: Int = 0,
        var hasUsedLookupItem: Boolean = false,
    )

    private var metaData = MetaData()

    override fun getState(): MetaData = metaData

    override fun loadState(state: MetaData) {
        this.metaData = state
    }

    var autocompleteAcceptCount: Int
        get() = metaData.ghostTextTabAcceptCount
        set(value) {
            metaData.ghostTextTabAcceptCount = value
        }

    var commitMessageButtonClicks: Int
        get() = metaData.commitMessageButtonClicks
        set(value) {
            metaData.commitMessageButtonClicks = value
        }

    var hasUsedLookupItem: Boolean
        get() = metaData.hasUsedLookupItem
        set(value) {
            metaData.hasUsedLookupItem = value
        }

    companion object {
        fun getInstance(): SweepMetaData = ApplicationManager.getApplication().getService(SweepMetaData::class.java)
    }
}
