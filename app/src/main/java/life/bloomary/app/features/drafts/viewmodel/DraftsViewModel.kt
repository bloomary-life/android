package life.bloomary.app.features.drafts.viewmodel

import kotlinx.coroutines.flow.StateFlow
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.features.drafts.model.DraftStore

/**
 * Mirrors ios/Features/Drafts/ViewModel/DraftsViewModel.swift — a thin proxy
 * over [DraftStore]. The signed-out → signed-in draft merge lives in
 * `DraftStore.setOwnerID` and is triggered from the app shell when the
 * LinkedIn session connects, exactly like `MainTabView.swift` does.
 */
class DraftsViewModel(
    private val store: DraftStore,
) {
    val drafts: StateFlow<List<Draft>> = store.drafts
    val isPaywalled: StateFlow<Boolean> = store.isPaywalled

    fun open(draft: Draft) {
        if (store.isPaywalled.value) return
        store.openDraft(draft)
    }

    fun delete(draft: Draft) {
        store.deleteDraft(draft)
    }
}
