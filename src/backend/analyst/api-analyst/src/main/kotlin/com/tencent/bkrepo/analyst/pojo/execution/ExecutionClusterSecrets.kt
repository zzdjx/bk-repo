package com.tencent.bkrepo.analyst.pojo.execution

import com.tencent.bkrepo.common.api.util.SecretMask

object ExecutionClusterSecrets {
    fun mask(cluster: ExecutionCluster): ExecutionCluster = when (cluster) {
        is KubernetesJobExecutionCluster ->
            cluster.copy(kubernetesProperties = maskProps(cluster.kubernetesProperties))
        is KubernetesDeploymentExecutionCluster ->
            cluster.copy(kubernetesProperties = maskProps(cluster.kubernetesProperties))
        else -> cluster
    }

    fun keepExisting(incoming: ExecutionCluster, existing: ExecutionCluster): ExecutionCluster {
        val oldProps = k8sProps(existing) ?: return incoming
        return when (incoming) {
            is KubernetesJobExecutionCluster ->
                incoming.copy(kubernetesProperties = keepProps(incoming.kubernetesProperties, oldProps))
            is KubernetesDeploymentExecutionCluster ->
                incoming.copy(kubernetesProperties = keepProps(incoming.kubernetesProperties, oldProps))
            else -> incoming
        }
    }

    private fun maskProps(props: KubernetesExecutionClusterProperties) = props.copy(
        token = SecretMask.mask(props.token),
        clientKeyData = SecretMask.mask(props.clientKeyData),
        clientCertificateData = SecretMask.mask(props.clientCertificateData)
    )

    private fun keepProps(
        incoming: KubernetesExecutionClusterProperties,
        existing: KubernetesExecutionClusterProperties
    ) = incoming.copy(
        token = SecretMask.keep(incoming.token, existing.token),
        clientKeyData = SecretMask.keep(incoming.clientKeyData, existing.clientKeyData),
        clientCertificateData = SecretMask.keep(incoming.clientCertificateData, existing.clientCertificateData)
    )

    private fun k8sProps(cluster: ExecutionCluster): KubernetesExecutionClusterProperties? = when (cluster) {
        is KubernetesJobExecutionCluster -> cluster.kubernetesProperties
        is KubernetesDeploymentExecutionCluster -> cluster.kubernetesProperties
        else -> null
    }
}
