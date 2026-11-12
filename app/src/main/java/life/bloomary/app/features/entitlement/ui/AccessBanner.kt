package life.bloomary.app.features.entitlement.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import life.bloomary.app.shared.ui.AppStyle

/** Access copy from `docs/product/tiers.md` "Copy — lapsed banners". Mirrors ios/Features/Entitlement/UI/AccessBanner.swift. */
object AccessBannerCopy {
    const val publishNeedsPlan = "Publishing needs an active plan."

    /** Rows 1–2: why the archive is read-only, so "trial ended" never reads as "subscription ended". */
    fun lapsed(subscriptionSource: String?, endedOn: Instant?): String {
        val grant = if (subscriptionSource in setOf("apple", "paddle")) "Your subscription" else "Your 14-day trial"
        val ended = endedOn?.let { " on ${formatted(it)}" } ?: ""
        return "$grant ended$ended. Your drafts are read-only."
    }

    /** Row 6: posts scheduled before the lapse still fire. */
    fun scheduledStillPublish(lapsedOn: Instant): String =
        "Posts you scheduled before ${formatted(lapsedOn)} will still publish."

    fun formatted(date: Instant): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(date)
}

/**
 * Persistent, non-dismissible access banner: reason and what is locked. The
 * iOS banner adds a "See plans" action that opens the App Store purchase;
 * Android has no in-app purchase yet (Play Billing needs backend verification),
 * and Google Play does not allow linking out to a web payment page, so the
 * banner has no action.
 */
@Composable
fun AccessBanner(
    message: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(AppStyle.paper)
            .background(AppStyle.coral.copy(alpha = 0.1f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = message, color = AppStyle.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(text = it, color = AppStyle.muted, fontSize = 12.sp) }
        }
    }
}
