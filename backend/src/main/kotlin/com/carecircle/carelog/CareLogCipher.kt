package com.carecircle.carelog

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class CareLogCipher(
    @param:Value("\${care-circle.care-log.encryption-key:}") private val encodedKey: String,
) {
    private val random = SecureRandom()

    fun isConfigured(): Boolean = runCatching { keyBytes() }.isSuccess

    fun encrypt(value: String, associatedData: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes(), "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.size + encrypted.size).put(iv).put(encrypted).array())
    }

    fun decrypt(value: String, associatedData: String): String {
        val bytes = Base64.getDecoder().decode(value)
        if (bytes.size <= IV_BYTES) throw IllegalArgumentException("Invalid encrypted care log entry")
        val buffer = ByteBuffer.wrap(bytes)
        val iv = ByteArray(IV_BYTES).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes(), "AES"), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun keyBytes(): ByteArray {
        val decoded = runCatching { Base64.getDecoder().decode(encodedKey) }.getOrNull()
        require(decoded?.size == KEY_BYTES) { "Care log encryption key must be a base64-encoded 32-byte value" }
        return decoded
    }

    private companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}