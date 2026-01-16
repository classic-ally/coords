package sh.bentley.transponder

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import uniffi.transponder_core.Identity
import uniffi.transponder_core.getIdentityColor
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class IdentityStore(context: Context) {

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val prefs: SharedPreferences =
        context.getSharedPreferences("transponder_identity", Context.MODE_PRIVATE)

    private val secretKey: SecretKey
        get() {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
                )
                keyGenerator.init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
                keyGenerator.generateKey()
            }
            return keyStore.getKey(KEY_ALIAS, null) as SecretKey
        }

    private fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        return cipher.iv + cipher.doFinal(data)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = data.sliceArray(0 until 12)
        val ciphertext = data.sliceArray(12 until data.size)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun putEncrypted(key: String, value: ByteArray) {
        val encrypted = encrypt(value)
        prefs.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    private fun getEncrypted(key: String): ByteArray? {
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            decrypt(Base64.decode(encoded, Base64.NO_WRAP))
        } catch (e: Exception) {
            null
        }
    }

    fun hasIdentity(): Boolean = prefs.contains(KEY_ED25519_PRIVATE)

    fun getIdentity(): Identity? {
        if (!hasIdentity()) return null

        return Identity(
            ed25519Private = getEncrypted(KEY_ED25519_PRIVATE) ?: return null,
            ed25519Public = getEncrypted(KEY_ED25519_PUBLIC) ?: return null,
            x25519Private = getEncrypted(KEY_X25519_PRIVATE) ?: return null,
            x25519Public = getEncrypted(KEY_X25519_PUBLIC) ?: return null
        )
    }

    fun saveIdentity(identity: Identity) {
        putEncrypted(KEY_ED25519_PRIVATE, identity.ed25519Private)
        putEncrypted(KEY_ED25519_PUBLIC, identity.ed25519Public)
        putEncrypted(KEY_X25519_PRIVATE, identity.x25519Private)
        putEncrypted(KEY_X25519_PUBLIC, identity.x25519Public)
    }

    fun clearIdentity() {
        prefs.edit().clear().apply()
    }

    // User metadata (non-secret, stored in plain text)
    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    var serverUrl: String?
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    /** Migrate server URL from old domain to new domain if it matches */
    fun migrateServerUrl(oldDomain: String, newDomain: String) {
        val current = serverUrl ?: return
        if (current.contains(oldDomain)) {
            val newUrl = current.replace(oldDomain, newDomain)
            serverUrl = newUrl
            println("Migrated user server URL to $newUrl")
        }
    }

    /** Whether to automatically share location (background sync + on app open). */
    var autoShareEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SHARE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SHARE_ENABLED, value).apply()

    /** The user's marker color (what others see). Computed once from identity. */
    val myColor: String? by lazy {
        getIdentity()?.let { getIdentityColor(it) }
    }

    // Location settings (advanced)
    var locationActiveTimeoutMs: Long
        get() = prefs.getLong(KEY_LOCATION_ACTIVE_TIMEOUT_MS, DEFAULT_LOCATION_ACTIVE_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_LOCATION_ACTIVE_TIMEOUT_MS, value).apply()

    var locationActiveAccuracyThresholdM: Float
        get() = prefs.getFloat(KEY_LOCATION_ACTIVE_ACCURACY_M, DEFAULT_LOCATION_ACTIVE_ACCURACY_M)
        set(value) = prefs.edit().putFloat(KEY_LOCATION_ACTIVE_ACCURACY_M, value).apply()

    var locationPassiveMaxAgeMs: Long
        get() = prefs.getLong(KEY_LOCATION_PASSIVE_MAX_AGE_MS, DEFAULT_LOCATION_PASSIVE_MAX_AGE_MS)
        set(value) = prefs.edit().putLong(KEY_LOCATION_PASSIVE_MAX_AGE_MS, value).apply()

    var locationPassiveAccuracyThresholdM: Float
        get() = prefs.getFloat(KEY_LOCATION_PASSIVE_ACCURACY_M, DEFAULT_LOCATION_PASSIVE_ACCURACY_M)
        set(value) = prefs.edit().putFloat(KEY_LOCATION_PASSIVE_ACCURACY_M, value).apply()

    companion object {
        private const val KEY_ALIAS = "transponder_master"
        private const val KEY_ED25519_PRIVATE = "ed25519_private"
        private const val KEY_ED25519_PUBLIC = "ed25519_public"
        private const val KEY_X25519_PRIVATE = "x25519_private"
        private const val KEY_X25519_PUBLIC = "x25519_public"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_AUTO_SHARE_ENABLED = "auto_share_enabled"
        private const val DEFAULT_SERVER_URL = "https://coord.is"

        // Location settings keys and defaults
        private const val KEY_LOCATION_ACTIVE_TIMEOUT_MS = "location_active_timeout_ms"
        private const val KEY_LOCATION_ACTIVE_ACCURACY_M = "location_active_accuracy_m"
        private const val KEY_LOCATION_PASSIVE_MAX_AGE_MS = "location_passive_max_age_ms"
        private const val KEY_LOCATION_PASSIVE_ACCURACY_M = "location_passive_accuracy_m"

        const val DEFAULT_LOCATION_ACTIVE_TIMEOUT_MS = 10_000L
        const val DEFAULT_LOCATION_ACTIVE_ACCURACY_M = 40f  // Early return if ≤40m accuracy
        const val DEFAULT_LOCATION_PASSIVE_MAX_AGE_MS = 5 * 60 * 1000L  // 5 minutes
        const val DEFAULT_LOCATION_PASSIVE_ACCURACY_M = 100f
    }
}
