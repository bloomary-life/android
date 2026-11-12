package life.bloomary.app.application.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Holds the tab/navigation UI state that lives in `@State` inside
 * ios/Application/Navigation/MainTabView.swift. Hoisted into the DI container
 * so feature layers (editor publish success, drafts list) can trigger the same
 * transitions the SwiftUI closures perform.
 */
class MainTabCoordinator {
    var selectedTab by mutableIntStateOf(2)
        private set

    var showsPostToast by mutableStateOf(false)
        private set

    var showsFeedback by mutableStateOf(false)
        private set

    /**
     * Mirrors `selectTab(_:)`: switching tabs keeps the editor as it is (iOS
     * stopped resetting it once opening a draft asks before discarding changes).
     */
    fun selectTab(tab: Int) {
        selectedTab = tab
    }

    /** Mirrors `openDraftInEditor()`. */
    fun openDraftInEditor() {
        selectedTab = 2
    }

    fun openFeedback() {
        showsFeedback = true
    }

    fun closeFeedback() {
        showsFeedback = false
    }

    /**
     * Mirrors `showPostedToast()`: jump to the Insights tab, show the toast for
     * 1.6 s, then fade it out (the 0.4 s fade animation lives in MainTabView).
     */
    fun showPostedToast(scope: CoroutineScope) {
        selectedTab = 0
        showsPostToast = true
        scope.launch {
            delay(1_600)
            showsPostToast = false
        }
    }
}
