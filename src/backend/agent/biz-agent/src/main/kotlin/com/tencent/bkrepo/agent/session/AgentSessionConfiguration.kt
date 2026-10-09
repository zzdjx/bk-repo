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
import com.tencent.bkrepo.agent.hitl.AgentInterruptStateRepository
import com.tencent.bkrepo.agent.hitl.DefaultAgentInterruptStateRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AgentSessionConfiguration {

    /**
     * 两个 store 只在这里 `new`，不各自注册成 bean——这是刻意的，为的是让"绕过 HITL 门面"从一个
     * 需要靠 review 发现的疏忽，变成容器启动就报 `NoSuchBeanDefinition` 的硬错误。
     *
     * 起因是一次真实的回归：[AgentInterruptStateRepository.getPendingInterrupt] 会把整组已过期的确认
     * 卡片当成"没有未决中断"，而 `AgentChatServiceImpl` 当时直接注入了 `AgentPendingInterruptStore`
     * 自己问底层，于是状态接口报"有未决中断"、resume 校验却说没有，前端渲染出一张点不动的卡片。
     * 门面当时就存在，它的 KDoc 里也写了"分散判断迟早会出现两处不一致"，但 public 的 store bean 让
     * 绕过门面毫无阻力。
     *
     * 代价是这两个 store 的装配参数（TTL 取值）挪到了这里，而不是跟各自的实现类放在一起。换来的是
     * 读写 pending interrupt 只剩门面一条路，编译期可验证。
     *
     * 落 Mongo 而不是缓存的理由见 [MongoAgentPendingInterruptStore]：这是持久化状态。
     * resume 幂等窗口取 `activeRunTtl`：超过这个时长原来那个 run 早已不在，重放没有意义。
     */
    @Bean
    fun agentInterruptStateRepository(
        properties: EffectiveAgentRuntimeProperties,
        pendingInterruptDao: AgentPendingInterruptDao,
        resumeMarkDao: AgentResumeMarkDao,
        objectMapper: ObjectMapper,
    ): AgentInterruptStateRepository = DefaultAgentInterruptStateRepository(
        pendingInterruptStore = MongoAgentPendingInterruptStore(
            dao = pendingInterruptDao,
            objectMapper = objectMapper,
            ttl = properties.sessionTtl,
        ),
        resumeIdempotencyStore = MongoAgentResumeIdempotencyStore(
            dao = resumeMarkDao,
            ttl = properties.activeRunTtl,
        ),
    )
}
