/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.retention

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 把 `agent.runtime.retention` 的保留期换算成写入 Mongo 的 `expiresAt` 时刻。
 *
 * ## 为什么是"写入时打戳"而不是直接在 `createdAt` 上挂 TTL 索引
 *
 * Mongo 的 TTL 索引可以直接建在业务时间字段上（`expireAfterSeconds = 90 天` + 索引建在 `startedAt`），
 * 那样连新字段都不用加，历史文档也立刻生效。但 Spring Data 的 `@Indexed(expireAfter = ...)` 只接受编译期
 * 常量，保留期就写死在注解里了——想按环境调整只能手工 `collMod` 改索引，配置中心失去作用。
 *
 * 因此这里沿用 `agent_run_event` 已有的做法：索引统一是 `expireAfter = "0s"` 挂在 `expiresAt` 上
 * （"到点即删"），具体保留多久由写入时算好的时刻决定。代价是保留期只对之后新写入的文档生效，已有文档保持
 * 旧的过期时刻；以及历史上没有这个字段的文档永远不会被 TTL 清理——后者由 [AgentRetentionBackfill] 兜底。
 *
 * ## 返回 `null` 的含义
 *
 * 保留期配成 `0`（或负值）表示"永不过期"，此时返回 `null`，写入的 `expiresAt` 就是空值。Mongo 的 TTL
 * 索引只处理值为日期的文档，字段缺失或为 `null` 的文档会被跳过，所以"不写 `expiresAt`"天然等价于
 * "这条数据不参与自动清理"，不需要额外的开关字段。
 */
@Component
class AgentRetentionPolicy(
    private val properties: EffectiveAgentRuntimeProperties,
) {

    fun runEventExpiry(): Instant? = expiryOf(properties.retention.runEvent)

    fun runExpiry(): Instant? = expiryOf(properties.retention.run)

    fun toolCallExpiry(): Instant? = expiryOf(properties.retention.toolCall)

    fun messageExpiry(): Instant? = expiryOf(properties.retention.message)

    /**
     * 会话元数据行的过期时刻。
     *
     * 每次 run 开始都会重算一次并覆盖旧值（见 `AgentSessionDao.touchSession`），所以语义是
     * "最后活跃之后再放多久"——只要用户还在用这个会话，它就不会过期。
     */
    fun sessionExpiry(): Instant? = expiryOf(properties.retention.session)

    private fun expiryOf(retention: Duration): Instant? {
        if (retention.isZero || retention.isNegative) {
            return null
        }
        return Instant.now().plus(retention)
    }
}
