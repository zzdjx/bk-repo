package com.tencent.bkrepo.common.security.crypto

import com.tencent.bkrepo.common.security.util.RsaUtils
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import java.util.Base64

/**
 * 密钥归一化、公私钥校验，并在有 Mongo 时把完整配置固化到 DB。
 * 有 Store 时先读库，只校验最终采用的那套；DB 有有效记录则以 DB 为准。
 * 查询失败、半对密钥或 fingerprint 对不上时启动失败，禁止当成无记录重新生成。
 * DB 已有的密钥类型以库为准；库里为空的类型用当前配置补上并写回。
 */
class CryptoPropertiesInitializer(
    private val properties: CryptoProperties,
    private val store: CryptoPropertiesStore? = null
) : InitializingBean {

    override fun afterPropertiesSet() {
        if (store == null) {
            normalize(properties)
            validate(properties)
            return
        }
        val existing = store.load()
        if (existing != null) {
            adopt(mergeWithLocal(existing))
            return
        }
        normalize(properties)
        validate(properties)
        if (!properties.hasMaterial()) {
            return
        }
        val persisted = store.insertIfAbsent(properties)
        if (persisted !== properties) {
            adopt(persisted)
        }
    }

    /**
     * DB 非空字段不动。空的密钥类型用当前配置补上并写回。
     * 本地配置坏了则只采用 DB，不补。
     */
    private fun mergeWithLocal(existing: CryptoProperties): CryptoProperties {
        val keyring = store ?: return existing
        val local = try {
            val snapshot = properties.copy()
            normalize(snapshot)
            validate(snapshot)
            snapshot
        } catch (ignored: Exception) {
            return existing
        }
        val merged = existing.fillMissingFrom(local)
        if (merged.fingerprint() == existing.fingerprint()) {
            return existing
        }
        return keyring.patch(existing.fingerprint(), merged) ?: keyring.load() ?: existing
    }

    private fun adopt(persisted: CryptoProperties) {
        val localFingerprint = fingerprintOrNull(properties)
        normalize(persisted)
        validate(persisted)
        properties.overwrite(persisted)
        if (localFingerprint != null && localFingerprint != properties.fingerprint()) {
            logger.warn(
                "security.crypto config differs from DB keyring, DB keyring wins and config is ignored. " +
                    "Rotating an in-use key requires re-encrypting existing data, do it manually. " +
                    "config=[$localFingerprint], db=[${properties.fingerprint()}]"
            )
        }
    }

    /**
     * 本地配置可能已坏（重建后不成对），不能因此挡住读库。算不出指纹就跳过对比。
     */
    private fun fingerprintOrNull(source: CryptoProperties): String? {
        return try {
            val snapshot = source.copy()
            normalize(snapshot)
            snapshot.fingerprint()
        } catch (ignored: Exception) {
            null
        }
    }

    private fun normalize(target: CryptoProperties) = with(target) {
        privateKeyStr = normalizePrivateKey(privateKeyStr)
        publicKeyStr = resolvePublicKey("publicKeyStr", privateKeyStr, publicKeyStr)
        privateKeyStr2048PKCS8 = normalizePrivateKey(privateKeyStr2048PKCS8)
        publicKeyStr2048PKCS8 = resolvePublicKey(
            "publicKeyStr2048PKCS8",
            privateKeyStr2048PKCS8,
            publicKeyStr2048PKCS8
        )
        privateKeyStr2048PKCS1 = normalizePrivateKey(privateKeyStr2048PKCS1)
        publicKeyStr2048PKCS1 = resolvePublicKey(
            "publicKeyStr2048PKCS1",
            privateKeyStr2048PKCS1,
            publicKeyStr2048PKCS1
        )
        aesKey = clean(aesKey)
        aesIv = clean(aesIv)
    }

    private fun validate(target: CryptoProperties) = with(target) {
        check(aesKey.isEmpty() || aesKey.toByteArray().size in AES_KEY_LENGTHS) {
            "security.crypto.aesKey must be 16, 24 or 32 bytes"
        }
        check(aesIv.isEmpty() || aesIv.toByteArray().size == AES_IV_LENGTH) {
            "security.crypto.aesIv must be $AES_IV_LENGTH bytes"
        }
        check(aesKey.isEmpty() == aesIv.isEmpty()) {
            "security.crypto keyring is incomplete: aesKey/aesIv"
        }
    }

    private fun normalizePrivateKey(privateKey: String): String {
        return clean(privateKey).takeIf { it.isNotEmpty() }
            ?.let { RsaUtils.normalizePrivateKey(it) }
            .orEmpty()
    }

    private fun resolvePublicKey(field: String, privateKey: String, publicKey: String): String {
        val cleaned = clean(publicKey)
        if (privateKey.isEmpty()) {
            check(cleaned.isEmpty()) { "security.crypto.$field has no matching private key" }
            return ""
        }
        val derived = RsaUtils.derivePublicKey(privateKey)
        if (cleaned.isEmpty()) {
            return derived
        }
        val provided = try {
            Base64.getEncoder().encodeToString(RsaUtils.stringToPublicKey(cleaned).encoded)
        } catch (exception: Exception) {
            throw IllegalStateException("security.crypto.$field is invalid", exception)
        }
        check(provided == derived) { "security.crypto.$field does not match its private key" }
        return derived
    }

    /**
     * 部署模板未被替换时会留下 __BK_REPO_XXX__ 或 ${BK_REPO_XXX}，一律按未配置处理，
     * 否则会带着占位符往下走，报错停在 Base64 解码上，运维看不懂
     */
    private fun clean(value: String): String {
        val trimmed = value.trim()
        val unresolved = trimmed.startsWith("\${") ||
            (trimmed.startsWith("__") && trimmed.endsWith("__"))
        return if (unresolved) "" else trimmed
    }

    companion object {
        private val AES_KEY_LENGTHS = setOf(16, 24, 32)
        private const val AES_IV_LENGTH = 16
        private val logger = LoggerFactory.getLogger(CryptoPropertiesInitializer::class.java)
    }
}
