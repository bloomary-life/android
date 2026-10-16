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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import life.bloomary.app.features.streak.ui.PostSuccessView
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.PrimaryButton
import life.bloomary.app.shared.ui.SecondaryButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorView(
    viewModel: EditorViewModel,
    onShowDrafts: () -> Unit,
    onPostSucceeded: () -> Unit,
    onShareStreak: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val statusMessage by viewModel.statusMessage.collectAsState()
    val isPaywalled by viewModel.isPaywalled.collectAsState()
    val imageData by viewModel.editorImageData.collectAsState()
    val imageFilename by viewModel.editorImageFilename.collectAsState()
    var didShareStreak by remember { mutableStateOf(false) }

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

            if (isPaywalled) {
                PaywallBanner()
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
                    addStreakFooter = viewModel.addStreakFooter,
                    onToggleStreakFooter = {
                        viewModel.addStreakFooter = !viewModel.addStreakFooter
                        viewModel.streakFooterChanged()
                    },
                )

                RichTextEditor(
                    text = viewModel.editorText,
                    onTextChange = { viewModel.editorText = it },
                    hasText = viewModel.hasEditorText,
                    onClear = { viewModel.clearEditor() },
                    enabled = !isPaywalled,
                )
            }

            statusMessage?.let { message ->
                Text(text = message, color = AppStyle.muted, fontSize = 15.sp)
            }

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
            SecondaryButton(
                text = "Save draft",
                enabled = !isPaywalled,
                onClick = {
                    viewModel.save()
                    onShowDrafts()
                },
            )
            PrimaryButton(
                text = if (viewModel.isPosting) "Posting..." else "Post to LinkedIn",
                enabled = !isPaywalled && !viewModel.isPosting && !viewModel.isNaturalizing,
                onClick = { viewModel.publish() },
            )
        }
    }

    if (viewModel.showsPostSuccess) {
        ModalBottomSheet(
            onDismissRequest = {
                viewModel.showsPostSuccess = false
                if (didShareStreak) {
                    didShareStreak = false
                } else {
                    onPostSucceeded()
                }
            },
            containerColor = AppStyle.panel,
        ) {
            PostSuccessView(
                streak = viewModel.publishedStreak,
                profilePictureURL = viewModel.profile?.pictureURL,
                onShareBadge = { badgeImage ->
                    didShareStreak = true
                    viewModel.startPostWithStreakBadge(badgeImage)
                    onShareStreak()
                },
                onViewOnLinkedIn = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, "https://www.linkedin.com/in/me/recent-activity/all/".toUri()))
                },
                onDismiss = {
                    viewModel.showsPostSuccess = false
                    if (didShareStreak) {
                        didShareStreak = false
                    } else {
                        onPostSucceeded()
                    }
                },
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
private fun EditorToolbar(
    isNaturalizing: Boolean,
    enabled: Boolean,
    onNaturalize: () -> Unit,
    onAttachImage: () -> Unit,
    addStreakFooter: Boolean,
    onToggleStreakFooter: () -> Unit,
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
            ToolbarText(if (addStreakFooter) "🔥✓" else "🔥", onClick = onToggleStreakFooter)
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
