package life.bloomary.app.features.drafts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.features.drafts.viewmodel.DraftsViewModel
import life.bloomary.app.shared.ui.AppStyle

/**
 * Port of ios/Features/Drafts/UI/DraftsView.swift.
 *
 * `onOpenDraft` plays the role of the SwiftUI closure — the app shell passes
 * `MainTabCoordinator.openDraftInEditor`.
 */
@Composable
fun DraftsView(
    viewModel: DraftsViewModel,
    onOpenDraft: () -> Unit,
) {
    val drafts by viewModel.drafts.collectAsState()
    val isPaywalled by viewModel.isPaywalled.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppStyle.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Drafts",
            color = AppStyle.ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )

        if (isPaywalled) {
            PaywallBanner()
        }

        if (drafts.isEmpty()) {
            EmptyDraftsPlaceholder(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 48.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppStyle.panel),
            ) {
                drafts.forEachIndexed { index, draft ->
                    DraftRow(
                        draft = draft,
                        onOpen = {
                            viewModel.open(draft)
                            onOpenDraft()
                        },
                        onDelete = { viewModel.delete(draft) },
                        isPaywalled = isPaywalled,
                    )
                    if (index != drafts.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = AppStyle.ink.copy(alpha = 0.12f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftRow(
    draft: Draft,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    isPaywalled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DraftContent(
            draft = draft,
            isPaywalled = isPaywalled,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    enabled = !isPaywalled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen,
                ),
        )

        // SF Symbol "xmark" → text glyph counterpart (plain button style).
        Box(
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = "Delete draft" }
                .clickable(
                    enabled = !isPaywalled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDelete,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                color = AppStyle.muted.copy(alpha = if (isPaywalled) 0.35f else 1f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PaywallBanner() {
    Text(
        text = "Free tier access ended.",
        color = AppStyle.ink,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppStyle.lime.copy(alpha = 0.35f))
            .padding(12.dp),
    )
}

@Composable
private fun DraftContent(draft: Draft, isPaywalled: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = draft.title,
            color = AppStyle.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = draft.body,
            color = AppStyle.muted,
            fontSize = 15.sp,
            maxLines = if (isPaywalled) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = draft.formattedUpdatedAt(),
            color = AppStyle.muted,
            fontSize = 12.sp,
        )
    }
}

/** ContentUnavailableView("No drafts", systemImage: "doc.text") counterpart. */
@Composable
private fun EmptyDraftsPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "📄", fontSize = 30.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "No drafts",
            color = AppStyle.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Saved drafts will appear here.",
            color = AppStyle.muted,
            fontSize = 15.sp,
        )
    }
}

/** Swift `.formatted(date: .abbreviated, time: .shortened)` — locale-dependent. */
private fun Draft.formattedUpdatedAt(): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(updatedAt)
