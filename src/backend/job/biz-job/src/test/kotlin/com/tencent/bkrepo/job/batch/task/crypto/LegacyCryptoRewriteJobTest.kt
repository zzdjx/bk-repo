package com.tencent.bkrepo.job.batch.task.crypto

import cn.hutool.crypto.asymmetric.RSA
import com.tencent.bkrepo.common.security.crypto.CryptoProperties
import com.tencent.bkrepo.common.security.crypto.LegacyCryptoKeys
import com.tencent.bkrepo.common.security.util.RsaUtils
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("存量密文重加密测试")
@Suppress("DEPRECATION")
internal class LegacyCryptoRewriteJobTest {

    /**
     * 用旧默认密钥加密，等价于升级前入库的存量密文
     */
    private fun legacyCipher(plain: String): String {
        useKey(
            RsaUtils.normalizePrivateKey(LegacyCryptoKeys.RSA_1024_PRIVATE_KEY),
            RsaUtils.derivePublicKey(LegacyCryptoKeys.RSA_1024_PRIVATE_KEY)
        )
        val cipher = RsaUtils.encrypt(plain)
        useCurrentKey()
        return cipher
    }

    private fun useKey(privateKey: String, publicKey: String) {
        RsaUtils(
            CryptoProperties(
                rsaAlgorithm = ALGORITHM, privateKeyStr = privateKey, publicKeyStr = publicKey
            )
        )
    }

    private fun useCurrentKey() = useKey(current.privateKeyBase64, current.publicKeyBase64)

    @BeforeEach
    fun setUp() {
        current = RSA()
        useCurrentKey()
    }

    @Test
    @DisplayName("旧密文被换成当前密钥，明文不变")
    fun testRewriteLegacyCipher() {
        val cipher = legacyCipher("p1")
        val raw = remoteJson(cipher)

        val updated = LegacyCryptoRewriteJob.rewriteCiphers(raw)

        Assertions.assertNotEquals(raw, updated)
        Assertions.assertFalse(updated.contains(cipher))
        val newCipher = Regex("\"password\":\"([^\"]+)\"").find(updated)!!.groupValues[1]
        Assertions.assertEquals("p1", RsaUtils.decrypt(newCipher))
    }

    /**
     * 这条守的是不能整体 toJsonString：JsonUtils 关掉了 FAIL_ON_UNKNOWN_PROPERTIES，
     * 老版本写入、当前 POJO 不认识的字段会在反序列化时被吞掉，重新序列化就永久丢失
     */
    @Test
    @DisplayName("当前 POJO 不认识的字段必须原样保留")
    fun testKeepUnknownFields() {
        val cipher = legacyCipher("p1")
        val raw = """{"type":"remote","url":"https://a","legacyOnlyField":"keep-me",""" +
            """"credentials":{"username":"u","password":"$cipher","legacyKey":"keep-too"}}"""

        val updated = LegacyCryptoRewriteJob.rewriteCiphers(raw)

        Assertions.assertTrue(updated.contains("\"legacyOnlyField\":\"keep-me\""))
        Assertions.assertTrue(updated.contains("\"legacyKey\":\"keep-too\""))
        Assertions.assertFalse(updated.contains(cipher))
    }

    @Test
    @DisplayName("当前密钥的密文不动，可重复跑")
    fun testIdempotent() {
        val raw = remoteJson(RsaUtils.encrypt("p1"))
        Assertions.assertEquals(raw, LegacyCryptoRewriteJob.rewriteCiphers(raw))
    }

    /**
     * 两把密钥都解不开的值不能碰：当成明文再加密一次会变双层密文，原值就取不回来了
     */
    @Test
    @DisplayName("谁也解不开的值原样跳过")
    fun testSkipUndecryptable() {
        val raw = remoteJson("not-a-cipher")
        Assertions.assertEquals(raw, LegacyCryptoRewriteJob.rewriteCiphers(raw))
        Assertions.assertNull(LegacyCryptoRewriteJob.reEncryptOrNull("not-a-cipher"))
    }

    /**
     * padding 偶发碰撞会让错的密钥解出一串随机字节，这种「明文」回写会永久盖掉原密文
     */
    @Test
    @DisplayName("解出的明文不像密码时原样跳过")
    fun testSkipImplausiblePlaintext() {
        val cipher = legacyCipher("\u0000\u0001binary")
        Assertions.assertNull(LegacyCryptoRewriteJob.reEncryptOrNull(cipher))
    }

    @Test
    @DisplayName("composite 多个代理源逐个处理，只换旧的")
    fun testCompositeChannels() {
        val legacy = legacyCipher("p1")
        val currentCipher = RsaUtils.encrypt("p2")
        val raw = """{"type":"composite","proxy":{"channelList":[""" +
            """{"public":false,"name":"c1","url":"https://a","password":"$legacy"},""" +
            """{"public":false,"name":"c2","url":"https://b","password":"$currentCipher"}]}}"""

        val updated = LegacyCryptoRewriteJob.rewriteCiphers(raw)

        Assertions.assertFalse(updated.contains(legacy))
        Assertions.assertTrue(updated.contains(currentCipher))
    }

    @Test
    @DisplayName("本地仓库没有密码字段，不产生改动")
    fun testLocalConfigUntouched() {
        val raw = """{"type":"local","settings":{"a":"b"}}"""
        Assertions.assertEquals(raw, LegacyCryptoRewriteJob.rewriteCiphers(raw))
    }

    private fun remoteJson(cipher: String) =
        """{"type":"remote","url":"https://a","credentials":{"username":"u","password":"$cipher"}}"""

    companion object {
        private const val ALGORITHM = "RSA/ECB/PKCS1Padding"
        private lateinit var current: RSA
    }
}
