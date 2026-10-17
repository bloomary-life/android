package life.bloomary.app.features.linkedinpost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.SecondaryButton

/** Port of ios/Features/LinkedInPost/UI/PostSuccessView.swift. */
@Composable
fun PostSuccessView(
    onViewOnLinkedIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AppStyle.paper)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Posted to LinkedIn",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.ink,
        )
        Text(
            text = "Your post is live.",
            fontSize = 15.sp,
            color = AppStyle.muted,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton(text = "View on LinkedIn", onClick = onViewOnLinkedIn)
            SecondaryButton(text = "Done", onClick = onDismiss)
        }
    }
}
