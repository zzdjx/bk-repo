package com.tencent.bkrepo.common.artifact.crypt

import com.tencent.bkrepo.common.security.crypto.CryptoProperties
import com.tencent.bkrepo.common.security.crypto.CryptoPropertiesInitializer
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ArtifactCryptConfiguration {

    // initializer 作为入参，保证 CryptKeyDecryptor 拿到的是归一化后的密钥
    @Bean
    @ConditionalOnProperty(name = ["artifact.crypt.enabled"], havingValue = "true")
    fun cryptFilter(
        cryptoProperties: CryptoProperties,
        initializer: CryptoPropertiesInitializer
    ): CryptFilter {
        val decryptor = CryptKeyDecryptor(cryptoProperties)
        return CryptFilter(decryptor)
    }
}