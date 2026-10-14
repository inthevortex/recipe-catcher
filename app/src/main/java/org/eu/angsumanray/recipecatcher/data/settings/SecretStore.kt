package org.eu.angsumanray.recipecatcher.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secretDataStore: DataStore<Preferences> by preferencesDataStore(name = "secret_prefs")

@Singleton
class SecretStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "recipe_catcher_master_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128

        val KEY_API_KEY_CIPHERTEXT = stringPreferencesKey("anthropic_api_key_encrypted")
    }

    private var jvmFallbackKey: SecretKey? = null

    private val keyStore: KeyStore? = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    private fun getOrCreateSecretKey(): SecretKey {
        if (keyStore != null) {
            try {
                if (keyStore.containsAlias(KEY_ALIAS)) {
                    val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                    if (entry != null) return entry.secretKey
                }

                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()

                keyGenerator.init(spec)
                return keyGenerator.generateKey()
            } catch (e: Exception) {
                // If AndroidKeyStore operation fails in test environment, fall through to fallback
            }
        }

        // JVM test fallback when AndroidKeyStore is unavailable
        if (jvmFallbackKey == null) {
            val keyGenerator = KeyGenerator.getInstance("AES")
            keyGenerator.init(256)
            jvmFallbackKey = keyGenerator.generateKey()
        }
        return jvmFallbackKey!!
    }

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isBlank()) return ""
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (combined.size <= GCM_IV_LENGTH_BYTES) return ""

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        val ciphertext = ByteArray(combined.size - GCM_IV_LENGTH_BYTES)
        System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH_BYTES)
        System.arraycopy(combined, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertext.size)

        val secretKey = getOrCreateSecretKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    suspend fun saveApiKey(apiKey: String) {
        val encrypted = encrypt(apiKey.trim())
        context.secretDataStore.edit { prefs ->
            prefs[KEY_API_KEY_CIPHERTEXT] = encrypted
        }
    }

    suspend fun getApiKey(): String {
        val encrypted = context.secretDataStore.data.map { prefs ->
            prefs[KEY_API_KEY_CIPHERTEXT]
        }.firstOrNull().orEmpty()
        return try {
            decrypt(encrypted)
        } catch (e: Exception) {
            ""
        }
    }
}
