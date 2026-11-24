package com.limelight.local.plugin

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Authenticated encryption primitive used to persist plugin `ctx.storage`,
 * instance cookies, and encrypted plugin configuration values at rest.
 */
interface SecretBox {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray

    fun encryptToBase64(plaintext: String): String =
        PluginCrypto.base64Encode(encrypt(plaintext.toByteArray(Charsets.UTF_8)))

    fun decryptFromBase64(encoded: String): String =
        String(decrypt(PluginCrypto.base64Decode(encoded)), Charsets.UTF_8)
}

/**
 * Production Android Keystore AES-256-GCM implementation (API 23+).
 * Format: [1 byte version = 1][12 byte IV][ciphertext + 16 byte GCM tag].
 */
class AndroidKeystoreSecretBox(
    private val keyAlias: String = DEFAULT_ALIAS,
) : SecretBox {
    private val lock = Any()

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == IV_LEN) { "Unexpected Keystore GCM IV length: ${iv.size}" }
        val body = cipher.doFinal(plaintext)
        val out = ByteArray(1 + IV_LEN + body.size)
        out[0] = FORMAT_VERSION
        System.arraycopy(iv, 0, out, 1, IV_LEN)
        System.arraycopy(body, 0, out, 1 + IV_LEN, body.size)
        return out
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 1 + IV_LEN + TAG_LEN_BYTES && ciphertext[0] == FORMAT_VERSION) {
            "Corrupted encrypted storage payload"
        }
        val iv = ciphertext.copyOfRange(1, 1 + IV_LEN)
        val body = ciphertext.copyOfRange(1 + IV_LEN, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_LEN_BITS, iv))
        return cipher.doFinal(body)
    }

    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        gen.init(spec)
        gen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DEFAULT_ALIAS = "depth3d_plugin_master_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FORMAT_VERSION: Byte = 1
        private const val IV_LEN = 12
        private const val TAG_LEN_BITS = 128
        private const val TAG_LEN_BYTES = 16
    }
}

/**
 * Pure JCA AES-256-GCM implementation for JVM unit tests and deterministic
 * encryption testing.
 */
class SoftwareAesSecretBox(
    keyBytes: ByteArray = PluginCrypto.randomBytes(32),
) : SecretBox {
    private val key = SecretKeySpec(keyBytes.copyOf(), "AES")
    private val random = SecureRandom()

    init {
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "AES key must be 16, 24, or 32 bytes"
        }
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plaintext)
        val out = ByteArray(1 + 12 + body.size)
        out[0] = 1
        System.arraycopy(iv, 0, out, 1, 12)
        System.arraycopy(body, 0, out, 13, body.size)
        return out
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 1 + 12 + 16 && ciphertext[0] == 1.toByte()) {
            "Corrupted encrypted payload"
        }
        val iv = ciphertext.copyOfRange(1, 13)
        val body = ciphertext.copyOfRange(13, ciphertext.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(body)
    }
}

class StorageQuotaExceededException(message: String) : IllegalStateException(message)

/**
 * Raised when encrypted storage on disk fails decryption or JSON parsing.
 * The original file on disk is preserved untouched so a transient keystore
 * error never silently wipes or overwrites credentials.
 */
class StorageCorruptedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Per-instance encrypted key-value store backing `ctx.storage` (protocol §5):
 * - JSON values
 * - encrypted on disk using [SecretBox]
 * - strictly capped at 1 MB serialized JSON size
 * - preserves original file and throws [StorageCorruptedException] if
 *   decryption fails rather than silently clearing and overwriting
 */
class InstanceStorage(
    private val file: File,
    private val secretBox: SecretBox,
    private val maxBytes: Int = MAX_STORAGE_BYTES,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = ReentrantReadWriteLock()
    private val entries = LinkedHashMap<String, JsonElement>()
    private var loaded = false
    private var loadFailure: StorageCorruptedException? = null

    fun get(key: String): JsonElement? = lock.read {
        if (loaded && loadFailure == null) return entries[key]
        null
    } ?: lock.write {
        ensureLoadedLocked()
        entries[key]
    }

    fun set(key: String, value: JsonElement) = lock.write {
        ensureLoadedLocked()
        val prevExisted = entries.containsKey(key)
        val prev = entries[key]
        entries[key] = value
        val serialized = JsonObject(entries).toString().toByteArray(Charsets.UTF_8)
        if (serialized.size > maxBytes) {
            if (prevExisted && prev != null) entries[key] = prev else entries.remove(key)
            throw StorageQuotaExceededException(
                "插件存储空间超过上限（${maxBytes / 1024} KB）",
            )
        }
        writeEncryptedLocked(serialized)
    }

    fun remove(key: String) = lock.write {
        ensureLoadedLocked()
        if (entries.remove(key) != null) {
            val serialized = JsonObject(entries).toString().toByteArray(Charsets.UTF_8)
            writeEncryptedLocked(serialized)
        }
    }

    fun keys(): List<String> = lock.write {
        ensureLoadedLocked()
        entries.keys.toList()
    }

    fun clear() = lock.write {
        entries.clear()
        loaded = true
        loadFailure = null
        if (file.exists()) {
            file.delete()
        }
    }

    private fun ensureLoadedLocked() {
        loadFailure?.let { throw it }
        if (loaded) return
        if (!file.exists()) {
            loaded = true
            return
        }
        try {
            val encrypted = file.readBytes()
            if (encrypted.isEmpty()) {
                throw StorageCorruptedException("加密存储文件为空或已损坏")
            }
            val plain = secretBox.decrypt(encrypted)
            val obj = json.parseToJsonElement(String(plain, Charsets.UTF_8)) as? JsonObject
                ?: throw StorageCorruptedException("加密存储内容格式无效")
            entries.clear()
            entries.putAll(obj)
            loaded = true
        } catch (e: StorageCorruptedException) {
            loadFailure = e
            throw e
        } catch (e: Exception) {
            val ex = StorageCorruptedException("无法解密插件存储，已保留原文件，请重新登录或检查系统密钥。", e)
            loadFailure = ex
            throw ex
        }
    }

    private fun writeEncryptedLocked(plainBytes: ByteArray) {
        file.parentFile?.mkdirs()
        val encrypted = secretBox.encrypt(plainBytes)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(encrypted)
        if (!tmp.renameTo(file)) {
            file.writeBytes(encrypted)
            tmp.delete()
        }
    }

    companion object {
        const val MAX_STORAGE_BYTES = 1024 * 1024
    }
}
