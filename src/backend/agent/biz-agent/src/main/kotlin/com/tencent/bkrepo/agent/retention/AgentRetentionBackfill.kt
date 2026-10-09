/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.retention

import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRetention
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
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
import java.time.Duration
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
 * ## 按 `retention.overrides` 分维度
 *
 * 逐条写入时 [AgentRetentionPolicy] 会按文档自己的 projectId/userId 匹配 `overrides`，这里也必须跟上，
 * 否则给某个项目单独配的长留存只对升级之后的新数据生效，升级之前那批照样按全局默认删掉——而需要长留存
 * 的恰恰常常是历史审计数据。
 *
 * 实现上不逐条判断，而是把配置拆成 [EffectiveAgentRetention.bucketsOf] 的有序分桶，一桶一条
 * `updateMulti`：先处理各条 override（名单条件 `projectId ∈ 名单 或 userId ∈ 名单`），最后用全局默认兜住
 * 剩下的。"取第一个命中的 override"这条优先级不需要额外表达——前面的桶已经把文档的 `expiresAt` 写上了，
 * 后面的桶靠"缺 `expiresAt`"这个条件自然跳过它们。
 *
 * 例外是 `agent_message`：这张表上没有 userId/projectId 字段（消息只按 threadId 归属会话），要分维度就得
 * 先回查 `agent_session`，成本与一次性回填不相称，因此它只按全局默认 `retention.message` 回填。影响面仅限
 * "给某项目配了超长 message 保留期、且该项目在升级前就有历史消息"这一种组合。
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
 * ## 读的是启动快照，改配置要重启
 *
 * 这里读的是 [EffectiveAgentRuntimeProperties]——启动时拍平固化的快照，本身也只在 [ApplicationReadyEvent]
 * 上跑一次，所以配置中心改 `retention.*` 不会触发重新回填。这是刻意的：回填是一次性迁移动作，不是持续生效
 * 的策略。改了保留期之后想让历史文档也跟着变，正确做法是重启（回填只认"缺字段"的文档，已经补过戳的不会被
 * 改回去，因此重启也不会修正上一次回填的结果）或者直接在 Mongo 上手工 `updateMulti`。
 *
 * 全模块只有 `agent.runtime.gray` 是热更新的，其余配置项——包括 `retention.*`——一律是启动期快照语义。
 *
 * ## 保留期配成"永不过期"时不补戳
 *
 * 保留期为 0 时 [AgentRetentionPolicy.expiryOf] 返回 `null`，这里直接跳过对应的桶。这一步不能省：Spring
 * Data 写入时会略掉值为 `null` 的字段，因此"配了永不过期"的新文档和"引入保留期之前"的老文档在库里长得一
 * 模一样，都是没有 `expiresAt` 字段——若不跳过，就会把用户明确要求永久保留的数据补上过期时刻。
 */
@Component
class AgentRetentionBackfill(
    private val mongoTemplate: MongoTemplate,
    private val retentionPolicy: AgentRetentionPolicy,
    properties: EffectiveAgentRuntimeProperties,
) {

    private val retention = properties.retention

    @EventListener(ApplicationReadyEvent::class)
    fun backfillMissingExpiry() {
        stampByDimension("agent_run_event", TAgentRunEvent::class.java, { it.runEvent }, retention.runEvent)
        stampByDimension("agent_run", TAgentRun::class.java, { it.run }, retention.run)
        stampByDimension("agent_tool_call", TAgentToolCall::class.java, { it.toolCall }, retention.toolCall)
        stampByDimension("agent_session", TAgentSession::class.java, { it.session }, retention.session)
        // agent_message 没有 userId/projectId，无法分维度，见类注释。
        stamp("agent_message", TAgentMessage::class.java, null, retentionPolicy.messageExpiry())
    }

    private fun stampByDimension(
        collection: String,
        type: Class<*>,
        pick: (EffectiveAgentRetention.Override) -> Duration?,
        global: Duration,
    ) {
        retention.bucketsOf(pick, global).forEach { bucket ->
            stamp(collection, type, bucketCriteria(bucket), retentionPolicy.expiryOf(bucket.retention))
        }
    }

    /** 兜底桶（名单都为空）返回 `null`，不加任何条件，匹配前面的桶没覆盖到的全部文档。 */
    private fun bucketCriteria(bucket: EffectiveAgentRetention.Bucket): Criteria? {
        val dimensions = buildList {
            bucket.projectIds.takeIf { it.isNotEmpty() }?.let { add(Criteria.where(PROJECT_ID).`in`(it)) }
            bucket.userIds.takeIf { it.isNotEmpty() }?.let { add(Criteria.where(USER_ID).`in`(it)) }
        }
        return when (dimensions.size) {
            0 -> null
            1 -> dimensions.single()
            else -> Criteria().orOperator(dimensions)
        }
    }

    private fun stamp(collection: String, type: Class<*>, dimension: Criteria?, expiresAt: Instant?) {
        if (expiresAt == null) {
            logger.info("Retention disabled for [$collection], skip expiresAt backfill")
            return
        }
        try {
            val missingExpiry = Criteria.where(EXPIRES_AT).exists(false)
            val query = Query(dimension?.let { missingExpiry.andOperator(it) } ?: missingExpiry)
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
        private val PROJECT_ID = TAgentRunEvent::projectId.name
        private val USER_ID = TAgentRunEvent::userId.name
    }
}
