package dev.sweep.assistant.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import java.util.*

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
        var deviceId: String? = null,
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

    /**
     * 稳定的设备标识（替代已标记移除的 PermanentInstallationID）。
     * 首次调用时生成并持久化，跨重启保持不变。
     */
    fun getOrCreateDeviceId(): String {
        metaData.deviceId?.let { return it }
        val id = UUID.randomUUID().toString()
        metaData.deviceId = id
        return id
    }

    companion object {
        fun getInstance(): SweepMetaData = ApplicationManager.getApplication().getService(SweepMetaData::class.java)
    }
}
