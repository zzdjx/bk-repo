package com.tencent.bkrepo.common.security.crypto

import com.tencent.bkrepo.common.security.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.core.MongoTemplate

/**
 * 将 CryptoProperties 持久化到 Mongo。同步 Mongo 不在 classpath 时（如 reactive driver）跳过，
 * 避免解析 [com.tencent.bkrepo.common.mongo.MongoAutoConfiguration] 导致启动失败。
 */
@AutoConfiguration(
    afterName = ["com.tencent.bkrepo.common.mongo.MongoAutoConfiguration"],
    before = [SecurityAutoConfiguration::class],
)
@ConditionalOnClass(
    value = [MongoTemplate::class],
    name = ["com.tencent.bkrepo.common.mongo.MongoAutoConfiguration"],
)
class CryptoMongoConfiguration {

    @Bean
    @ConditionalOnBean(MongoTemplate::class)
    fun cryptoPropertiesStore(mongoTemplate: MongoTemplate): CryptoPropertiesStore {
        return MongoCryptoPropertiesStore(mongoTemplate)
    }
}
