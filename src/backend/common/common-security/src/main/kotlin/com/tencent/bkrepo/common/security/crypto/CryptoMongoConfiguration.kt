package com.tencent.bkrepo.common.security.crypto

import com.tencent.bkrepo.common.mongo.MongoAutoConfiguration
import com.tencent.bkrepo.common.security.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.core.MongoTemplate

@AutoConfiguration(
    after = [MongoAutoConfiguration::class],
    before = [SecurityAutoConfiguration::class]
)
@ConditionalOnClass(MongoTemplate::class)
class CryptoMongoConfiguration {

    @Bean
    @ConditionalOnBean(MongoTemplate::class)
    fun cryptoPropertiesStore(mongoTemplate: MongoTemplate): CryptoPropertiesStore {
        return MongoCryptoPropertiesStore(mongoTemplate)
    }
}
