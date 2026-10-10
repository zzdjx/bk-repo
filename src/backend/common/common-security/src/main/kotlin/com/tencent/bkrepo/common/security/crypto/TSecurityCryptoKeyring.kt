package com.tencent.bkrepo.common.security.crypto

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * Mongo 全局密钥文档，集合 [COLLECTION]，[_id]=[GLOBAL_ID]。
 *
 * 存的是启动时归一化后的值，不是 Consul / helm yaml 原文。贴 PEM、缺公钥、fingerprint 对不上
 * 都会启动失败，不会回落配置。
 *
 * 字段形态：
 * - 私钥：[RsaUtils.normalizePrivateKey]，单行 PKCS#8 DER Base64（无 PEM 头、无换行）
 * - 公钥：[RsaUtils.derivePublicKey]，单行 X.509 DER Base64，必须由对应私钥推出
 * - AES：trim 后原样；aesKey 16/24/32 字节，aesIv 16 字节
 * - fingerprint：[CryptoProperties.fingerprint]，按归一化后整份重算
 * - schemaVersion：必须为 [SCHEMA_VERSION]
 * 三套 RSA 与 AES 都必须成对（两个都空或两个都有）。
 *
 * 手改优先删文档让进程重写：先改 yaml/Consul，再
 * `db.security_crypto_keyring.deleteOne({_id:"global"})`，只启一个带 MongoTemplate 的服务。
 * 独立库每个库都要删一次。
 *
 * 必须直接写库时，先把私钥转到入库形态再算 fingerprint，不要贴 PEM：
 * openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in priv.pem | openssl base64 -A
 * openssl rsa -in priv.pem -pubout -outform DER 2>/dev/null | openssl base64 -A
 * 登录 / PKCS8 / PKCS1 三套各做一遍。fingerprint 算法见 [CryptoProperties.fingerprint]。
 */
@Document(TSecurityCryptoKeyring.COLLECTION)
data class TSecurityCryptoKeyring(
    @Id
    val id: String = GLOBAL_ID,
    val schemaVersion: Int = SCHEMA_VERSION,
    val rsaAlgorithm: String = "",
    val privateKeyStr: String = "",
    val publicKeyStr: String = "",
    val privateKeyStr2048PKCS8: String = "",
    val publicKeyStr2048PKCS8: String = "",
    val privateKeyStr2048PKCS1: String = "",
    val publicKeyStr2048PKCS1: String = "",
    val aesKey: String = "",
    val aesIv: String = "",
    val fingerprint: String = "",
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    fun toProperties(): CryptoProperties {
        return CryptoProperties(
            rsaAlgorithm = rsaAlgorithm,
            privateKeyStr = privateKeyStr,
            publicKeyStr = publicKeyStr,
            privateKeyStr2048PKCS8 = privateKeyStr2048PKCS8,
            publicKeyStr2048PKCS8 = publicKeyStr2048PKCS8,
            privateKeyStr2048PKCS1 = privateKeyStr2048PKCS1,
            publicKeyStr2048PKCS1 = publicKeyStr2048PKCS1,
            aesKey = aesKey,
            aesIv = aesIv
        )
    }

    companion object {
        const val COLLECTION = "security_crypto_keyring"
        const val GLOBAL_ID = "global"
        const val SCHEMA_VERSION = 1

        fun from(properties: CryptoProperties, now: LocalDateTime): TSecurityCryptoKeyring {
            return TSecurityCryptoKeyring(
                id = GLOBAL_ID,
                schemaVersion = SCHEMA_VERSION,
                rsaAlgorithm = properties.rsaAlgorithm,
                privateKeyStr = properties.privateKeyStr,
                publicKeyStr = properties.publicKeyStr,
                privateKeyStr2048PKCS8 = properties.privateKeyStr2048PKCS8,
                publicKeyStr2048PKCS8 = properties.publicKeyStr2048PKCS8,
                privateKeyStr2048PKCS1 = properties.privateKeyStr2048PKCS1,
                publicKeyStr2048PKCS1 = properties.publicKeyStr2048PKCS1,
                aesKey = properties.aesKey,
                aesIv = properties.aesIv,
                fingerprint = properties.fingerprint(),
                createdAt = now,
                updatedAt = now
            )
        }
    }
}
