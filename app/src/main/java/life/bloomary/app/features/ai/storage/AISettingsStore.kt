package life.bloomary.app.features.ai.storage

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Mirrors ios/Features/AI/Storage/AISettingsStore.swift.
 */
data class AISettings(
    val apiKey: String,
)

interface AIAPIKeyStore {
    fun load(): String?
    fun save(apiKey: String): Boolean
    fun clear(): Boolean
}

class AISettingsStore(
    private val keyStore: AIAPIKeyStore,
) {
    private val _apiKey = MutableStateFlow(keyStore.load() ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    val current: AISettings
        get() = AISettings(apiKey = _apiKey.value)

    fun setApiKey(value: String) {
        _apiKey.value = value
    }

    fun save() {
        val trimmedKey = _apiKey.value.trim()
        _apiKey.value = trimmedKey
        val saved = if (trimmedKey.isEmpty()) {
            keyStore.clear()
        } else {
            keyStore.save(trimmedKey)
        }
        _statusMessage.value = if (saved) "AI settings saved." else "AI key could not be saved."
    }

    fun clearAPIKey() {
        keyStore.clear()
        _apiKey.value = ""
    }

    fun clearAccountDeletionData() {
        clearAPIKey()
    }
}

/**
 * Keystore-backed equivalent of KeychainAIAPIKeyStore (Tink AEAD with an
 * Android Keystore master key; the master key never leaves the device,
 * matching iOS `ThisDeviceOnly`).
 *
 * Keychain coordinates mirrored as storage identifiers:
 * service "com.dailymind.bloomary.ai", account/key "ai-api-key".
 */
class KeystoreAIAPIKeyStore(
    context: Context,
) : AIAPIKeyStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val aead: Aead? by lazy {
        try {
            AeadConfig.register()
            AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, PREFS_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun load(): String? {
        val encoded = prefs.getString(KEY, null) ?: return null
        return try {
            val ciphertext = Base64.getDecoder().decode(encoded)
            String(aead?.decrypt(ciphertext, null) ?: return null, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun save(apiKey: String): Boolean {
        return try {
            val ciphertext = aead?.encrypt(apiKey.toByteArray(Charsets.UTF_8), null) ?: return false
            prefs.edit().putString(KEY, Base64.getEncoder().encodeToString(ciphertext)).commit()
        } catch (e: Exception) {
            false
        }
    }

    override fun clear(): Boolean {
        return try {
            prefs.edit().remove(KEY).commit()
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val PREFS_FILE = "com.dailymind.bloomary.ai"
        private const val KEYSET_NAME = "bloomary_ai_keyset"
        private const val MASTER_KEY_ALIAS = "bloomary_ai_master_key"
        private const val KEY = "ai-api-key"
    }
}
