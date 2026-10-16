package life.bloomary.app.features.account.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.drop
import life.bloomary.app.features.account.viewmodel.AccountViewModel
import life.bloomary.app.features.linkedinauth.ui.LinkedInProfilePhoto
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.SecondaryButton

/**
 * Mirrors the private AccountDetailView in
 * ios/Features/Account/UI/AccountSettingsView.swift (~line 231): the pushed
 * screen holding log out and the destructive delete-account modal.
 */
@Composable
fun AccountDetailView(
    viewModel: AccountViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val token by viewModel.token.collectAsStateWithLifecycle()
    val statusMessage by viewModel.statusMessage.collectAsStateWithLifecycle()

    var showsDeleteConfirmation by remember { mutableStateOf(false) }
    var deletionReason by remember { mutableStateOf("") }

    // Mirrors .onChange(of: isConnected): dismiss only on a connected →
    // disconnected flip, never for the initial value.
    LaunchedEffect(Unit) {
        viewModel.isConnected.drop(1).collect { connected ->
            if (!connected) onDismiss()
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = AppStyle.paper,
        bottomBar = {
            if (isConnected) {
                SecondaryButton(
                    text = "Delete account",
                    onClick = { showsDeleteConfirmation = true },
                    modifier = Modifier.padding(16.dp),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Row(
                modifier = Modifier.clickable(onClick = onDismiss),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "‹", color = AppStyle.ink, fontSize = 28.sp)
            }

            Text(
                text = "Account",
                color = AppStyle.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AppStyle.panel)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val profile = token?.profile
                if (profile != null) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        LinkedInProfilePhoto(pictureURL = profile.pictureURL, size = 56.dp)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = profile.name,
                                color = AppStyle.ink,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            profile.displayHeadline?.let { headline ->
                                Text(text = headline, color = AppStyle.muted, fontSize = 15.sp)
                            }
                            Text(
                                text = "Registered ${token?.connectedAt?.let(::formatNumericDate) ?: ""}",
                                color = AppStyle.muted,
                                fontSize = 12.sp,
                            )
                        }
                    }

                    SecondaryButton(
                        text = if (viewModel.isLoggingOut) "Logging out..." else "Log out",
                        onClick = { viewModel.logOut() },
                        enabled = !viewModel.isLoggingOut,
                    )
                } else {
                    Text(text = "Not connected.", color = AppStyle.muted)
                }
            }

            statusMessage?.let { message ->
                Text(text = message, color = AppStyle.muted, fontSize = 15.sp)
            }
        }

        if (showsDeleteConfirmation) {
            DeleteConfirmationModal(
                onCancel = {
                    showsDeleteConfirmation = false
                    deletionReason = ""
                },
                onConfirm = {
                    showsDeleteConfirmation = false
                    viewModel.deleteAccount { onDismiss() }
                    deletionReason = ""
                },
                deletionReason = deletionReason,
                onDeletionReasonChange = { deletionReason = it },
            )
        }
    }
}

/**
 * Mirrors the deleteConfirmationModal overlay: 70%-width panel card over a
 * black 28% scrim. The optional "Reason" field is collected but never sent —
 * deliberate parity with the iOS quirk (AccountSettingsView.swift:346).
 */
@Composable
private fun DeleteConfirmationModal(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    deletionReason: String,
    onDeletionReasonChange: (String) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.28f)),
        )
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .width(maxWidth * 0.7f)
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(8.dp),
                    ambientColor = AppStyle.ink.copy(alpha = 0.18f),
                    spotColor = AppStyle.ink.copy(alpha = 0.18f),
                )
                .clip(RoundedCornerShape(8.dp))
                .background(AppStyle.panel)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                text = "Delete account?",
                color = AppStyle.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "This deletes your LinkedIn connection, local drafts, local authorization token, and AI API key. Support, legal, and security records may be retained where required.",
                color = AppStyle.muted,
                fontSize = 15.sp,
            )
            OutlinedTextField(
                value = deletionReason,
                onValueChange = onDeletionReasonChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Reason (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(text = "Cancel", onClick = onCancel)
                SecondaryButton(text = "Delete account", onClick = onConfirm)
            }
        }
    }
}
