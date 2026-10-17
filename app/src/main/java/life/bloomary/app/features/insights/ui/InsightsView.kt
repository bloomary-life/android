package life.bloomary.app.features.insights.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 */
@Composable
fun InsightsView(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppStyle.paper)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
