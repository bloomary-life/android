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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import life.bloomary.app.features.drafts.domain.SchedulePolicy
import life.bloomary.app.features.drafts.model.Draft
import life.bloomary.app.features.drafts.model.DraftSchedule
import life.bloomary.app.features.drafts.model.DraftSyncStatus
import life.bloomary.app.features.drafts.viewmodel.DraftsViewModel
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.DateTimePickerField

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
    val syncStatus by viewModel.syncStatus.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val schedules by viewModel.scheduledPosts.collectAsState()
    val plan by viewModel.plan.collectAsState()
    var searchText by remember { mutableStateOf("") }
    var showsScheduledOnly by remember { mutableStateOf(false) }
    var reschedulingDraft by remember { mutableStateOf<Draft?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshFromRemote()
            delay(30_000)
        }
    }

    val query = searchText.trim()
    val visibleDrafts = drafts
        .sortedByDescending { it.updatedAt }
        .filter { draft ->
            (!showsScheduledOnly || viewModel.scheduledPost(draft, schedules) != null) &&
                (query.isEmpty() || draft.title.contains(query, ignoreCase = true) || draft.body.contains(query, ignoreCase = true))
        }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppStyle.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = "Drafts", color = AppStyle.ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${drafts.size}/${plan.maxDrafts}",
                color = if (!plan.hasPlanAccess && drafts.size >= plan.maxDrafts - 1) AppStyle.coral else AppStyle.muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        OutlinedTextField(
            value = searchText,
            onValueChange = { searchText = it },
            placeholder = { Text("Search drafts") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            listOf("All" to false, "Scheduled" to true).forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = showsScheduledOnly == value,
                    onClick = { showsScheduledOnly = value },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                ) { Text(label) }
            }
        }

        Text(text = syncStatus.message, color = AppStyle.muted, fontSize = 12.sp)
        if (syncStatus == DraftSyncStatus.Failed) {
            TextButton(onClick = { viewModel.retrySync() }) { Text("Retry sync") }
        }

        statusMessage?.let { Text(text = it, color = AppStyle.coral, fontSize = 12.sp) }

        if (viewModel.isOverDraftLimit) {
            Text(
                text = "⚠ " + viewModel.overLimitMessage,
                color = AppStyle.ink,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppStyle.coral.copy(alpha = 0.1f))
                    .padding(10.dp),
            )
        }

        if (visibleDrafts.isEmpty()) {
            EmptyDraftsPlaceholder(
                scheduledOnly = showsScheduledOnly,
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
                visibleDrafts.forEachIndexed { index, draft ->
                    val schedule = viewModel.scheduledPost(draft, schedules)
                    val canSchedule = viewModel.canSchedule && draft.status != "published" && viewModel.canEdit(draft) &&
                        (schedule == null || schedule.status in setOf("pending", "retry", "needs_review"))
                    DraftRow(
                        draft = draft,
                        schedule = schedule,
                        canSchedule = canSchedule,
                        canEdit = viewModel.canEdit(draft),
                        onOpen = { if (viewModel.open(draft)) onOpenDraft() },
                        onSchedule = { reschedulingDraft = draft },
                        onDelete = { viewModel.delete(draft) },
                    )
                    if (index != visibleDrafts.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = AppStyle.ink.copy(alpha = 0.12f))
                    }
                }
            }
        }
    }

    reschedulingDraft?.let { draft ->
        ScheduleDraftDialog(
            draft = draft,
            schedule = viewModel.scheduledPost(draft, schedules),
            viewModel = viewModel,
            onDismiss = { reschedulingDraft = null },
        )
    }
}

@Composable
private fun ScheduleDraftDialog(
    draft: Draft,
    schedule: DraftSchedule?,
    viewModel: DraftsViewModel,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val existing = schedule?.scheduledInstant?.takeIf { it > Instant.now().plus(DraftsViewModel.reuseExistingTimeMargin) }
    var scheduleDate by remember { mutableStateOf(existing ?: SchedulePolicy.defaultScheduleDate()) }
    var scheduleError by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var reviewConfirmed by remember { mutableStateOf(false) }
    val needsReview = schedule?.status == "needs_review"

    fun run(action: suspend () -> Unit) {
        isSaving = true
        scope.launch {
            try {
                action()
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                scheduleError = e.message
            } finally {
                isSaving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (schedule == null) "Schedule post" else "Reschedule post") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DateTimePickerField(
                    value = scheduleDate,
                    onValueChange = { scheduleDate = it },
                    min = Instant.now().plus(SchedulePolicy.minimumLeadTime),
                    max = Instant.now().plus(Duration.ofDays(viewModel.scheduleMaxAheadDays.toLong())),
                )
                if (needsReview) {
                    Text("Check your LinkedIn posts before retrying: the previous attempt may already have published.", fontSize = 14.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = reviewConfirmed, onCheckedChange = { reviewConfirmed = it })
                        Text("I checked LinkedIn; this post was not published", fontSize = 14.sp)
                    }
                }
                scheduleError?.let { Text(it, color = AppStyle.coral, fontSize = 14.sp) }
                if (schedule != null) {
                    OutlinedButton(enabled = !isSaving, onClick = { run { viewModel.unschedule(draft) } }) {
                        Text("Unschedule", color = AppStyle.coral)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isSaving && (!needsReview || reviewConfirmed),
                onClick = { run { viewModel.reschedule(draft, scheduleDate, reviewConfirmed) } },
            ) { Text(if (isSaving) "Saving…" else if (schedule == null) "Schedule" else "Reschedule") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DraftRow(
    draft: Draft,
    schedule: DraftSchedule?,
    canSchedule: Boolean,
    canEdit: Boolean,
    onOpen: () -> Unit,
    onSchedule: () -> Unit,
    onDelete: () -> Unit,
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
            schedule = schedule,
            modifier = Modifier
                .weight(1f)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onOpen,
                ),
        )

        if (canSchedule) {
            OutlinedButton(onClick = onSchedule, modifier = Modifier.padding(top = 6.dp)) {
                Text(if (schedule == null) "Schedule" else "Reschedule", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Lapsed drafts are read-only; deleting shows the reason instead of doing nothing.
        Box(
            modifier = Modifier
                .size(32.dp)
                .semantics { contentDescription = "Delete draft" }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDelete,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "✕",
                color = AppStyle.muted.copy(alpha = if (canEdit) 1f else 0.35f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DraftContent(draft: Draft, schedule: DraftSchedule?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text = draft.title, color = AppStyle.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            if (draft.status == "published") {
                Text(text = "Published", color = AppStyle.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        schedule?.scheduledInstant?.let { date ->
            Text(text = "Scheduled · ${formatDateTime(date)} · ${schedule.status}", color = AppStyle.muted, fontSize = 12.sp)
        }
        Text(text = draft.body, color = AppStyle.muted, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Text(text = formatDateTime(draft.updatedAt), color = AppStyle.muted, fontSize = 12.sp)
    }
}

/** ContentUnavailableView counterpart. */
@Composable
private fun EmptyDraftsPlaceholder(scheduledOnly: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "📄", fontSize = 30.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (scheduledOnly) "No scheduled posts" else "No drafts",
            color = AppStyle.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (scheduledOnly) "Scheduled posts will appear here." else "Saved drafts will appear here.",
            color = AppStyle.muted,
            fontSize = 15.sp,
        )
    }
}

/** Swift `.formatted(date: .abbreviated, time: .shortened)` — locale-dependent. */
private fun formatDateTime(instant: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .format(instant)
