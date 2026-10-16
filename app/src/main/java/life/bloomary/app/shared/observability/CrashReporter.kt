package life.bloomary.app.shared.observability

import android.content.Context
import android.content.pm.PackageManager
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

/**
 * Mirrors ios/Shared/Observability/CrashReporter.swift: DSN/environment/sample
 * rate come from manifest meta-data (the Android Info.plist equivalent), and a
 * blank DSN disables reporting entirely.
 */
object CrashReporter {

    fun start(context: Context) {
        val meta = try {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } ?: return

        val dsn = meta.getString("SentryDSN").orEmpty()
        val environment = meta.getString("SentryEnvironment") ?: "production"
        val sampleRate = meta.get("SentryTracesSampleRate").toDoubleOrNullSafe() ?: 0.1
        start(context, dsn = dsn, environment = environment, tracesSampleRate = sampleRate)
    }

    fun start(
        context: Context,
        dsn: String,
        environment: String,
        tracesSampleRate: Double,
    ) {
        val trimmedDsn = dsn.trim()
        if (trimmedDsn.isEmpty()) return

        SentryAndroid.init(context.applicationContext) { options ->
            options.dsn = trimmedDsn
            options.environment = environment
            options.tracesSampleRate = tracesSampleRate
            options.isSendDefaultPii = false
            // Closest Android equivalents of the iOS crash/app-hang/performance flags.
            options.isAnrEnabled = true
            options.isEnableAutoActivityLifecycleTracing = true
        }
    }

    fun captureMessage(message: String, level: SentryLevel = SentryLevel.ERROR) {
        io.sentry.Sentry.captureMessage(message, level)
    }

    private fun Any?.toDoubleOrNullSafe(): Double? = when (this) {
        is Double -> this
        is Float -> toDouble()
        is Int -> toDouble()
        is String -> toDoubleOrNull()
        else -> null
    }
}
