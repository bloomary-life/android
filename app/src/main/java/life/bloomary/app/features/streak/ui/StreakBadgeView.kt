package life.bloomary.app.features.streak.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.shared.ui.AppStyle

/** Mirrors StreakBadgeView.cardSize and the ImageRenderer scale of 2 (→ 1200×630 PNG). */
object StreakBadgeLayout {
    val cardWidth = 600.dp
    val cardHeight = 315.dp
    const val renderScale = 2f
}

// Color(red: 0.039, green: 0.400, blue: 0.761) — the "in" fallback letters.
private val LinkedInBlue = Color(0.039f, 0.400f, 0.761f)

/**
 * Port of ios/Features/Streak/UI/StreakBadgeView.swift.
 *
 * Shows 5 weekday letters Mon–Fri indexing streak.weekDays — deliberately NOT
 * unified with the Insights streak card's 7 dots. [profileImage] is only
 * supplied for the share render; the on-screen badge keeps the "in" fallback,
 * mirroring `StreakBadgeView(streak: streak)` in PostSuccessView.swift.
 */
@Composable
fun StreakBadgeView(
    streak: Streak,
    profileImage: ImageBitmap? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppStyle.panel, RoundedCornerShape(24.dp))
            .border(1.dp, AppStyle.ink.copy(alpha = 0.12f), RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgeProfilePhoto(profileImage)

            Text(
                text = "Day ${streak.current} 🔥",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = AppStyle.ink,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val weekdayLetters = listOf("M", "T", "W", "T", "F")
            for (index in 0 until 5) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val posted = index < streak.weekDays.size && streak.weekDays[index]
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(
                                if (posted) AppStyle.coral else AppStyle.ink.copy(alpha = 0.12f),
                                CircleShape,
                            ),
                    )
                    Text(
                        text = weekdayLetters[index],
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppStyle.muted,
                    )
                }
            }
        }

        Text(
            text = "tracked with Bloomary",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.muted,
        )
    }
}

@Composable
private fun BadgeProfilePhoto(profileImage: ImageBitmap?) {
    Box(modifier = Modifier.size(64.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            if (profileImage != null) {
                Image(
                    bitmap = profileImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    text = "in",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = LinkedInBlue,
                )
            }
        }

        // Coral ring — applied after the clip, like the SwiftUI overlay.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(3.dp, AppStyle.coral, CircleShape),
        )

        Box(
            modifier = Modifier
                .size(34.dp)
                .align(Alignment.BottomEnd)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "🔥", fontSize = 22.sp)
        }
    }
}

/**
 * Offscreen capture host replacing ImageRenderer: draws the badge into
 * [graphicsLayer] at a fixed density of [StreakBadgeLayout.renderScale] so the
 * recorded bitmap is always 1200×630 regardless of the device. Nothing is drawn
 * to the screen.
 *
 * [profileImage] must already be loaded into state before composing this —
 * iOS awaits the fetch before ImageRenderer runs; here the caller must set the
 * image state and let a frame record before `graphicsLayer.toImageBitmap()`,
 * otherwise the "in" fallback is captured.
 */
@Composable
fun StreakBadgeCapture(
    streak: Streak,
    profileImage: ImageBitmap?,
    graphicsLayer: GraphicsLayer,
) {
    CompositionLocalProvider(LocalDensity provides Density(StreakBadgeLayout.renderScale)) {
        Box(modifier = Modifier.size(0.dp)) {
            Box(
                modifier = Modifier
                    .requiredSize(StreakBadgeLayout.cardWidth, StreakBadgeLayout.cardHeight)
                    .drawWithContent {
                        graphicsLayer.record {
                            this@drawWithContent.drawContent()
                        }
                    },
            ) {
                StreakBadgeView(
                    streak = streak,
                    profileImage = profileImage,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
