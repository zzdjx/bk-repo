package com.tencent.bkrepo.common.security.crypto

import com.tencent.bkrepo.common.security.util.RsaUtils
import org.springframework.beans.factory.InitializingBean

/**
 * 密钥归一化与校验。私钥统一转成单行 PKCS#8 Base64，公钥未配置时从私钥推导。
 * 代码里不再保留任何默认密钥。这里只归一化配了的密钥，缺哪把由真正用到它的
 * [RsaUtils] / [com.tencent.bkrepo.common.security.util.AESUtils] 报错，
 * 否则 proxy 这类一把都用不上的服务会被迫配齐全套才能启动。
 * helm 由 chart 渲染时生成，二进制部署用 scripts/gen-crypto-keys.sh 生成后填入配置。
 */
class CryptoPropertiesInitializer(
    private val properties: CryptoProperties
) : InitializingBean {

    override fun afterPropertiesSet() = with(properties) {
        privateKeyStr = normalizePrivateKey(privateKeyStr)
        publicKeyStr = resolvePublicKey(privateKeyStr, publicKeyStr)
        privateKeyStr2048PKCS8 = normalizePrivateKey(privateKeyStr2048PKCS8)
        publicKeyStr2048PKCS8 = resolvePublicKey(privateKeyStr2048PKCS8, publicKeyStr2048PKCS8)
        privateKeyStr2048PKCS1 = normalizePrivateKey(privateKeyStr2048PKCS1)
        publicKeyStr2048PKCS1 = resolvePublicKey(privateKeyStr2048PKCS1, publicKeyStr2048PKCS1)
        aesKey = clean(aesKey)
        check(aesKey.isEmpty() || aesKey.toByteArray().size in AES_KEY_LENGTHS) {
            "security.crypto.aesKey must be 16, 24 or 32 bytes"
        }
        aesIv = clean(aesIv)
        check(aesIv.isEmpty() || aesIv.toByteArray().size == AES_IV_LENGTH) {
            "security.crypto.aesIv must be $AES_IV_LENGTH bytes"
        }
    }

    private fun normalizePrivateKey(privateKey: String): String {
        return clean(privateKey).takeIf { it.isNotEmpty() }
            ?.let { RsaUtils.normalizePrivateKey(it) }
            .orEmpty()
    }

    private fun resolvePublicKey(privateKey: String, publicKey: String): String {
        val cleaned = clean(publicKey)
        if (cleaned.isNotEmpty() || privateKey.isEmpty()) {
            return cleaned
        }
        return RsaUtils.derivePublicKey(privateKey)
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
    }
}
