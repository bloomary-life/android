package life.bloomary.app.features.streak.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.ui.AppStyle
import life.bloomary.app.shared.ui.PrimaryButton
import life.bloomary.app.shared.ui.SecondaryButton
import okhttp3.OkHttpClient
import okhttp3.Request

/** Mirrors streakPostSuggestionDays in PostSuccessView.swift. */
private val StreakPostSuggestionDays = setOf(7, 30, 50, 100)

/**
 * Port of ios/Features/Streak/UI/PostSuccessView.swift.
 *
 * [onShareBadge] receives the rendered 1200×630 badge (ImageBitmap, PNG-encoded
 * by the caller — EditorViewModel.startPostWithStreakBadge stores
 * "bloomary-streak-badge.png" / "image/png"). The caller is also responsible
 * for the tab jump to Drafts (iOS does it in EditorView's onShareStreak).
 */
@Composable
fun PostSuccessView(
    streak: Streak?,
    profilePictureURL: String?,
    onShareBadge: (ImageBitmap) -> Unit,
    onViewOnLinkedIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val badgeLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()

    var isSharing by remember { mutableStateOf(false) }
    var shareProfileImage by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        AppTelemetry.track(AppTelemetryEvent.StreakViewed)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
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

        if (streak != null) {
            // Card is laid out at 600×315 and displayed at scale 0.5 (300×157.5),
            // mirroring the nested .frame/.scaleEffect in PostSuccessView.swift.
            Box(modifier = Modifier.size(300.dp, 157.5.dp)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .requiredSize(StreakBadgeLayout.cardWidth, StreakBadgeLayout.cardHeight)
                        .scale(0.5f),
                ) {
                    StreakBadgeView(streak = streak)
                }
            }
        } else {
            Text(
                text = "Your post is live.",
                fontSize = 15.sp,
                color = AppStyle.muted,
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (streak != null && streak.current in StreakPostSuggestionDays) {
                PrimaryButton(
                    text = if (isSharing) "Saving draft..." else "Save streak draft",
                    enabled = !isSharing,
                    onClick = {
                        val current = streak
                        isSharing = true
                        scope.launch {
                            // Preload the profile Bitmap into state BEFORE capturing;
                            // wait for the recomposition + draw pass to record it into
                            // the layer, otherwise the badge captures the "in" fallback.
                            shareProfileImage = loadBadgeProfileImage(profilePictureURL)
                            withFrameNanos { }
                            withFrameNanos { }
                            val image = badgeLayer.toImageBitmap()
                            onShareBadge(image)
                            isSharing = false
                            onDismiss()
                        }
                    },
                )
            }

            SecondaryButton(text = "View on LinkedIn", onClick = onViewOnLinkedIn)

            SecondaryButton(text = "Done", onClick = onDismiss)
        }
    }

    if (streak != null) {
        StreakBadgeCapture(
            streak = streak,
            profileImage = shareProfileImage,
            graphicsLayer = badgeLayer,
        )
    }
}

/**
 * Mirrors loadProfileImage() in PostSuccessView.swift: User-Agent Bloomary/1.0,
 * 8 s timeout, nil on any failure. iOS uses returnCacheDataElseLoad with the
 * shared URLSession cache; OkHttp here has no cache configured, so every share
 * refetches — noted deviation, user-visible behavior is unchanged.
 */
private suspend fun loadBadgeProfileImage(profilePictureURL: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (profilePictureURL.isNullOrEmpty()) return@withContext null
        try {
            val client = OkHttpClient.Builder()
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(profilePictureURL)
                .header("User-Agent", "Bloomary/1.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }
