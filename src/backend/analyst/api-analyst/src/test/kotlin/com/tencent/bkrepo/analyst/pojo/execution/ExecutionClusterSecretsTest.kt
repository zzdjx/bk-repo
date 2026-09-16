package com.tencent.bkrepo.analyst.pojo.execution

import com.tencent.bkrepo.common.api.util.SecretMask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("执行集群密钥掩码")
class ExecutionClusterSecretsTest {

    @Test
    fun `mask k8s credentials and keep when placeholder`() {
        val stored = jobCluster("k8s-token", "client-key", "client-cert")
        val masked = ExecutionClusterSecrets.mask(stored) as KubernetesJobExecutionCluster
        assertEquals(SecretMask.MASKED, masked.kubernetesProperties.token)
        assertEquals(SecretMask.MASKED, masked.kubernetesProperties.clientKeyData)
        assertEquals(SecretMask.MASKED, masked.kubernetesProperties.clientCertificateData)
        assertEquals("cluster-desc", masked.description)
        assertEquals("k8s-token", stored.kubernetesProperties.token)
        assertNotSame(stored, masked)

        val incoming = jobCluster("*", "", "new-cert")
        val merged = ExecutionClusterSecrets.keepExisting(incoming, stored) as KubernetesJobExecutionCluster
        assertEquals("k8s-token", merged.kubernetesProperties.token)
        assertEquals("client-key", merged.kubernetesProperties.clientKeyData)
        assertEquals("new-cert", merged.kubernetesProperties.clientCertificateData)
        assertEquals("cluster-desc", merged.description)
        assertEquals("*", incoming.kubernetesProperties.token)
    }

    @Test
    fun `docker cluster has no secrets to mask`() {
        val cluster = DockerExecutionCluster(name = "docker")
        val masked = ExecutionClusterSecrets.mask(cluster)
        assertEquals(cluster, masked)
    }

    private fun jobCluster(token: String, key: String, cert: String) = KubernetesJobExecutionCluster(
        name = "k8s",
        description = "cluster-desc",
        kubernetesProperties = KubernetesExecutionClusterProperties(
            token = token,
            clientKeyData = key,
            clientCertificateData = cert
        )
    )
}
