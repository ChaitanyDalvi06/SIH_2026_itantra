package com.itantra.edgespeech.protocol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CryptoEngine {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "iTantra_Identity_Key"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureIdentityKey()
    }

    private fun ensureIdentityKey() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val keyGenSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }
    }

    fun getIdentityKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    fun encrypt(plaintext: ByteArray, secretKey: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val ciphertextWithTag = cipher.doFinal(plaintext)

        // Concatenate IV + Ciphertext/Tag
        val result = ByteArray(iv.size + ciphertextWithTag.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertextWithTag, 0, result, iv.size, ciphertextWithTag.size)
        return result
    }

    fun decrypt(encryptedPayload: ByteArray, secretKey: SecretKey): ByteArray {
        if (encryptedPayload.size < GCM_IV_LENGTH) {
            throw IllegalArgumentException("Payload too short for GCM IV")
        }

        val iv = encryptedPayload.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertextWithTag = encryptedPayload.copyOfRange(GCM_IV_LENGTH, encryptedPayload.size)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        return cipher.doFinal(ciphertextWithTag)
    }
}
