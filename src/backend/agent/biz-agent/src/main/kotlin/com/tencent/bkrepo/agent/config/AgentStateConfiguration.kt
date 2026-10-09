/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.config

import com.mongodb.client.MongoClient
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import io.agentscope.core.state.AgentStateStore
import io.agentscope.extensions.mongodb.state.MongoAgentStateStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.MongoDatabaseFactory

@Configuration(proxyBeanMethods = false)
class AgentStateConfiguration {

    /**
     * 会话状态落 MongoDB，与 `agent_session` / `agent_run` 等业务集合同库同连接池。
     *
     * ## 为什么不是 Redis
     *
     * `RedisAgentStateStore` 到 2.0.4 都没有 TTL 入口——它底下的 `RedisClientAdapter` 只暴露
     * `set(key, value)`，整个 redis extension 里没有 `expire`/`setex`/`ttl` 的调用——所以会话状态写进
     * Redis 之后永不过期，只能靠外部任务扫 key 去清。而会话状态是需要长期可回溯的持久化数据，被 Redis
     * 的内存淘汰策略逐出就等于会话凭空失忆，本来也不该和缓存共用一套存储。换 Mongo 之后到期由 TTL 索引
     * （框架按 `agent.runtime.state.ttl-days` 建）自动回收。
     *
     * ## 集合名为什么是写死的
     *
     * [STATE_COLLECTION] 不开放配置，和本模块其余八张集合（都在各自 `@Document` 注解里写死）保持一致。
     * 单独给它一个配置项，只会让人以为集合名整体可配，实际改了也只搬得动这一张表。
     *
     * ## 没有降级分支
     *
     * Mongo 是 bk-repo 全服务的硬依赖（所有 dao 都依赖它），拿不到 client 就不是"降级到内存"能糊过去的
     * 场景。此前 Redis 版本留了 [io.agentscope.core.state.InMemoryAgentStateStore] 兜底，代价是 Redis
     * 不可用时服务照常起来、会话却在重启和跨副本之间静默丢失；这里不保留这个行为。
     *
     * client 的生命周期归 Spring Boot 的 `MongoAutoConfiguration` 管，store 的 `close()` 不能调，
     * 否则会把整个服务的 Mongo 连接一起关掉——因此这里不注册为 destroyMethod。
     */
    @Bean(destroyMethod = "")
    fun agentStateStore(
        properties: EffectiveAgentRuntimeProperties,
        mongoClient: MongoClient,
        mongoDatabaseFactory: MongoDatabaseFactory,
    ): AgentStateStore {
        val databaseName = mongoDatabaseFactory.mongoDatabase.name
        logger.info(
            "Agent state store: mongodb db[$databaseName] collection[$STATE_COLLECTION] " +
                "ttlDays[${properties.state.ttlDays}]"
        )
        return MongoAgentStateStore.builder()
            .mongoClient(mongoClient)
            .databaseName(databaseName)
            .collectionName(STATE_COLLECTION)
            .ttlDays(properties.state.ttlDays)
            .build()
    }

    companion object {
        private const val STATE_COLLECTION = "agent_state"
        private val logger = LoggerFactory.getLogger(AgentStateConfiguration::class.java)
    }
}
