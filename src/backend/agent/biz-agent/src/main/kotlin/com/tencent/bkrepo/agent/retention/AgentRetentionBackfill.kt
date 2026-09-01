/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.retention

import com.tencent.bkrepo.agent.model.TAgentMessage
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.model.TAgentRunEvent
import com.tencent.bkrepo.agent.model.TAgentSession
import com.tencent.bkrepo.agent.model.TAgentToolCall
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 给引入保留期之前写入的历史文档补上 `expiresAt`，否则它们会永远留在库里——TTL 索引只删"字段值是日期
 * 且已过期"的文档，字段缺失的一概跳过。
 *
 * ## 为什么统一按"从现在起再放一个完整保留期"而不是按文档自己的创建时间算
 *
 * 按创建时间算需要用聚合管道更新（`$set: {expiresAt: {$add: ["$startedAt", 保留期]}}`），而且五张表的时间
 * 字段名各不相同（`startedAt`/`calledAt`/`createdAt`/`updatedAt`），还会让升级瞬间就删掉一批已经超期的旧
 * 数据。统一给一个完整窗口的代价只是旧数据多留一段时间，换来的是一条最简单的 `updateMulti` 和"升级不会
 * 顺手删数据"的确定性，对一次性迁移来说更划算。
 *
 * ## 执行时机与幂等性
 *
 * 挂在 [ApplicationReadyEvent] 上、每个副本每次启动都跑一遍。查询条件是"缺 `expiresAt` 字段"，走的正是
 * TTL 索引（普通单字段索引会把缺失字段当 null 索引），第一次补完之后再启动就匹配不到任何文档，等于空转；
 * 多副本同时执行也只是重复做同一个幂等更新，不需要分布式锁。
 *
 * 唯一需要留意的是升级后第一次启动：如果历史数据量很大，这次 `updateMulti` 会阻塞启动线程一段时间。
 * 当前 Agent 的这几张表规模都很小，因此没有再做分批；后续量级上来时可以改成分页游标。
 *
 * ## 保留期配成"永不过期"时不补戳
 *
 * 保留期为 0 时 [AgentRetentionPolicy] 返回 `null`，这里直接跳过对应集合。这一步不能省：Spring Data 写入
 * 时会略掉值为 `null` 的字段，因此"配了永不过期"的新文档和"引入保留期之前"的老文档在库里长得一模一样，
 * 都是没有 `expiresAt` 字段——若不跳过，就会把用户明确要求永久保留的数据补上过期时刻。
 */
@Component
class AgentRetentionBackfill(
    private val mongoTemplate: MongoTemplate,
    private val retentionPolicy: AgentRetentionPolicy,
) {

    @EventListener(ApplicationReadyEvent::class)
    fun backfillMissingExpiry() {
        stamp("agent_run_event", TAgentRunEvent::class.java, retentionPolicy.runEventExpiry())
        stamp("agent_run", TAgentRun::class.java, retentionPolicy.runExpiry())
        stamp("agent_tool_call", TAgentToolCall::class.java, retentionPolicy.toolCallExpiry())
        stamp("agent_message", TAgentMessage::class.java, retentionPolicy.messageExpiry())
        stamp("agent_session", TAgentSession::class.java, retentionPolicy.sessionExpiry())
    }

    private fun stamp(collection: String, type: Class<*>, expiresAt: Instant?) {
        if (expiresAt == null) {
            logger.info("Retention disabled for [$collection], skip expiresAt backfill")
            return
        }
        try {
            val query = Query(Criteria.where(EXPIRES_AT).exists(false))
            val modified = mongoTemplate.updateMulti(query, Update().set(EXPIRES_AT, expiresAt), type).modifiedCount
            if (modified > 0) {
                logger.info("Backfilled expiresAt[$expiresAt] for $modified legacy docs in [$collection]")
            }
        } catch (ignored: Exception) {
            // 兜底迁移失败只影响历史数据的清理，不能拖累启动：新写入的文档已经带上 expiresAt。
            logger.warn("Failed to backfill expiresAt in [$collection]", ignored)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentRetentionBackfill::class.java)
        private val EXPIRES_AT = TAgentRunEvent::expiresAt.name
    }
}
