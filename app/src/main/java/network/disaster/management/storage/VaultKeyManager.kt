package network.disaster.management.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware-backed Master Key Manager leveraging Android KeyStore (TEE / StrongBox).
 * Generates and securely seals a 256-bit vault passphrase so that database encryption
 * is device-unique and unrecoverable if flash memory is extracted from hardware.
 */
object VaultKeyManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "DisasterVaultMasterKey_v1"
    private const val PREFS_NAME = "disaster_vault_security_prefs"
    private const val PREF_ENCRYPTED_PASSPHRASE = "vault_enc_pass"
    private const val PREF_IV = "vault_iv"

    private val secureRandom = SecureRandom()

    @Synchronized
    fun getOrCreateVaultPassphrase(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encPassBase64 = prefs.getString(PREF_ENCRYPTED_PASSPHRASE, null)
        val ivBase64 = prefs.getString(PREF_IV, null)

        val masterKey = getOrCreateMasterKey()

        if (encPassBase64 != null && ivBase64 != null) {
            try {
                val encBytes = Base64.decode(encPassBase64, Base64.NO_WRAP)
                val iv = Base64.decode(ivBase64, Base64.NO_WRAP)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
                val raw = cipher.doFinal(encBytes)
                return raw.joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                // If hardware key became invalid, re-generate clean key
            }
        }

        // Generate brand new 32-byte (256-bit) cryptographically random passphrase
        val rawPass = ByteArray(32).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, GCMParameterSpec(128, iv))
        val encryptedPass = cipher.doFinal(rawPass)

        prefs.edit()
            .putString(PREF_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encryptedPass, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
            .commit()

        val hexResult = rawPass.joinToString("") { "%02x".format(it) }
        rawPass.fill(0)
        return hexResult
    }

    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // We supply our own SecureRandom IV
                .build()

            keyGenerator.init(spec)
            return keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey
    }

    @Synchronized
    fun purgeVaultSecurity(context: Context) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) {}

        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        } catch (_: Exception) {}
    }
}
