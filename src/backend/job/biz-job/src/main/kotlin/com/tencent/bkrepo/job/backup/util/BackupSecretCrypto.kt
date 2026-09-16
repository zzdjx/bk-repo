package com.tencent.bkrepo.job.backup.util

import cn.hutool.crypto.CryptoException
import com.tencent.bkrepo.common.api.exception.BadRequestException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.security.util.AESUtils
import com.tencent.bkrepo.job.backup.config.DataBackupConfig
import org.springframework.stereotype.Component

/**
 * 备份文件中的敏感字段加密。无 [PREFIX] 的旧备份按明文兼容恢复。
 */
@Component
class BackupSecretCrypto(
    private val dataBackupConfig: DataBackupConfig,
) {
    fun encrypt(plain: String): String {
        if (plain.isEmpty() || plain.startsWith(PREFIX)) {
            return plain
        }
        return PREFIX + AESUtils.encrypt(plain, requireKey())
    }

    fun decrypt(value: String): String {
        if (value.isEmpty() || !value.startsWith(PREFIX)) {
            return value
        }
        try {
            return AESUtils.decrypt(value.removePrefix(PREFIX), requireKey())
        } catch (_: CryptoException) {
            throw BadRequestException(
                CommonMessageCode.PARAMETER_INVALID,
                "backup.encrypt-key does not match backup ciphertext"
            )
        }
    }

    private fun requireKey(): String {
        val key = dataBackupConfig.encryptKey
        if (key.toByteArray().size !in KEY_LENGTHS) {
            throw BadRequestException(
                CommonMessageCode.PARAMETER_INVALID,
                "backup.encrypt-key must be 16, 24 or 32 bytes; " +
                    "set BK_REPO_BACKUP_ENCRYPT_KEY or backup.encrypt-key"
            )
        }
        return key
    }

    companion object {
        const val PREFIX = "enc:"
        private val KEY_LENGTHS = setOf(16, 24, 32)
    }
}
