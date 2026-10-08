/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.common.redis.RedisOperation
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * 启动期集中校验 Redis 依赖，把"静默退化"变成"启动失败"。
 *
 * 背景：靠 Redis 的几处存储各自 `ObjectProvider.getIfAvailable()`，拿不到就退回进程内实现并只打一行
 * warn。本地开发需要这种退化，生产不需要——单副本能跑通、多副本才暴露问题，而这类故障往往是发布很久
 * 之后才被用户投诉出来的。
 *
 * 因此生产环境把 `agent.runtime.state.require-redis` 置为 true：缺 Redis 时直接让 Spring 上下文
 * 刷新失败，不给"带着一堆进程内存储对外提供服务"的机会。校验放在一处而不是散在各 Configuration
 * 里：各存储的 bean 可能先于本 bean 构造完成（那几行退化 warn 照旧会打），但只要上下文最终起不来，
 * 就不会有流量进来，fail-fast 的目的已经达到。
 *
 * ## 为什么只剩三项
 *
 * 会话状态、挂起中断、长期记忆、后台任务原本也在这张清单上。它们是持久化数据，被 Redis 的内存淘汰
 * 策略逐出就等于用户数据凭空消失，已经整体迁到 MongoDB（见 [AgentStateConfiguration]、
 * [com.tencent.bkrepo.agent.session.MongoAgentPendingInterruptStore]、
 * [AgentMemoryFilesystemConfiguration]、[AgentTaskRepositoryConfiguration]）。剩在 Redis 上的只有
 * 缓存（会话归属，真相在 Mongo `agent_session`）与协调原语（活跃运行互斥锁 + 停止标志、恢复幂等
 * 指纹）——这三类本来就该放 Redis：短 TTL、高频读写、丢了最多是多问一次数据库或多跑一次校验。
 *
 * 校验范围也随之收窄到 [RedisOperation]：不再需要原生 Lettuce 客户端，那层只有
 * `RedisAgentStateStore` 和旧的 `LettuceStore` 要，两者都已经不在了。
 *
 * 边界：这里只校验"Redis 客户端有没有被装配出来"，即防配置漏配；不做连通性探测（ping）。连通性是
 * 另一类问题（网络抖动、实例故障），用启动探测去卡会把瞬时抖动放大成起不来，交给健康检查与告警更合适。
 */
@Component
class AgentRedisRequirementValidator(
    private val properties: EffectiveAgentRuntimeProperties,
    private val redisOperation: ObjectProvider<RedisOperation>,
) {

    @PostConstruct
    fun validate() {
        if (redisOperation.getIfAvailable() != null) {
            logger.info("agent redis dependency check passed (requireRedis={})", properties.requireRedis)
            return
        }
        val detail = "missing=redisOperation, degraded=[$DEGRADED_CAPABILITIES]"
        if (properties.requireRedis) {
            throw IllegalStateException(
                "Agent requires redis but it is not configured: $detail. " +
                    "Configure spring redis for this service, " +
                    "or set $REQUIRE_REDIS_PROPERTY=false to explicitly accept in-memory degradation " +
                    "(single replica / local development only)."
            )
        }
        logger.warn(
            "Agent is running WITHOUT redis: {}. This is unsafe for multi-replica deployment; " +
                "set {}=true in production to fail fast instead.",
            detail,
            REQUIRE_REDIS_PROPERTY,
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentRedisRequirementValidator::class.java)

        const val REQUIRE_REDIS_PROPERTY = "agent.runtime.state.require-redis"

        private const val DEGRADED_CAPABILITIES = "sessionOwnership, activeRun, resumeIdempotency"
    }
}
