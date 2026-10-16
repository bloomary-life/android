package life.bloomary.app.application.config

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import life.bloomary.app.shared.network.AppJson

/**
 * Mirrors ios/Application/Config/AppConfiguration.swift: decodes the bundled
 * `assets/app_configuration.json` (same keys/values as AppConfiguration.plist)
 * and falls back to hardcoded defaults on any error.
 */
@Serializable
data class AppConfiguration(
    val api: API,
    val linkedIn: LinkedIn,
    val storage: Storage,
    val observability: Observability = Observability.disabled,
) {
    @Serializable
    data class API(
        @SerialName("baseURL") val baseURL: String,
    ) {
        init {
            require(baseURL.startsWith("https://") || baseURL.startsWith("http://")) {
                "Invalid baseURL"
            }
        }
    }

    @Serializable
    data class LinkedIn(
        val callbackURLScheme: String,
    )

    @Serializable
    data class Storage(
        val draftsKey: String,
        val linkedInTokenKey: String,
    )

    @Serializable
    data class Observability(
        val sentryDSN: String,
        val sentryEnvironment: String,
        val sentryTracesSampleRate: Double,
        val telemetryDeckAppID: String,
        val telemetryDeckNamespace: String,
    ) {
        companion object {
            val disabled = Observability(
                sentryDSN = "",
                sentryEnvironment = "production",
                sentryTracesSampleRate = 0.1,
                telemetryDeckAppID = "",
                telemetryDeckNamespace = "",
            )
        }
    }

    companion object {
        private const val ASSET_NAME = "app_configuration.json"

        val fallback = AppConfiguration(
            api = API(baseURL = "https://api.bloomary.life"),
            linkedIn = LinkedIn(callbackURLScheme = "bloomary"),
            storage = Storage(
                draftsKey = "bloomary_drafts",
                linkedInTokenKey = "bloomary_linkedin_token",
            ),
            observability = Observability.disabled,
        )

        fun load(context: Context): AppConfiguration {
            return try {
                val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
                AppJson.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                fallback
            }
        }
    }
}
