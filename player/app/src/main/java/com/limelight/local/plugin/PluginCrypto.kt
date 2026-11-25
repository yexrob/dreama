package com.limelight.local.plugin

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Implements `ctx.crypto` (protocol §5):
 * md5, sha1, sha256, hmac, aes (CBC/GCM), rsa (public-key encrypt),
 * randomBytes, base64, hex.
 */
object PluginCrypto {
    private val secureRandom = SecureRandom()
    private const val MAX_RANDOM_BYTES = 65_536

    fun md5(data: ByteArray): String = digest("MD5", data)
    fun sha1(data: ByteArray): String = digest("SHA-1", data)
    fun sha256(data: ByteArray): String = digest("SHA-256", data)

    fun hmac(algorithm: String, key: ByteArray, data: ByteArray): String {
        val jcaAlgo = when (algorithm.lowercase(Locale.ROOT).replace("-", "")) {
            "md5" -> "HmacMD5"
            "sha1" -> "HmacSHA1"
            "sha256" -> "HmacSHA256"
            "sha512" -> "HmacSHA512"
            else -> throw IllegalArgumentException("Unsupported HMAC algorithm: $algorithm")
        }
        val mac = Mac.getInstance(jcaAlgo)
        mac.init(SecretKeySpec(key, jcaAlgo))
        return hexEncode(mac.doFinal(data))
    }

    fun aesEncrypt(
        mode: String,
        key: ByteArray,
        iv: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray = aesOp(Cipher.ENCRYPT_MODE, mode, key, iv, plaintext, aad)

    fun aesDecrypt(
        mode: String,
        key: ByteArray,
        iv: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray? = null,
    ): ByteArray = aesOp(Cipher.DECRYPT_MODE, mode, key, iv, ciphertext, aad)

    private fun aesOp(
        opMode: Int,
        mode: String,
        key: ByteArray,
        iv: ByteArray,
        input: ByteArray,
        aad: ByteArray?,
    ): ByteArray {
        require(key.size == 16 || key.size == 24 || key.size == 32) {
            "AES key must be 16, 24, or 32 bytes (got ${key.size})"
        }
        val secretKey = SecretKeySpec(key, "AES")
        return when (mode.uppercase(Locale.ROOT)) {
            "CBC", "AES-CBC" -> {
                require(iv.size == 16) { "AES-CBC IV must be 16 bytes (got ${iv.size})" }
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(opMode, secretKey, IvParameterSpec(iv))
                cipher.doFinal(input)
            }
            "GCM", "AES-GCM" -> {
                require(iv.size == 12 || iv.size == 16) { "AES-GCM IV must be 12 or 16 bytes (got ${iv.size})" }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(opMode, secretKey, GCMParameterSpec(128, iv))
                if (aad != null && aad.isNotEmpty()) {
                    cipher.updateAAD(aad)
                }
                cipher.doFinal(input)
            }
            else -> throw IllegalArgumentException("Unsupported AES mode: $mode (use CBC or GCM)")
        }
    }

    /**
     * Encrypts [data] with an RSA public key in PEM (SPKI) or raw base64 DER format.
     */
    fun rsaEncrypt(
        publicKeyPem: String,
        data: ByteArray,
        padding: String = "PKCS1",
    ): ByteArray {
        val cleaned = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s+"), "")
        val der = base64Decode(cleaned)
        val pubKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val cipher = when (padding.uppercase(Locale.ROOT)) {
            "PKCS1", "RSA/ECB/PKCS1PADDING" -> {
                Cipher.getInstance("RSA/ECB/PKCS1Padding").also { it.init(Cipher.ENCRYPT_MODE, pubKey) }
            }
            "OAEP", "OAEP-SHA256", "OAEPWITHSHA-256ANDMGF1PADDING" -> {
                Cipher.getInstance("RSA/ECB/OAEPPadding").also {
                    it.init(
                        Cipher.ENCRYPT_MODE,
                        pubKey,
                        OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT),
                    )
                }
            }
            "OAEP-SHA1", "OAEPWITHSHA-1ANDMGF1PADDING" -> {
                Cipher.getInstance("RSA/ECB/OAEPPadding").also {
                    it.init(
                        Cipher.ENCRYPT_MODE,
                        pubKey,
                        OAEPParameterSpec("SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT),
                    )
                }
            }
            else -> throw IllegalArgumentException("Unsupported RSA padding: $padding")
        }
        return cipher.doFinal(data)
    }

    fun randomBytes(length: Int): ByteArray {
        require(length in 0..MAX_RANDOM_BYTES) { "randomBytes length must be in 0..$MAX_RANDOM_BYTES" }
        return ByteArray(length).also { secureRandom.nextBytes(it) }
    }

    fun base64Encode(data: ByteArray, urlSafe: Boolean = false): String =
        if (urlSafe) Base64.getUrlEncoder().withoutPadding().encodeToString(data)
        else Base64.getEncoder().encodeToString(data)

    fun base64Decode(encoded: String): ByteArray {
        val normalized = encoded.trim().replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        return Base64.getDecoder().decode(padded)
    }

    fun hexEncode(data: ByteArray): String {
        val out = StringBuilder(data.size * 2)
        for (b in data) {
            val v = b.toInt() and 0xFF
            out.append(HEX_CHARS[v ushr 4])
            out.append(HEX_CHARS[v and 0x0F])
        }
        return out.toString()
    }

    fun hexDecode(hex: String): ByteArray {
        val cleaned = hex.trim()
        require(cleaned.length % 2 == 0) { "Hex string must have even length" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(cleaned[i * 2], 16)
            val lo = Character.digit(cleaned[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "Invalid hex character at index ${i * 2}" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun digest(algo: String, data: ByteArray): String =
        hexEncode(MessageDigest.getInstance(algo).digest(data))

    private const val HEX_CHARS = "0123456789abcdef"
}
