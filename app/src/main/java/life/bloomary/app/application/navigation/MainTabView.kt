package life.bloomary.app.application.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import life.bloomary.app.R
import life.bloomary.app.features.account.ui.AccountSettingsView
import life.bloomary.app.features.account.viewmodel.AccountViewModel
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.drafts.model.DraftStore
import life.bloomary.app.features.drafts.ui.DraftsView
import life.bloomary.app.features.drafts.viewmodel.DraftsViewModel
import life.bloomary.app.features.editor.ui.EditorView
import life.bloomary.app.features.editor.viewmodel.EditorViewModel
import life.bloomary.app.features.entitlement.api.EntitlementService
import life.bloomary.app.features.feedback.api.FeedbackService
import life.bloomary.app.features.feedback.ui.FeedbackView
import life.bloomary.app.features.insights.ui.InsightsView
import life.bloomary.app.features.linkedinauth.model.LinkedInProfile
import life.bloomary.app.features.linkedinauth.model.LinkedInSessionStore
import life.bloomary.app.features.notifications.api.NotificationPushRegistrar
import life.bloomary.app.features.notifications.api.NotificationReminderService
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.observability.PerformanceSignposts
import life.bloomary.app.shared.ui.AppStyle

private val TabLabels = intArrayOf(
    R.string.tab_insights,
    R.string.tab_drafts,
    R.string.tab_editor,
    R.string.tab_settings,
)

private val TabIcons = arrayOf("📈", "📝", "✏️", "⚙️")

/** Port of ios/Application/Navigation/MainTabView.swift. */
@Composable
fun MainTabView(
    coordinator: MainTabCoordinator,
    draftStore: DraftStore,
    linkedInSessionStore: LinkedInSessionStore,
    editorViewModel: EditorViewModel,
    draftsViewModel: DraftsViewModel,
    accountViewModel: AccountViewModel,
    aiSettingsStore: AISettingsStore,
    feedbackService: FeedbackService,
    entitlementService: EntitlementService,
    notificationReminderService: NotificationReminderService,
    notificationPushRegistrar: NotificationPushRegistrar,
) {
    val scope = rememberCoroutineScope()
    val token by linkedInSessionStore.token.collectAsState()

    LaunchedEffect(token?.accessToken, token?.profile?.memberID) {
        PerformanceSignposts.appLaunchComplete()
        draftStore.setOwnerID(token?.profile?.memberID)
        if (token == null) {
            editorViewModel.clearStreakState()
        }
        // Push token registration runs on session availability, mirroring the
        // APNs callback-driven registration behind the iOS
        // NotificationTokenService.
        notificationPushRegistrar.syncRegistration(token)
        notificationReminderService.syncTodayReminder(token)
        val credential = linkedInSessionStore.postCredential
        if (credential == null) {
            draftStore.setPaywalled(false)
        } else {
            runCatching { entitlementService.entitlement(credential) }.getOrNull()?.let { entitlement ->
                draftStore.setPaywalled(entitlement.isPaywalled)
                if (entitlement.isPaywalled && coordinator.selectedTab == 2) {
                    coordinator.selectTab(1)
                }
            }
        }
    }

    Scaffold(
        containerColor = AppStyle.paper,
        bottomBar = {
            NavigationBar(containerColor = AppStyle.panel) {
                TabLabels.forEachIndexed { index, labelRes ->
                    NavigationBarItem(
                        selected = coordinator.selectedTab == index,
                        onClick = { coordinator.selectTab(index) },
                        icon = { Text(TabIcons[index], fontSize = 20.sp) },
                        label = { Text(stringResource(labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppStyle.ink,
                            selectedTextColor = AppStyle.ink,
                            unselectedIconColor = AppStyle.muted,
                            unselectedTextColor = AppStyle.muted,
                            indicatorColor = AppStyle.lime,
                        ),
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (coordinator.selectedTab) {
                0 -> InsightsView(
                    knownStreak = editorViewModel.knownStreak,
                    onLoadStreak = { editorViewModel.loadStreak() },
                )
                1 -> DraftsView(
                    viewModel = draftsViewModel,
                    onOpenDraft = { coordinator.openDraftInEditor() },
                )
                2 -> EditorView(
                    viewModel = editorViewModel,
                    onShowDrafts = { coordinator.selectTab(1) },
                    onPostSucceeded = { coordinator.showPostedToast(scope) },
                    onShareStreak = { coordinator.selectTab(1) },
                )
                3 -> AccountSettingsView(
                    viewModel = accountViewModel,
                    feedbackService = feedbackService,
                    aiSettingsStore = aiSettingsStore,
                )
            }

            DraggableFeedbackButton(
                onClick = {
                    coordinator.openFeedback()
                    AppTelemetry.track(AppTelemetryEvent.FeedbackOpened)
                },
            )

            PostedToast(
                visible = coordinator.showsPostToast,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            if (coordinator.showsFeedback) {
                FeedbackOverlay(
                    feedbackService = feedbackService,
                    profile = token?.profile,
                    onClose = { coordinator.closeFeedback() },
                )
            }
        }
    }
}

/**
 * Floating ladybug feedback button. Mirrors the SwiftUI overlay: default at
 * (44, max(92, height * 0.82)), draggable, clamped 34pt from each edge.
 */
@Composable
private fun DraggableFeedbackButton(onClick: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val radiusPx = with(density) { 34.dp.toPx() }
        val defaultX = with(density) { 44.dp.toPx() }
        val minDefaultY = with(density) { 92.dp.toPx() }
        val defaultY = max(minDefaultY, constraints.maxHeight * 0.82f)

        var position by remember { mutableStateOf(Offset(defaultX, defaultY)) }

        fun clamped(candidate: Offset) = Offset(
            x = min(max(candidate.x, radiusPx), constraints.maxWidth - radiusPx),
            y = min(max(candidate.y, radiusPx), constraints.maxHeight - radiusPx),
        )

        val feedbackDescription = stringResource(R.string.send_feedback)
        Box(
            modifier = Modifier
                .offset { IntOffset((position.x - 26.dp.toPx()).roundToInt(), (position.y - 26.dp.toPx()).roundToInt()) }
                .size(52.dp)
                .clip(CircleShape)
                .background(AppStyle.coral)
                .semantics { contentDescription = feedbackDescription }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onClick() })
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        position = clamped(position + dragAmount)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // SF Symbol "ladybug" → emoji counterpart.
            Text("🐞", fontSize = 20.sp)
        }
    }
}

/** "Posted to LinkedIn" toast: 0.2 s fade/slide in, 0.4 s fade out. */
@Composable
private fun PostedToast(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
        exit = fadeOut(tween(400)),
    ) {
        Text(
            text = stringResource(R.string.posted_toast),
            color = AppStyle.ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(top = 12.dp)
                .shadow(elevation = 18.dp, shape = RoundedCornerShape(50), ambientColor = AppStyle.ink.copy(alpha = 0.12f))
                .background(AppStyle.panel.copy(alpha = 0.96f), RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun FeedbackOverlay(
    feedbackService: FeedbackService,
    profile: LinkedInProfile?,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppStyle.ink.copy(alpha = 0.3f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = AppStyle.panel,
            modifier = Modifier.padding(32.dp),
        ) {
            FeedbackView(
                feedbackService = feedbackService,
                profile = profile,
                onClose = onClose,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
