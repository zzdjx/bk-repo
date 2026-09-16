package com.tencent.bkrepo.common.analysis.pojo.scanner

import com.tencent.bkrepo.common.analysis.pojo.scanner.arrowhead.ArrowheadScanner
import com.tencent.bkrepo.common.analysis.pojo.scanner.scanCodeCheck.scanner.ScancodeToolkitScanner
import com.tencent.bkrepo.common.analysis.pojo.scanner.standard.StandardScanner
import com.tencent.bkrepo.common.analysis.pojo.scanner.trivy.TrivyScanner
import com.tencent.bkrepo.common.api.util.SecretMask

object ScannerSecrets {
    fun mask(scanner: Scanner): Scanner {
        when (scanner) {
            is ArrowheadScanner -> {
                scanner.knowledgeBase.secretId = SecretMask.mask(scanner.knowledgeBase.secretId).orEmpty()
                scanner.knowledgeBase.secretKey = SecretMask.mask(scanner.knowledgeBase.secretKey).orEmpty()
                scanner.container.dockerRegistryPassword = SecretMask.mask(scanner.container.dockerRegistryPassword)
            }
            is StandardScanner -> {
                scanner.dockerRegistryPassword = SecretMask.mask(scanner.dockerRegistryPassword)
            }
            is TrivyScanner -> {
                scanner.container.dockerRegistryPassword = SecretMask.mask(scanner.container.dockerRegistryPassword)
            }
            is ScancodeToolkitScanner -> {
                scanner.container.dockerRegistryPassword = SecretMask.mask(scanner.container.dockerRegistryPassword)
            }
        }
        return scanner
    }

    fun keepExisting(incoming: Scanner, existing: Scanner): Scanner {
        when {
            incoming is ArrowheadScanner && existing is ArrowheadScanner -> {
                incoming.knowledgeBase.secretId =
                    SecretMask.keep(incoming.knowledgeBase.secretId, existing.knowledgeBase.secretId).orEmpty()
                incoming.knowledgeBase.secretKey =
                    SecretMask.keep(incoming.knowledgeBase.secretKey, existing.knowledgeBase.secretKey).orEmpty()
                incoming.container.dockerRegistryPassword = SecretMask.keep(
                    incoming.container.dockerRegistryPassword,
                    existing.container.dockerRegistryPassword
                )
            }
            incoming is StandardScanner && existing is StandardScanner -> {
                incoming.dockerRegistryPassword =
                    SecretMask.keep(incoming.dockerRegistryPassword, existing.dockerRegistryPassword)
            }
            incoming is TrivyScanner && existing is TrivyScanner -> {
                incoming.container.dockerRegistryPassword = SecretMask.keep(
                    incoming.container.dockerRegistryPassword,
                    existing.container.dockerRegistryPassword
                )
            }
            incoming is ScancodeToolkitScanner && existing is ScancodeToolkitScanner -> {
                incoming.container.dockerRegistryPassword = SecretMask.keep(
                    incoming.container.dockerRegistryPassword,
                    existing.container.dockerRegistryPassword
                )
            }
        }
        return incoming
    }
}
