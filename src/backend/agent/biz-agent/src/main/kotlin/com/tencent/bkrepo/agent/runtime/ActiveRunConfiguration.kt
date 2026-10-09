/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.runtime.store.ActiveRunStateStore
import com.tencent.bkrepo.agent.dao.AgentActiveRunDao
import com.tencent.bkrepo.agent.runtime.store.MongoActiveRunStateStore
import io.agentscope.core.state.AgentStateStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ActiveRunConfiguration {

    @Bean
    fun activeRunStateStore(
        properties: EffectiveAgentRuntimeProperties,
        dao: AgentActiveRunDao,
    ): ActiveRunStateStore = MongoActiveRunStateStore(dao = dao, lockTtl = properties.activeRunTtl)

    @Bean
    fun activeRunManager(
        stateStore: ActiveRunStateStore,
        agentStateStore: AgentStateStore,
    ): ActiveRunManager = ActiveRunManager(stateStore, agentStateStore)
}
