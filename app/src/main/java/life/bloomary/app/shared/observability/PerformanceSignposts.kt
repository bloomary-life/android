package life.bloomary.app.shared.observability

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Thin android.util.Log wrapper mirroring
 * ios/Shared/Observability/PerformanceSignposts.swift.
 * Emits only counts and durations — never post bodies, tokens, or attachments.
 */
object PerformanceSignposts {
    private const val TAG = "Bloomary.Performance"

    @Volatile
    private var didReportAppLaunch = false

    private val asyncIntervals = ConcurrentHashMap<String, Long>()

    /** Emitted once per process when the root view first appears. */
    fun appLaunchComplete() {
        if (didReportAppLaunch) return
        didReportAppLaunch = true
        Log.i(TAG, "AppLaunchComplete")
    }

    /** Draft cache load: interval duration + draft count log line. */
    fun <T> draftCacheLoad(body: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = body()
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        val count = (result as? life.bloomary.app.features.drafts.domain.DraftSnapshot)?.drafts?.size
        Log.d(TAG, "DraftCacheLoad drafts=$count durationMs=$elapsedMs")
        return result
    }

    /** Synchronous interval measurement (e.g. local draft save). */
    fun <T> measure(name: String, body: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = body()
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        Log.d(TAG, "$name durationMs=$elapsedMs")
        return result
    }

    /** Async interval measurement (e.g. publish attempt duration). */
    fun beginAsyncInterval(name: String): String {
        val token = "$name#${SystemClock.elapsedRealtimeNanos()}"
        asyncIntervals[token] = SystemClock.elapsedRealtimeNanos()
        return token
    }

    fun endAsyncInterval(name: String, token: String) {
        val start = asyncIntervals.remove(token) ?: return
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
        Log.d(TAG, "$name durationMs=$elapsedMs")
    }
}
