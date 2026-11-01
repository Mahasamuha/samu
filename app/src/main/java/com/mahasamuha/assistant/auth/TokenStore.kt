package com.mahasamuha.assistant.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import android.util.Log
import com.mahasamuha.assistant.voice.TAG
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the serialized AppAuth state (which holds the refresh token) encrypted with AES-GCM under an
 * Android Keystore key. The key is StrongBox-backed when available, otherwise TEE-backed, and is only
 * usable while the device is unlocked, so the token can't be read while the phone is locked.
 */
class TokenStore(
    context: Context,
) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "auth_state.bin"))

    fun exists(): Boolean = file.baseFile.exists()

    fun save(plaintext: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = file.startWrite()
        try {
            out.write(iv.size)
            out.write(iv)
            out.write(ciphertext)
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    /** Throws if the device is locked or the key was invalidated; callers treat the latter as signed out. */
    fun load(): String? {
        if (!exists()) return null
        val bytes = file.readFully()
        val ivLength = bytes[0].toInt()
        val iv = bytes.copyOfRange(1, 1 + ivLength)
        val cipher =
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            }
        return String(cipher.doFinal(bytes, 1 + ivLength, bytes.size - 1 - ivLength), Charsets.UTF_8)
    }

    fun clear() {
        file.delete()
    }

    /** For the setup screen: where the key actually lives. */
    fun keySecurityLevel(): String {
        val key = key()
        val info = SecretKeyFactory.getInstance(key.algorithm, KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
        return when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software"
            else -> "unknown (${info.securityLevel})"
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as SecretKey?)?.let { return it }
        return try {
            generateKey(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            Log.i(TAG, "StrongBox unavailable, using TEE for the token key")
            generateKey(strongBox = false)
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec =
            KeyGenParameterSpec
                .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUnlockedDeviceRequired(true)
                .setIsStrongBoxBacked(strongBox)
                .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "auth_state_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
