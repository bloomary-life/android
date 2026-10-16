package life.bloomary.app.features.feedback.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.bloomary.app.features.feedback.api.FeedbackImageAttachment
import life.bloomary.app.features.feedback.api.FeedbackSenderIdentity
import life.bloomary.app.features.feedback.api.FeedbackService
import life.bloomary.app.features.linkedinauth.model.LinkedInProfile
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.PrimaryButton
import life.bloomary.app.shared.ui.SecondaryButton

private const val MaxAttachments = 5
private const val PrivacyPolicyURL = "https://bloomary.life/privacy/"

/**
 * Port of ios/Features/Feedback/UI/FeedbackView.swift.
 *
 * [profile] is the session profile (LinkedInSessionStore.token?.profile on iOS)
 * used to build the sender identity. [feedbackService] is the feature API
 * service; its send() performs the trim + empty-message validation and throws
 * FeedbackError with the iOS messages.
 */
@Composable
fun FeedbackView(
    feedbackService: FeedbackService,
    profile: LinkedInProfile?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf<List<FeedbackImageAttachment>>(emptyList()) }
    var isSending by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MaxAttachments),
    ) { uris ->
        scope.launch {
            attachments = loadAttachments(context, uris)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppStyle.paper),
    ) {
        // navigationTitle("Feedback") + toolbar Close (topBarTrailing).
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "Feedback",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppStyle.ink,
                modifier = Modifier.align(Alignment.Center),
            )
            Text(
                text = "Close",
                fontSize = 17.sp,
                color = AppStyle.coral,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable(onClick = onClose)
                    .padding(4.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // intro
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppStyle.panel, RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Welcome",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppStyle.ink,
                )
                Text(
                    text = "We're open to your thoughts to make this app bring you more, so don't hesitate to share your ideas, feedback on app work, or whatever you may find insightful.",
                    fontSize = 17.sp,
                    color = AppStyle.muted,
                )
            }

            // form
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("Title", color = AppStyle.muted) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = AppStyle.ink, fontSize = 17.sp),
                )

                BasicTextField(
                    value = message,
                    onValueChange = { message = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(1.dp, AppStyle.ink.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    textStyle = TextStyle(color = AppStyle.ink, fontSize = 17.sp),
                )

                // "photo.on.rectangle.angled" label → emoji counterpart.
                SecondaryButton(
                    text = "🖼️ Attach images",
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )

                if (attachments.isNotEmpty()) {
                    Text(
                        text = "${attachments.size} image${if (attachments.size == 1) "" else "s"} attached",
                        fontSize = 12.sp,
                        color = AppStyle.muted,
                    )
                }

                if (statusMessage.isNotEmpty()) {
                    Text(
                        text = statusMessage,
                        fontSize = 15.sp,
                        color = AppStyle.muted,
                    )
                }

                PrimaryButton(
                    text = if (isSending) "Sending..." else "Send feedback",
                    enabled = !isSending,
                    onClick = {
                        isSending = true
                        statusMessage = ""
                        val identity = profile?.let {
                            FeedbackSenderIdentity(
                                name = it.name,
                                linkedInID = it.memberID,
                                email = it.email,
                            )
                        }
                        scope.launch {
                            try {
                                feedbackService.send(title, message, identity, attachments)
                                statusMessage = "Sent. Confirmation was emailed to you."
                                isSending = false
                                AppTelemetry.track(AppTelemetryEvent.FeedbackSent)
                                title = ""
                                message = ""
                                attachments = emptyList()
                            } catch (e: Exception) {
                                // Mirrors error.localizedDescription.
                                statusMessage = e.message ?: "Feedback send failed."
                                isSending = false
                            }
                        }
                    },
                )
            }

            // links
            Text(
                text = "Privacy policy",
                fontSize = 15.sp,
                color = Color.Black,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PrivacyPolicyURL)))
                },
            )
        }
    }
}

/**
 * Mirrors loadAttachments(from:): bytes are base64-encoded and named
 * feedback-{index+1}.jpg regardless of the source format; failed reads drop
 * the item. The picker enforces max 5; take() is the belt-and-braces mirror of
 * maxSelectionCount for fallback pickers that ignore it.
 */
private suspend fun loadAttachments(
    context: Context,
    uris: List<Uri>,
): List<FeedbackImageAttachment> = withContext(Dispatchers.IO) {
    uris.take(MaxAttachments).mapIndexedNotNull { index, uri ->
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return@mapIndexedNotNull null
        FeedbackImageAttachment(
            filename = "feedback-${index + 1}.jpg",
            content = Base64.encodeToString(bytes, Base64.NO_WRAP),
        )
    }
}
