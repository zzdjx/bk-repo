package com.tencent.bkrepo.common.analysis.pojo.scanner

import com.tencent.bkrepo.common.analysis.pojo.scanner.arrowhead.ArrowheadDockerImage
import com.tencent.bkrepo.common.analysis.pojo.scanner.arrowhead.ArrowheadScanner
import com.tencent.bkrepo.common.analysis.pojo.scanner.arrowhead.KnowledgeBase
import com.tencent.bkrepo.common.analysis.pojo.scanner.standard.StandardScanner
import com.tencent.bkrepo.common.api.util.SecretMask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("扫描器配置密钥掩码")
class ScannerSecretsTest {

    @Test
    fun `mask arrowhead secrets and keep on placeholder update`() {
        val stored = arrowhead("sid", "skey", "pass")
        ScannerSecrets.mask(stored)
        assertEquals(SecretMask.MASKED, stored.knowledgeBase.secretId)
        assertEquals(SecretMask.MASKED, stored.knowledgeBase.secretKey)
        assertEquals(SecretMask.MASKED, stored.container.dockerRegistryPassword)
        assertEquals("http://kb", stored.knowledgeBase.endpoint)

        val incoming = ArrowheadScanner(
            name = "ah",
            version = "1.0",
            knowledgeBase = KnowledgeBase("http://kb-new", "*", ""),
            container = ArrowheadDockerImage("img:2", "user", "*")
        )
        val existing = arrowhead("sid", "skey", "pass")
        ScannerSecrets.keepExisting(incoming, existing)
        assertEquals("sid", incoming.knowledgeBase.secretId)
        assertEquals("skey", incoming.knowledgeBase.secretKey)
        assertEquals("pass", incoming.container.dockerRegistryPassword)
        assertEquals("http://kb-new", incoming.knowledgeBase.endpoint)
        assertEquals("img:2", incoming.container.image)
    }

    @Test
    fun `rotate standard scanner docker password`() {
        val stored = StandardScanner(
            name = "std",
            image = "img:1",
            dockerRegistryUsername = "user",
            dockerRegistryPassword = "old-pass",
            cmd = "scan"
        )
        val incoming = StandardScanner(
            name = "std",
            image = "img:1",
            dockerRegistryUsername = "user",
            dockerRegistryPassword = "new-pass",
            cmd = "scan"
        )
        ScannerSecrets.keepExisting(incoming, stored)
        assertEquals("new-pass", incoming.dockerRegistryPassword)
        ScannerSecrets.mask(stored)
        assertEquals(SecretMask.MASKED, stored.dockerRegistryPassword)
    }

    private fun arrowhead(secretId: String, secretKey: String, password: String) = ArrowheadScanner(
        name = "ah",
        version = "1.0",
        knowledgeBase = KnowledgeBase("http://kb", secretId, secretKey),
        container = ArrowheadDockerImage("img:1", "user", password)
    )
}
