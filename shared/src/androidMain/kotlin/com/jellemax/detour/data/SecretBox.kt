package com.jellemax.detour.data

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM over a key that lives in the Android Keystore and never leaves it.
 *
 * Written against the platform directly rather than androidx.security-crypto, which was
 * deprecated in 1.1.0-beta01 (4 June 2025) "in favour of existing platform APIs and direct
 * use of Android Keystore". That is also what the OWASP Mobile Application Security Cheat
 * Sheet asks for, so the deprecation removed a dependency rather than forcing one.
 *
 * Both entry points return null rather than throwing. The key can genuinely disappear —
 * a device restore, an app-data clear, a Keystore fault — and a throw would propagate out
 * of Settings.init() and crash the app on every launch, permanently, because the failure
 * is persistent. Returning null means "no value", which the sign-in flow already handles.
 */
internal object SecretBox {

    private const val ALIAS = "detour_credential_store"
    private const val KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val TAG = "SecretBox"

    /**
     * Base64 of `IV || ciphertext`, or null if the key is unavailable.
     *
     * One-shot recovery only, on this path: if the existing alias cannot be used by this
     * transformation — invalidated by a device-level event, or left by a future spec change
     * — [key] throws, and a bare retry would throw again forever. Delete the alias and
     * retry exactly once with a freshly generated key. Never do this from [open]: deleting
     * the key on a decrypt failure would destroy the ability to read values a later attempt
     * might still decrypt, and a corrupt blob is a per-value problem, not a key problem.
     */
    fun seal(plain: String): String? {
        trySeal(plain)?.let { return it }
        Log.w(TAG, "seal failed with the existing key; recreating it and retrying once")
        deleteKey()
        return trySeal(plain)
    }

    private fun trySeal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        // No IV is supplied: Keystore keys default to setRandomizedEncryptionRequired(true),
        // which makes passing one throw. The cipher generates it and we read it back.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.encodeToByteArray())
        Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }.onFailure { Log.w(TAG, "seal failed", it) }.getOrNull()

    /** The plaintext, or null if the blob is corrupt or the key is gone. */
    fun open(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES),
        )
        cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).decodeToString()
    }.onFailure { Log.w(TAG, "open failed", it) }.getOrNull()

    /** Removes the alias so the next [key] call generates fresh material. Best-effort. */
    private fun deleteKey() {
        runCatching {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        // StrongBox is a separate security chip and is the strongest option the cheat sheet
        // names, but plenty of devices do not have one and generation throws there. Ask,
        // then fall back to the ordinary hardware-backed keystore.
        return runCatching {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        }.getOrElse {
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    private fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
        ALIAS,
        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
    )
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        // Deliberately NOT setUserAuthenticationRequired: the trip service reads tokens
        // with the screen off, and requiring user presence would stop recording.
        .apply {
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setIsStrongBoxBacked(true)
            }
        }
        .build()
}
