package life.bloomary.app.features.insights.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import life.bloomary.app.features.streak.model.Streak
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.ui.AppStyle

private data class Insight(
    val topline: String,
    val title: String,
    val summary: String,
    val url: String,
)

// Verbatim copy of the hardcoded success stories in InsightsView.swift.
private val Insights = listOf(
    Insight(
        topline = "$2M revenue",
        title = "Christian Krause / Quota League",
        summary = "Daily LinkedIn posts helped build $2M revenue and a coaching business.",
        url = "https://www.linkedin.com/feed/update/urn%3Ali%3Aactivity%3A7340347428467843072/",
    ),
    Insight(
        topline = "$100K+ MRR pipeline",
        title = "Alyona Mysko",
        summary = "Founder-led LinkedIn posts created $100K+ in measurable MRR pipeline.",
        url = "https://www.linkedin.com/feed/update/urn%3Ali%3Aactivity%3A7379153226815795200/",
    ),
    Insight(
        topline = "600+ leads",
        title = "Jim Taylor",
        summary = "Consistent LinkedIn content produced 600+ leads without ads or cold outreach.",
        url = "https://www.linkedin.com/feed/update/urn%3Ali%3Aactivity%3A7465096268839628800/",
    ),
    Insight(
        topline = "2x in 60 days",
        title = "Lauren Tickner",
        summary = "Buyer-focused LinkedIn content helped a client double revenue in 60 days.",
        url = "https://www.linkedin.com/feed/update/urn%3Ali%3Aactivity%3A7479866952899956736/",
    ),
    Insight(
        topline = "3x client income",
        title = "Trisha Paul",
        summary = "Profile and post clarity helped turn LinkedIn into 3x client income.",
        url = "https://www.linkedin.com/feed/update/urn%3Ali%3Aactivity%3A7343299040001851394/",
    ),
    Insight(
        topline = "7 inbound leads/week",
        title = "Rita Kerola",
        summary = "Clear positioning helped turn LinkedIn into 7 inbound leads per week.",
        url = "https://www.linkedin.com/feed/update/urn%3Ali%3Aactivity%3A7465280725379039232/",
    ),
)

/**
 * Port of ios/Features/Insights/UI/InsightsView.swift.
 *
 * The SwiftUI view takes the shared EditorViewModel; here the two values it
 * reads are passed explicitly: [knownStreak] (EditorViewModel.knownStreak) and
 * [onLoadStreak] (EditorViewModel.loadStreak, run once per appearance like
 * SwiftUI's `.task`).
 */
@Composable
fun InsightsView(
    knownStreak: Streak?,
    onLoadStreak: suspend () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        onLoadStreak()
    }

    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppStyle.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (knownStreak != null) {
            StreakCard(knownStreak)
        }

        Text(
            text = "Public LinkedIn signals",
            fontSize = 12.sp,
            color = AppStyle.muted,
        )

        Text(
            text = "People turn their lived work into visible opportunity.",
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.ink,
        )

        Insights.forEach { insight ->
            InsightCard(
                insight = insight,
                onClick = { openInsight(context, insight.url) },
            )
        }
    }
}

private fun openInsight(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    try {
        context.startActivity(intent)
        // Mirrors UIApplication.open completion handler: track only when opened.
        AppTelemetry.track(AppTelemetryEvent.InsightOpened)
    } catch (e: ActivityNotFoundException) {
        // No handler installed — iOS simply does not track in that case.
    }
}

@Composable
private fun StreakCard(streak: Streak) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppStyle.panel, RoundedCornerShape(8.dp))
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // SF Symbol "flame.fill" → emoji counterpart (same convention as tab icons).
        Text(text = "🔥", fontSize = 44.sp)

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Your streak",
                fontSize = 15.sp,
                color = AppStyle.muted,
            )
            Text(
                text = "${streak.current} ${if (streak.current == 1) "day" else "days"}",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = AppStyle.ink,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // 7 dots labelled M T W T F S S indexing streak.weekDays — deliberately
        // NOT unified with StreakBadgeView's 5 Mon–Fri letters.
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (index in 0 until 7) {
                    val posted = index < streak.weekDays.size && streak.weekDays[index]
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                if (posted) AppStyle.lime else AppStyle.ink.copy(alpha = 0.12f),
                                CircleShape,
                            ),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { letter ->
                    Text(
                        text = letter,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppStyle.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.width(10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightCard(insight: Insight, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppStyle.panel, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = insight.topline,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.coral,
        )
        Text(
            text = insight.title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppStyle.ink,
        )
        Text(
            text = insight.summary,
            fontSize = 15.sp,
            color = AppStyle.muted,
        )
    }
}
