package dev.hunkreview.pycharm.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(name = "HunkPluginSettings", storages = [Storage("hunk-review.xml", roamingType = RoamingType.DISABLED)])
class HunkPluginSettings : PersistentStateComponent<HunkPluginSettings.State> {

    data class State(
        var cliPath: String? = null,
        var pollIntervalMs: Long = 2500L,
        var lastComparisonRef: String = DEFAULT_COMPARISON_REF,
        var defaultGroupByDirectory: Boolean = false
    )

    private var state = State()

    var cliPath: String?
        get() = state.cliPath
        set(value) { state.cliPath = value }

    var pollIntervalMs: Long
        get() = state.pollIntervalMs
        set(value) { state.pollIntervalMs = value }

    var lastComparisonRef: String
        get() = state.lastComparisonRef
        set(value) { state.lastComparisonRef = value }

    var defaultGroupByDirectory: Boolean
        get() = state.defaultGroupByDirectory
        set(value) { state.defaultGroupByDirectory = value }

    override fun getState(): State = state

    override fun loadState(loadedState: State) {
        state = loadedState
    }

    companion object {
        const val DEFAULT_COMPARISON_REF = "master"
        fun getInstance(): HunkPluginSettings = service()
    }
}
