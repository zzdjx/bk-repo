/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.session

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.dao.AgentPendingInterruptDao
import com.tencent.bkrepo.agent.dao.AgentResumeMarkDao
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AgentSessionConfiguration {

    /** 落 Mongo 的理由见 [MongoAgentPendingInterruptStore]：这是持久化状态，不是缓存。 */
    @Bean
    fun agentPendingInterruptStore(
        properties: EffectiveAgentRuntimeProperties,
        dao: AgentPendingInterruptDao,
        objectMapper: ObjectMapper,
    ): AgentPendingInterruptStore = MongoAgentPendingInterruptStore(
        dao = dao,
        objectMapper = objectMapper,
        ttl = properties.sessionTtl,
    )

    /** 幂等窗口取 `activeRunTtl`：超过这个时长原来那个 run 早已不在，重放已经没有意义。 */
    @Bean
    fun agentResumeIdempotencyStore(
        properties: EffectiveAgentRuntimeProperties,
        dao: AgentResumeMarkDao,
    ): AgentResumeIdempotencyStore = MongoAgentResumeIdempotencyStore(
        dao = dao,
        ttl = properties.activeRunTtl,
    )
}
