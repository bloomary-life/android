package life.bloomary.app.shared.storage

import android.content.Context

/** Persists one boolean, so settings stores run in unit tests without a Context. */
interface BooleanSetting {
    fun load(): Boolean
    fun save(value: Boolean)
}

class InMemoryBooleanSetting(private var value: Boolean = false) : BooleanSetting {
    override fun load() = value
    override fun save(value: Boolean) {
        this.value = value
    }
}

class SharedPreferencesBooleanSetting(context: Context, private val key: String) : BooleanSetting {
    private val prefs = context.applicationContext.getSharedPreferences("bloomary_settings", Context.MODE_PRIVATE)
    override fun load() = prefs.getBoolean(key, false)
    override fun save(value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}
