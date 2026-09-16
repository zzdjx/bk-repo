package com.tencent.bkrepo.common.artifact.crypt

import cn.hutool.crypto.asymmetric.KeyType
import cn.hutool.crypto.asymmetric.RSA
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.api.util.readJsonString
import com.tencent.bkrepo.common.security.crypto.CryptoProperties
import org.slf4j.LoggerFactory

class CryptKeyDecryptor(
    private val cryptoProperties: CryptoProperties
) {

    /**
     * 密钥会被 [com.tencent.bkrepo.common.security.crypto.CryptoPropertiesInitializer] 归一化并可能被 DB keyring
     * 覆盖，而本 Bean 所在的 ArtifactAutoConfiguration 排在 SecurityAutoConfiguration 之前创建。
     * 延迟到首次使用再取，避免构造期拿到未归一化或未被 DB 覆盖的旧密钥。
     */
    private val rsa by lazy {
        check(cryptoProperties.privateKeyStr2048PKCS1.isNotEmpty()) {
            "security.crypto.privateKeyStr2048PKCS1 is required when artifact.crypt.enabled is true"
        }
        RSA(
            cryptoProperties.rsaAlgorithm,
            cryptoProperties.privateKeyStr2048PKCS1,
            cryptoProperties.publicKeyStr2048PKCS1,
        )
    }


    fun getKey(encryptedKey: String): String {
        val cryptKey = try {
            val data = rsa.decryptStr(encryptedKey, KeyType.PrivateKey)
            data.readJsonString<CryptKey>()
        } catch (e: Exception) {
            logger.warn("Crypt key[$encryptedKey] is invalid:  ${e.message}")
            throw ErrorCodeException(CommonMessageCode.PARAMETER_INVALID, "crypt key")
        }
        if (cryptKey.timestamp + cryptKey.expiredSeconds < System.currentTimeMillis() / 1000L) {
            logger.info("Crypt key[$encryptedKey] expired, timestamp is ${cryptKey.timestamp}, " +
                    "expired seconds is ${cryptKey.expiredSeconds}")
            throw ErrorCodeException(CommonMessageCode.RESOURCE_EXPIRED, "crypt key")
        }
        return cryptKey.key
    }

    companion object {
        private val logger = LoggerFactory.getLogger(CryptKeyDecryptor::class.java)
    }
}