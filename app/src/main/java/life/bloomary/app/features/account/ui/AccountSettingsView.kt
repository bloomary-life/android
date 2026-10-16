package life.bloomary.app.features.account.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import life.bloomary.app.features.account.viewmodel.AccountViewModel
import life.bloomary.app.features.ai.storage.AISettingsStore
import life.bloomary.app.features.feedback.api.FeedbackService
import life.bloomary.app.features.feedback.ui.FeedbackView
import life.bloomary.app.features.linkedinauth.ui.LinkedInProfilePhoto
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.PrimaryButton
import life.bloomary.app.shared.ui.SecondaryButton

private const val COMMUNITY_URL = "https://t.me/+NDQcpuABme9iYTU0"
private const val PRIVACY_URL = "https://bloomary.life/privacy/"
private const val TERMS_URL = "https://bloomary.life/terms/"

/** Swift `.numeric` date style: abbreviated, locale-dependent, no time. */
internal fun formatNumericDate(instant: Instant): String {
    val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
    return instant.atZone(ZoneId.systemDefault()).format(formatter)
}

/**
 * Mirrors ios/Features/Account/UI/AccountSettingsView.swift.
 *
 * The iOS detail screen is a NavigationLink push; the Android shell has no
 * NavigationStack, so the detail view is swapped in over the list (plus
 * BackHandler), with the same auto-dismiss-on-disconnect behavior inside
 * AccountDetailView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSettingsView(
    viewModel: AccountViewModel,
    feedbackService: FeedbackService?,
    aiSettingsStore: AISettingsStore,
    modifier: Modifier = Modifier,
) {
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val token by viewModel.token.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()
    val aiApiKey by aiSettingsStore.apiKey.collectAsStateWithLifecycle()
    val aiStatusMessage by aiSettingsStore.statusMessage.collectAsStateWithLifecycle()

    var showsFeedback by remember { mutableStateOf(false) }
    var apiKeyDraft by remember { mutableStateOf("") }
    var isEditingAPIKey by remember { mutableStateOf(false) }
    var showsDetail by remember { mutableStateOf(false) }

    val uriHandler = LocalUriHandler.current
    val hasFeedback = feedbackService != null

    LaunchedEffect(Unit) {
        apiKeyDraft = aiSettingsStore.apiKey.value
    }

    if (showsDetail) {
        BackHandler { showsDetail = false }
        AccountDetailView(
            viewModel = viewModel,
            onDismiss = { showsDetail = false },
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppStyle.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = "Settings",
            color = AppStyle.ink,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )

        if (isConnected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppStyle.panel)
                    .border(1.dp, AppStyle.ink.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .clickable { showsDetail = true }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LinkedInProfilePhoto(pictureURL = token?.profile?.pictureURL, size = 48.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = token?.profile?.name ?: "Account",
                        color = AppStyle.ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    token?.connectedAt?.let { connectedAt ->
                        Text(
                            text = "Registered ${formatNumericDate(connectedAt)}",
                            color = AppStyle.muted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(text = "›", color = AppStyle.muted, fontSize = 20.sp)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = if (viewModel.isConnecting) "Connecting..." else "Connect LinkedIn",
                    onClick = { viewModel.connect() },
                    enabled = !viewModel.isConnecting,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "By logging in, you accept Bloomary's",
                        color = AppStyle.muted,
                        fontSize = 12.sp,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Privacy Policy",
                            color = AppStyle.coral,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { uriHandler.openUri(PRIVACY_URL) },
                        )
                        Text(text = "and", color = AppStyle.muted, fontSize = 12.sp)
                        Text(
                            text = "Terms and Conditions",
                            color = AppStyle.coral,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { uriHandler.openUri(TERMS_URL) },
                        )
                        Text(text = ".", color = AppStyle.muted, fontSize = 12.sp)
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(AppStyle.panel)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Gemini API key",
                color = AppStyle.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )

            if (aiApiKey.isEmpty() || isEditingAPIKey) {
                OutlinedTextField(
                    value = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Gemini API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "••••••••",
                        color = AppStyle.ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 17.sp,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Change",
                        color = AppStyle.ink,
                        fontSize = 17.sp,
                        modifier = Modifier.clickable {
                            isEditingAPIKey = true
                            apiKeyDraft = ""
                        },
                    )
                }
            }

            SecondaryButton(
                text = "Save AI settings",
                onClick = {
                    aiSettingsStore.setApiKey(apiKeyDraft)
                    aiSettingsStore.save()
                    isEditingAPIKey = false
                },
            )

            Text(
                text = "Use a Gemini key that Google accepts for Gemini API. Free quota, billing, region, and key restrictions are handled by Google.",
                color = AppStyle.muted,
                fontSize = 12.sp,
            )

            aiStatusMessage?.let { message ->
                Text(text = message, color = AppStyle.muted, fontSize = 15.sp)
            }
        }

        if (isConnected) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppStyle.panel)
                    .border(1.dp, AppStyle.ink.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .clickable { uriHandler.openUri(COMMUNITY_URL) }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "👥 Community",
                    color = AppStyle.ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (hasFeedback) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppStyle.coral)
                        .clickable {
                            showsFeedback = true
                            AppTelemetry.track(AppTelemetryEvent.FeedbackOpened)
                        }
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "🐞 Send feedback",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        statusMessage?.let { message ->
            Text(text = message, color = AppStyle.muted, fontSize = 15.sp)
        }
    }

    if (showsFeedback && feedbackService != null) {
        ModalBottomSheet(
            onDismissRequest = { showsFeedback = false },
            containerColor = AppStyle.panel,
        ) {
            FeedbackView(
                feedbackService = feedbackService,
                profile = token?.profile,
                onClose = { showsFeedback = false },
            )
        }
    }
}
