package life.bloomary.app.features.linkedinauth.storage

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.util.Base64
import life.bloomary.app.features.linkedinauth.model.LinkedInToken
import life.bloomary.app.shared.network.AppJson

/**
 * Mirrors the `LinkedInTokenStore` protocol in
 * ios/Features/LinkedInAuth/Storage/AuthTokenStore.swift.
 */
interface LinkedInTokenStore {
    fun load(): LinkedInToken?
    fun save(token: LinkedInToken)
    fun clear()

    fun clearAccountDeletionData() {
        clear()
    }
}

/**
 * Keystore-backed equivalent of the Keychain `AuthTokenStore` (Tink AEAD with an
 * Android Keystore master key, same approach as `KeystoreAIAPIKeyStore`).
 *
 * Keychain coordinates mirrored as storage identifiers:
 * service "com.dailymind.bloomary.linkedin", account = [key].
 *
 * `load()` returns null on any decode/decrypt failure, mirroring the Swift `try?`.
 */
class AuthTokenStore(
    context: Context,
    private val key: String,
) : LinkedInTokenStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(SERVICE, Context.MODE_PRIVATE)

    private val aead: Aead? by lazy {
        try {
            AeadConfig.register()
            AndroidKeysetManager.Builder()
                .withSharedPref(appContext, KEYSET_NAME, SERVICE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://$MASTER_KEY_ALIAS")
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun load(): LinkedInToken? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            val ciphertext = Base64.getDecoder().decode(encoded)
            val plaintext = aead?.decrypt(ciphertext, null) ?: return null
            AppJson.decodeFromString(LinkedInToken.serializer(), String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    override fun save(token: LinkedInToken) {
        try {
            val plaintext = AppJson.encodeToString(LinkedInToken.serializer(), token)
            val ciphertext = aead?.encrypt(plaintext.toByteArray(Charsets.UTF_8), null) ?: return
            prefs.edit().putString(key, Base64.getEncoder().encodeToString(ciphertext)).commit()
        } catch (e: Exception) {
            // Swift silently ignores encode/write failures; mirror that.
        }
    }

    override fun clear() {
        prefs.edit().remove(key).commit()
    }

    override fun clearAccountDeletionData() {
        // iOS additionally deletes iCloud-synchronizable copies of the item.
        // Android Keystore/Tink keysets never leave the device, so clearing the
        // local entry is the complete equivalent.
        clear()
    }

    companion object {
        private const val SERVICE = "com.dailymind.bloomary.linkedin"
        private const val KEYSET_NAME = "bloomary_linkedin_keyset"
        private const val MASTER_KEY_ALIAS = "bloomary_linkedin_master_key"
    }
}
