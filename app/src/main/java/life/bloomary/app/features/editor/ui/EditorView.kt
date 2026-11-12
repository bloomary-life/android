package life.bloomary.app.features.editor.ui

import android.content.Intent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import life.bloomary.app.features.editor.viewmodel.EditorViewModel
import life.bloomary.app.features.linkedinauth.ui.LinkedInProfilePhoto
import life.bloomary.app.features.linkedinpost.ui.PostSuccessView
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.DateTimePickerField
import life.bloomary.app.shared.ui.PrimaryButton
import life.bloomary.app.shared.ui.SecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorView(
    viewModel: EditorViewModel,
    onShowDrafts: () -> Unit,
    onPostSucceeded: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val statusMessage by viewModel.statusMessage.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val plan by viewModel.plan.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    val editingDraftID by viewModel.editingDraftID.collectAsState()
    val draftLimitWarning = remember(plan, drafts, editingDraftID) { viewModel.draftLimitWarning }
    var scheduleDate by remember { mutableStateOf(viewModel.defaultScheduleDate()) }
    val imageData by viewModel.editorImageData.collectAsState()
    val imageFilename by viewModel.editorImageFilename.collectAsState()

    val imagePicker = rememberPostImagePicker { attachment ->
        viewModel.setAttachedImage(attachment.data, attachment.filename, attachment.mimeType)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppStyle.paper),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹",
                    color = AppStyle.ink,
                    fontSize = 28.sp,
                    modifier = Modifier
                        .size(28.dp)
                        .clickable(onClick = onShowDrafts),
                )
                Text(
                    text = "Write your post",
                    color = AppStyle.ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            draftLimitWarning?.let { warning ->
                Text(
                    text = "⚠ $warning",
                    color = AppStyle.ink,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppStyle.coral.copy(alpha = 0.1f))
                        .padding(10.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppStyle.panel),
            ) {
                EditorToolbar(
                    isNaturalizing = viewModel.isNaturalizing,
                    enabled = viewModel.hasEditorText && !viewModel.isPosting,
                    onNaturalize = { viewModel.naturalizeEnglish() },
                    onAttachImage = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )

                Text(
                    text = syncStatus.message,
                    color = AppStyle.muted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )

                RichTextEditor(
                    text = viewModel.editorText,
                    onTextChange = { viewModel.editorText = it },
                    hasText = viewModel.hasEditorText,
                    onClear = { viewModel.clearEditor() },
                )
            }

            statusMessage?.let { message ->
                Text(text = message, color = AppStyle.muted, fontSize = 15.sp)
            }

            Text(text = viewModel.postingSuggestions, color = AppStyle.muted, fontSize = 12.sp)

            LinkedInPreview(viewModel)

            AttachedImagePreview(
                data = imageData,
                filename = imageFilename,
                onRemove = { viewModel.removeAttachedImage() },
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AppStyle.paper)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HorizontalDivider(color = AppStyle.ink.copy(alpha = 0.12f))
            // Gate on lapsed, not isPro: trial schedules too. Locked controls stay
            // tappable and explain why instead of going inert.
            SecondaryButton(
                text = if (plan.isLapsed) "Schedule · Pro" else "Schedule",
                enabled = !viewModel.isPosting && !viewModel.isScheduling && (viewModel.hasEditorText || imageData != null),
                onClick = {
                    if (plan.isLapsed) {
                        viewModel.showPlanRequired()
                    } else {
                        scheduleDate = viewModel.defaultScheduleDate()
                        viewModel.scheduleMessage = null
                        viewModel.showsSchedulePicker = true
                    }
                },
            )
            SecondaryButton(
                text = "Save draft",
                enabled = !(draftLimitWarning != null && !plan.isLapsed),
                onClick = {
                    if (viewModel.save()) onShowDrafts()
                },
            )
            PrimaryButton(
                text = if (viewModel.isPosting) "Posting..." else "Post to LinkedIn",
                enabled = !viewModel.isPosting && !viewModel.isNaturalizing,
                onClick = { if (plan.isLapsed) viewModel.showPlanRequired() else viewModel.publish() },
            )
        }
    }

    if (viewModel.showsPublishRetryConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.showsPublishRetryConfirmation = false },
            title = { Text("Check LinkedIn before posting again") },
            text = { Text("This content was already submitted. It may have published even if Bloomary did not receive a response.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.showsPublishRetryConfirmation = false
                    viewModel.publish(confirmRetry = true)
                }) { Text("I checked · Post again", color = AppStyle.coral) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        viewModel.showsPublishRetryConfirmation = false
                        context.startActivity(Intent(Intent.ACTION_VIEW, LINKEDIN_RECENT_ACTIVITY.toUri()))
                    }) { Text("Open LinkedIn") }
                    TextButton(onClick = { viewModel.showsPublishRetryConfirmation = false }) { Text("Cancel") }
                }
            },
        )
    }

    if (viewModel.showsAIConsent) {
        AlertDialog(
            onDismissRequest = { viewModel.showsAIConsent = false },
            title = { Text("Send this draft to Google Gemini?") },
            text = {
                Text(
                    "Naturalize English sends the text of this draft to Google Gemini with your Gemini API key. " +
                        "Google processes it under its own terms. Bloomary asks once; deleting your account resets this choice.",
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.allowAIAndNaturalize() }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { viewModel.showsAIConsent = false }) { Text("Cancel") } },
        )
    }

    if (viewModel.showsSchedulePicker) {
        AlertDialog(
            onDismissRequest = { viewModel.showsSchedulePicker = false },
            title = { Text("Schedule post") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateTimePickerField(
                        value = scheduleDate,
                        onValueChange = { scheduleDate = it },
                        min = viewModel.earliestScheduleDate(),
                        max = viewModel.latestScheduleDate(),
                    )
                    Text(text = viewModel.scheduleLimitText, color = AppStyle.muted, fontSize = 12.sp)
                    viewModel.scheduleMessage?.let { Text(text = it, color = AppStyle.coral, fontSize = 14.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = !viewModel.isScheduling, onClick = { viewModel.schedule(scheduleDate) }) {
                    Text(if (viewModel.isScheduling) "Scheduling…" else "Schedule")
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.showsSchedulePicker = false }) { Text("Cancel") } },
        )
    }

    if (viewModel.showsPostSuccess) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.showsPostSuccess = false
                onPostSucceeded()
            },
            containerColor = AppStyle.panel,
        ) {
            PostSuccessView(
                onViewOnLinkedIn = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, LINKEDIN_RECENT_ACTIVITY.toUri()))
                },
                onDismiss = {
                    viewModel.showsPostSuccess = false
                    onPostSucceeded()
                },
            )
        }
    }
}

