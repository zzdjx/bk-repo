/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.common.security.util

import cn.hutool.crypto.CryptoException
import cn.hutool.crypto.asymmetric.KeyType
import cn.hutool.crypto.asymmetric.RSA
import com.tencent.bkrepo.common.security.crypto.CryptoProperties
import com.tencent.bkrepo.common.security.crypto.LegacyCryptoKeys
import org.slf4j.LoggerFactory
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.InvalidKeySpecException
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64


/**
 * RSA 非对称加密工具类
 */
class RsaUtils(
    cryptoProperties: CryptoProperties
) {
    init {
        properties = cryptoProperties
        // 未配置时不建实例，等真正用到再报错，避免用不上登录密钥的服务（如 proxy）启动即失败
        rsa = cryptoProperties.privateKeyStr.takeIf { it.isNotEmpty() }?.let {
            RSA(cryptoProperties.rsaAlgorithm, it, cryptoProperties.publicKeyStr)
        }
        // 配置变了就丢掉缓存，下次用到按新算法重建
        legacyRsa = null
    }

    companion object {
        private lateinit var properties: CryptoProperties
        private var rsa: RSA? = null
        private var legacyRsa: RSA? = null
        private val logger = LoggerFactory.getLogger(RsaUtils::class.java)

        /**
         * 未配置时不能返回空串：UserController 的 GET /rsa 直接把它给前端，
         * 前端拿空公钥加密登录密码，故障现场会跑到浏览器里
         */
        val publicKey: String get() = required(properties.publicKeyStr, "publicKeyStr")

        val privateKey: String get() = required(properties.privateKeyStr, "privateKeyStr")

        private fun required(value: String, field: String): String {
            check(value.isNotEmpty()) { missing(field) }
            return value
        }

        private fun missing(field: String) =
            "security.crypto.$field is required, generate it with scripts/gen-crypto-keys.sh"

        private fun rsa(): RSA = checkNotNull(rsa) { missing("privateKeyStr") }

        /**
         * 只有 [decryptWithSource] 的兜底分支会走到，按需构造，用不上登录密钥的服务
         * （如 proxy）不必为它常驻一把已公开的私钥。公钥留空，从构造上就无法用旧密钥加密。
         * 并发首次进入可能构造两次，幂等，无害
         */
        private fun legacyRsa(): RSA = legacyRsa
            ?: RSA(properties.rsaAlgorithm, legacyPrivateKey(), null).also { legacyRsa = it }

        /**
         * 公钥加密
         * @param password 需要解密的密码
         */
        fun encrypt(password: String): String {
            return rsa().encryptBcd(password, KeyType.PublicKey)
        }

        /**
         * 私钥解密，返回解密后的密码
         * @param password 前端加密后的密码
         */
        fun decrypt(password: String): String {
            return decryptWithSource(password).first
        }

        /**
         * 额外返回解开密文的密钥来源，供存量重加密判断该不该回写。
         * 不能直接 encrypt(decrypt(x))：解不开时旧实现会把密文当明文返回，
         * 再加密一遍就变成双层密文，不可逆
         */
        fun decryptWithSource(password: String): Pair<String, KeySource> {
            return try {
                rsa().decryptStr(password, KeyType.PrivateKey) to KeySource.CURRENT
            } catch (ignored: CryptoException) {
                logger.warn("decrypted with legacy key, this ciphertext still needs re-encryption")
                legacyRsa().decryptStr(password, KeyType.PrivateKey) to KeySource.LEGACY
            }
        }

        /**
         * 唯一允许引用旧默认密钥的地方，且只取私钥用于解密
         */
        @Suppress("DEPRECATION")
        private fun legacyPrivateKey(): String {
            return normalizePrivateKey(LegacyCryptoKeys.RSA_1024_PRIVATE_KEY)
        }

        fun stringToPrivateKey(privateStr: String): RSAPrivateKey {
            require(privateStr.isNotBlank()) { "rsa private key is blank" }
            val der = Base64.getDecoder().decode(pemBody(privateStr))
            val factory = KeyFactory.getInstance("RSA")
            return try {
                factory.generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateKey
            } catch (ignored: InvalidKeySpecException) {
                // helm genPrivateKey 产出的是 PKCS#1，包一层 PKCS#8 头再解
                factory.generatePrivate(PKCS8EncodedKeySpec(pkcs1ToPkcs8(der))) as RSAPrivateKey
            }
        }

        fun stringToPublicKey(publStr: String?): RSAPublicKey {
            require(!publStr.isNullOrBlank()) { "rsa public key is blank" }
            val data = Base64.getDecoder().decode(pemBody(publStr))
            val spec = X509EncodedKeySpec(data)
            val fact = KeyFactory.getInstance("RSA")
            return fact.generatePublic(spec) as RSAPublicKey
        }

        /**
         * 私钥归一化成单行 PKCS#8 Base64，屏蔽 PEM 与 PKCS#1 差异
         */
        fun normalizePrivateKey(privateStr: String): String {
            return Base64.getEncoder().encodeToString(stringToPrivateKey(privateStr).encoded)
        }

        /**
         * 公钥是私钥的冗余信息，未配置时直接推导，返回单行 X.509 Base64
         */
        fun derivePublicKey(privateStr: String): String {
            val privateKey = stringToPrivateKey(privateStr)
            require(privateKey is RSAPrivateCrtKey) { "rsa private key without CRT params" }
            val spec = RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(spec)
            return Base64.getEncoder().encodeToString(publicKey.encoded)
        }

        /**
         * PKCS#1 DER 包成 PKCS#8 PrivateKeyInfo。
         * 长度统一按 DER 的 0x82 两字节形式编码，仅对 RSA >= 1024 位成立
         * （PKCS#1 体恒大于 255 字节）。需要支持更短密钥时改成通用 DER 长度编码。
         */
        private fun pkcs1ToPkcs8(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val rsaEncryption = byteArrayOf(
                0x30, 0x0D, 0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(),
                0xF7.toByte(), 0x0D, 0x01, 0x01, 0x01, 0x05, 0x00
            )
            val octetString = byteArrayOf(
                0x04, 0x82.toByte(), (pkcs1.size ushr 8).toByte(), pkcs1.size.toByte()
            ) + pkcs1
            val body = version + rsaEncryption + octetString
            return byteArrayOf(
                0x30, 0x82.toByte(), (body.size ushr 8).toByte(), body.size.toByte()
            ) + body
        }

        private fun pemBody(value: String): String {
            return value
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
        }
    }
}

/**
 * 密文由哪把密钥解开。LEGACY 表示这条密文仍是升级前的默认密钥加密的，需要重加密
 */
enum class KeySource {
    CURRENT,
    LEGACY
}
