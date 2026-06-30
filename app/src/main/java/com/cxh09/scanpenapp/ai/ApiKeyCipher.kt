package com.cxh09.scanpenapp.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API Key 加密：把明文 API Key 用 [android.security.keystore] 保管的 AES/GCM 密钥加密成
 * 不可读的 base64 串，写入 SharedPreferences / JSON 时使用此格式。
 *
 * 设计目标：
 * - 零三方依赖：纯 Android Keystore API，避免引入 Tink / security-crypto（包体增大）。
 * - 防备份泄露：即便用户开启 adb backup，没有 Keystore 钥匙也解不出原文。
 * - 失败可降级：Keystore 不可用（系统不支持 / 被擦除）→ 加密返回 null，
 *   解密失败返回 null；调用方据此判定「需要用户重新输入」。
 *
 * 格式（base64 编码后的字符串）:
 * - 12 字节 IV
 * - N 字节密文（包含 GCM 16 字节 tag）
 *
 * 容器前缀：
 * - `"enc:"` 表示加密 blob，调用方写入持久化时必须带此前缀；
 * - 缺前缀视为明文（兼容老数据 / 兜底）。
 */
object ApiKeyCipher {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "scanpen_ai_apikey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val ENC_PREFIX = "enc:"

    /**
     * 加密明文 API Key，返回带 `enc:` 前缀的 base64 串。
     * - 明文为空 → 返回空串（不是错误，表示「无 key」状态）。
     * - Keystore 不可用 / 加密失败 → 返回 null（调用方应保留明文兜底或要求重输）。
     */
    fun encrypt(plain: String): String? {
        if (plain.isEmpty()) return ""
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            require(iv.size == IV_LENGTH_BYTES) { "unexpected IV size: ${iv.size}" }
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val blob = ByteArray(iv.size + ct.size).apply {
                System.arraycopy(iv, 0, this, 0, iv.size)
                System.arraycopy(ct, 0, this, iv.size, ct.size)
            }
            ENC_PREFIX + Base64.encodeToString(blob, Base64.NO_WRAP)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 解密带 `enc:` 前缀的 base64 串，返回明文。
     * - 空串 → 返回空串。
     * - 解密失败 / Keystore 不可用 / blob 损坏 → 返回 null。
     * - 输入不带 `enc:` 前缀视为明文，原样返回（兼容老数据 + 兜底）。
     */
    fun decrypt(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!stored.startsWith(ENC_PREFIX)) return stored
        val blob = try {
            Base64.decode(stored.substring(ENC_PREFIX.length), Base64.NO_WRAP)
        } catch (_: Throwable) {
            return null
        }
        if (blob.size <= IV_LENGTH_BYTES) return null
        return try {
            val key = getKey() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = blob.copyOfRange(0, IV_LENGTH_BYTES)
            val ct = blob.copyOfRange(IV_LENGTH_BYTES, blob.size)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 判断存储值是否处于「已加密」形态（带 `enc:` 前缀）。
     * UI 拿不到明文时可据此提示「已加密」或判定需要用户重新输入。
     */
    fun isEncrypted(stored: String): Boolean = stored.startsWith(ENC_PREFIX)

    private fun getOrCreateKey(): SecretKey {
        val existing = getKey()
        if (existing != null) return existing
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            // 不要用户认证（词典笔场景无 PIN），纯硬件绑定 Keystore
            .setRandomizedEncryptionRequired(true)
            .build()
        kg.init(spec)
        return kg.generateKey()
    }

    private fun getKey(): SecretKey? = try {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
    } catch (_: Throwable) {
        null
    }
}
