package life.bloomary.app.features.drafts.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import life.bloomary.app.shared.storage.BooleanSetting
import life.bloomary.app.shared.storage.InMemoryBooleanSetting

/**
 * "Reduce data usage": when on, automatic draft sync pauses; scheduling still
 * uploads the chosen draft. Mirrors ios/Features/Drafts/Storage/SyncSettingsStore.swift.
 */
class SyncSettingsStore(private val setting: BooleanSetting = InMemoryBooleanSetting()) {
    private val _isDataReductionEnabled = MutableStateFlow(setting.load())
    val isDataReductionEnabled: StateFlow<Boolean> = _isDataReductionEnabled.asStateFlow()

    fun setDataReductionEnabled(enabled: Boolean) {
        _isDataReductionEnabled.value = enabled
        setting.save(enabled)
    }
}
