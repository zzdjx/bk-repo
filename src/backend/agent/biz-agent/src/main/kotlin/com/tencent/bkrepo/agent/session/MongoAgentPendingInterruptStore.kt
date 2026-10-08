/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.session

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.dao.AgentPendingInterruptDao
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * 待确认中断现场落 MongoDB。
 *
 * 这份数据是持久化状态而不是缓存：丢了意味着用户手上那张确认卡片永远点不动——
 * [com.tencent.bkrepo.agent.hitl.AguiResumeValidator] 找不到 pending interrupt 会直接拒掉 resume，
 * 而且它还是 [com.tencent.bkrepo.agent.hitl.AguiResumeCoordinatorHydrator] 跨副本重建框架协调器
 * 内存表的唯一来源。放 Redis 时它是全库唯一一份、又会被内存淘汰策略逐出，风险和收益不匹配。
 */
class MongoAgentPendingInterruptStore(
    private val dao: AgentPendingInterruptDao,
    private val objectMapper: ObjectMapper,
    private val ttl: Duration,
) : AgentPendingInterruptStore {

    override fun save(threadId: String, session: PendingInterruptSession) {
        dao.savePayload(
            threadId = threadId,
            payload = objectMapper.writeValueAsString(session),
            expiresAt = Instant.now().plus(ttl),
        )
    }

    override fun get(threadId: String): PendingInterruptSession? {
        val record = dao.findByThreadId(threadId) ?: return null
        // Mongo 的 TTL 线程约 60s 才扫一轮，过期文档在这个窗口里仍然读得到。
        // 这里按 expiresAt 自己判一次，免得过期的确认卡片又能被 resume 一次。
        if (record.expiresAt.isBefore(Instant.now())) {
            return null
        }
        return try {
            objectMapper.readValue(record.payload, PendingInterruptSession::class.java)
        } catch (exception: Exception) {
            // 结构变更导致的反序列化失败按"没有待确认中断"处理：拒掉 resume 比用半截现场继续跑安全。
            logger.warn("Failed to deserialize pending interrupt for thread[$threadId]", exception)
            null
        }
    }

    override fun clear(threadId: String) {
        dao.removeByThreadId(threadId)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MongoAgentPendingInterruptStore::class.java)
    }
}
