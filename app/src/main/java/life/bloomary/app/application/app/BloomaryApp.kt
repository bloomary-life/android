package life.bloomary.app.application.app

import android.app.Application
import life.bloomary.app.application.config.AppConfiguration
import life.bloomary.app.application.di.AppContainer
import life.bloomary.app.shared.observability.AppTelemetry
import life.bloomary.app.shared.observability.AppTelemetryEvent
import life.bloomary.app.shared.observability.CrashReporter

/**
 * Boot order mirrors ios/Application/App/BloomaryApp.swift exactly:
 * CrashReporter.start → AppConfiguration.load → AppTelemetry.start →
 * track(appOpened) → AppContainer.
 */
class BloomaryApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.start(this)
        val configuration = AppConfiguration.load(this)
        AppTelemetry.start(this, configuration.observability)
        AppTelemetry.track(AppTelemetryEvent.AppOpened)
        container = AppContainer(configuration, this)
    }
}