private const val LINKEDIN_RECENT_ACTIVITY = "https://www.linkedin.com/in/me/recent-activity/all/"

@Composable
private fun EditorToolbar(
    isNaturalizing: Boolean,
    enabled: Boolean,
    onNaturalize: () -> Unit,
    onAttachImage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarText("B")
            ToolbarText("I")
            ToolbarText("S")
            ToolbarText("U")
            ToolbarText("<>")
            ToolbarText("T/")
        }
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarText(if (isNaturalizing) "..." else "✨", enabled = enabled, onClick = onNaturalize)
            ToolbarText("😀")
            ToolbarText("🖼️", onClick = onAttachImage)
            ToolbarText("•")
            ToolbarText("1.")
        }
        HorizontalDivider(color = AppStyle.ink.copy(alpha = 0.12f))
    }
}

@Composable
private fun ToolbarText(
    text: String,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Text(
        text = text,
        color = if (enabled) AppStyle.ink.copy(alpha = 0.72f) else AppStyle.muted,
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .size(44.dp)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .padding(10.dp),
    )
}

@Composable
private fun LinkedInPreview(viewModel: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .border(1.dp, AppStyle.ink.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val profile = viewModel.profile
        if (profile != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LinkedInProfilePhoto(pictureURL = profile.pictureURL, size = 48.dp)
                Column {
                    Text(text = profile.name, color = AppStyle.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(text = profile.displayHeadline ?: "Now", color = AppStyle.muted, fontSize = 15.sp)
                }
            }
        } else {
            Text(text = "Connect LinkedIn to preview your profile.", color = AppStyle.muted, fontSize = 15.sp)
        }
        Text(text = viewModel.previewText, color = AppStyle.ink, fontSize = 16.sp)
    }
}
