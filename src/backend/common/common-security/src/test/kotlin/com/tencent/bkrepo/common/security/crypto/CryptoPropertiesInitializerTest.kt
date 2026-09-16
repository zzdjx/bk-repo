package com.tencent.bkrepo.common.security.crypto

import cn.hutool.crypto.asymmetric.KeyType
import cn.hutool.crypto.asymmetric.RSA
import com.tencent.bkrepo.common.security.util.RsaUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Base64

@DisplayName("密钥归一化与校验测试")
internal class CryptoPropertiesInitializerTest {

    /**
     * 缺密钥不能拖垮启动：proxy 这类服务一把都用不上，报错要留到真正使用时
     */
    @Test
    @DisplayName("私钥未配置时启动不失败，用到才失败")
    fun testPrivateKeyRequiredOnUse() {
        val properties = properties(privateKeyStr = "")
        init(properties)
        Assertions.assertEquals("", properties.privateKeyStr)
        Assertions.assertEquals("", properties.publicKeyStr)

        RsaUtils(properties)
        val error = Assertions.assertThrows(IllegalStateException::class.java) {
            RsaUtils.encrypt("p1")
        }
        Assertions.assertTrue(error.message!!.contains("privateKeyStr is required"))

        // GET /rsa 直接把这个字段给前端，返回空串会把故障现场推到浏览器里
        val keyError = Assertions.assertThrows(IllegalStateException::class.java) {
            RsaUtils.publicKey
        }
        Assertions.assertTrue(keyError.message!!.contains("publicKeyStr is required"))
    }

    /**
     * 模板占位符没被替换时必须按"未配置"处理，否则会带着占位符往下走，
     * 错误停在 Base64 解码上，运维看不出是漏配
     */
    @Test
    @DisplayName("未替换的部署占位符按未配置处理")
    fun testUnresolvedPlaceholder() {
        listOf("__BK_REPO_CRYPTO_PRIVATE_KEY_STR__", "\${BK_REPO_CRYPTO_PRIVATE_KEY_STR}")
            .forEach { placeholder ->
                val properties = properties(privateKeyStr = placeholder)
                init(properties)
                Assertions.assertEquals("", properties.privateKeyStr, placeholder)
            }
    }

    @Test
    @DisplayName("只配私钥时公钥自动推导且与私钥配对")
    fun testDerivePublicKey() {
        val properties = properties(publicKeyStr = "")
        init(properties)

        Assertions.assertTrue(properties.publicKeyStr.isNotBlank())
        // 推导出的公钥必须真能和私钥配对，仅非空说明不了问题
        val rsa = RSA(ALGORITHM, properties.privateKeyStr, properties.publicKeyStr)
        val cipher = rsa.encryptBcd("p1", KeyType.PublicKey)
        Assertions.assertEquals("p1", rsa.decryptStr(cipher, KeyType.PrivateKey))
    }

    /**
     * helm 的 genCA 产出 PKCS#1 PEM，openssl 默认产出 PKCS#8，两种都要能吃进来且结果一致
     */
    @Test
    @DisplayName("PKCS#1 PEM 与 PKCS#8 归一化结果一致")
    fun testNormalizePkcs1Pem() {
        val pkcs8 = RSA().privateKeyBase64
        val pem = "-----BEGIN RSA PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(toPkcs1(pkcs8)).chunked(64).joinToString("\n") +
            "\n-----END RSA PRIVATE KEY-----"

        val properties = properties(privateKeyStr = pem, publicKeyStr = "")
        init(properties)

        Assertions.assertEquals(RsaUtils.normalizePrivateKey(pkcs8), properties.privateKeyStr)
    }

    @Test
    @DisplayName("AES 密钥长度非法时启动失败")
    fun testAesLength() {
        val keyError = Assertions.assertThrows(IllegalStateException::class.java) {
            init(properties(aesKey = "0".repeat(20)))
        }
        Assertions.assertTrue(keyError.message!!.contains("16, 24 or 32 bytes"))

        val ivError = Assertions.assertThrows(IllegalStateException::class.java) {
            init(properties(aesIv = "0".repeat(8)))
        }
        Assertions.assertTrue(ivError.message!!.contains("16 bytes"))
    }

    private fun init(properties: CryptoProperties) =
        CryptoPropertiesInitializer(properties).afterPropertiesSet()

    private fun properties(
        privateKeyStr: String = pkcs8Key,
        publicKeyStr: String = "",
        aesKey: String = "0".repeat(32),
        aesIv: String = "0".repeat(16)
    ) = CryptoProperties(
        rsaAlgorithm = ALGORITHM,
        privateKeyStr = privateKeyStr,
        publicKeyStr = publicKeyStr,
        privateKeyStr2048PKCS8 = pkcs8Key,
        privateKeyStr2048PKCS1 = pkcs8Key,
        aesKey = aesKey,
        aesIv = aesIv
    )

    /**
     * 剥掉 PKCS#8 固定 26 字节头拿到内层 PKCS#1，正好是生产代码 pkcs1ToPkcs8 的逆操作
     */
    private fun toPkcs1(pkcs8Base64: String): ByteArray {
        val der = Base64.getDecoder().decode(pkcs8Base64)
        return der.copyOfRange(PKCS8_HEADER_SIZE, der.size)
    }

    companion object {
        private const val ALGORITHM = "RSA/ECB/PKCS1Padding"
        private const val PKCS8_HEADER_SIZE = 26
        private val pkcs8Key: String = RSA().privateKeyBase64
    }
}
