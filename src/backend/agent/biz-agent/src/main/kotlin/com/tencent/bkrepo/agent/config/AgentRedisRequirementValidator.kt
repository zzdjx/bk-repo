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
import io.lettuce.core.RedisClient
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.stereotype.Component

/**
 * 启动期集中校验 Redis 依赖，把"静默退化"变成"启动失败"。
 *
 * 背景：Agent 有六处存储各自 `ObjectProvider.getIfAvailable()`，拿不到 Redis 就退回进程内实现并
 * 只打一行 warn（会话归属、挂起中断、恢复幂等、活跃运行、后台任务、长期记忆）。本地开发需要这种
 * 退化，生产不需要——单副本能跑通、多副本才暴露问题（会话飘到另一个副本就丢上下文、确认卡片恢复
 * 不了、后台任务查不到），而这类故障往往是发布很久之后才被用户投诉出来的。
 *
 * 因此生产环境把 `agent.runtime.state.require-redis` 置为 true：缺 Redis 时直接让 Spring 上下文
 * 刷新失败，不给"带着一堆进程内存储对外提供服务"的机会。校验放在一处而不是散在各 Configuration
 * 里：各存储的 bean 可能先于本 bean 构造完成（那几行退化 warn 照旧会打），但只要上下文最终起不来，
 * 就不会有流量进来，fail-fast 的目的已经达到。
 *
 * 边界：这里只校验"Redis 客户端有没有被装配出来"，即防配置漏配；不做连通性探测（ping）。连通性是
 * 另一类问题（网络抖动、实例故障），用启动探测去卡会把瞬时抖动放大成起不来，交给健康检查与告警更合适。
 */
@Component
class AgentRedisRequirementValidator(
    private val properties: EffectiveAgentRuntimeProperties,
    private val connectionFactory: ObjectProvider<RedisConnectionFactory>,
    private val redisOperation: ObjectProvider<RedisOperation>,
) {

    @PostConstruct
    fun validate() {
        val missing = missingDependencies()
        if (missing.isEmpty()) {
            logger.info("agent redis dependency check passed (requireRedis={})", properties.requireRedis)
            return
        }
        val detail = "missing=${missing.joinToString(",")}, degraded=[$DEGRADED_CAPABILITIES]"
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

    private fun missingDependencies(): List<String> = buildList {
        // RedisAgentStateStore / LettuceStore 需要原生 Lettuce client（比 RedisOperation 低一层），
        // 两者缺任意一个都会有存储退化，所以分开报，运维看日志就知道是哪层没配上。
        val lettuceClient = (connectionFactory.getIfAvailable() as? LettuceConnectionFactory)
            ?.nativeClient as? RedisClient
        if (lettuceClient == null) {
            add("lettuceRedisClient")
        }
        if (redisOperation.getIfAvailable() == null) {
            add("redisOperation")
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentRedisRequirementValidator::class.java)

        const val REQUIRE_REDIS_PROPERTY = "agent.runtime.state.require-redis"

        private const val DEGRADED_CAPABILITIES =
            "agentState, sessionOwnership, pendingInterrupt, resumeIdempotency, activeRun, " +
                "backgroundTask, longTermMemory"
    }
}
