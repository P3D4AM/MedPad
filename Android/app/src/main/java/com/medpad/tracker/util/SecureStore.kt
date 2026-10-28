package com.medpad.tracker.util

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * package 10 — secure token storage (platform Keystore, no new dependency).
 *
 * The app token used to live as PLAINTEXT in SharedPreferences. Requirement:
 * never store a token (or password) in plaintext; use the platform's secure
 * storage mechanism. This object implements the standard Android pattern
 * with ZERO new dependencies (the sandbox builds offline):
 *
 *   - AES-256/GCM key generated INSIDE AndroidKeyStore (key material never
 *     enters app files and cannot be exported)
 *   - every encryption lets the Keystore generate a fresh random IV
 *     (randomized encryption REQUIRED — the recommended posture), the IV is
 *     stored alongside the ciphertext
 *   - stored format: "enc:v1:" + Base64(iv ‖ ciphertext+tag)
 *   - decrypt FAILS CLOSED on any tampering (GCM auth), wrong key, or
 *     corrupt blob → null (never a garbage token)
 *
 * Passwords are NEVER stored at all — auto-login is token-based only.
 *
 * The AES/GCM core works with any SecretKey (pure javax.crypto) so it is
 * unit-testable on the JVM; only `keystoreKey()` touches Android. If the
 * Keystore is broken on some exotic ROM, encrypt() returns null and the
 * caller keeps the token in memory only (no plaintext fallback — honest
 * degradation: auto-login disabled until the next successful login).
 */
object SecureStore {
    const val PREFIX = "enc:v1:"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "medpad_token_key"
    private const val TAG_BITS = 128

    // ── pure crypto core (JVM-testable) ───────────────────────

    fun encryptWith(key: SecretKey, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key) // provider-generated fresh IV
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decryptWith(key: SecretKey, blob: String): String? {
        if (!blob.startsWith(PREFIX)) return null
        return try {
            val raw = Base64.decode(blob.removePrefix(PREFIX), Base64.NO_WRAP)
            // IV length is recoverable from the blob: total = iv + ct(+tag);
            // GCM IVs from the Keystore provider are 12 bytes.
            val ivLen = 12
            if (raw.size <= ivLen) return null
            val iv = raw.copyOfRange(0, ivLen)
            val ct = raw.copyOfRange(ivLen, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null // tampered / wrong key / corrupt — fail closed
        }
    }

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    // ── Android Keystore glue ─────────────────────────────────

    fun encrypt(context: Context, plain: String): String? {
        val key = keystoreKey() ?: return null
        return try { encryptWith(key, plain) } catch (_: Exception) { null }
    }

    fun decrypt(context: Context, blob: String): String? {
        val key = keystoreKey() ?: return null
        return decryptWith(key, blob)
    }

    private fun keystoreKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: run {
            val gen = KeyGenerator.getInstance("AES", KEYSTORE)
            gen.init(
                android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            gen.generateKey()
        }
    } catch (_: Exception) {
        null // Keystore unavailable on this device/ROM — caller degrades honestly
    }
}
