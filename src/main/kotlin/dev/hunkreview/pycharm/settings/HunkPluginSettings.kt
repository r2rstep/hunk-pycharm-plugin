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
        var pollIntervalMs: Long = 2500L
    )

    private var state = State()

    var cliPath: String?
        get() = state.cliPath
        set(value) { state.cliPath = value }

    var pollIntervalMs: Long
        get() = state.pollIntervalMs
        set(value) { state.pollIntervalMs = value }

    override fun getState(): State = state

    override fun loadState(loadedState: State) {
        state = loadedState
    }

    companion object {
        fun getInstance(): HunkPluginSettings = service()
    }
}
