/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.session.AgentPendingInterruptStore
import com.tencent.bkrepo.agent.session.AgentResumeIdempotencyStore
import com.tencent.bkrepo.agent.session.PendingInterruptSession
import com.tencent.bkrepo.agent.session.PendingInterruptSnapshot
import java.time.Instant

/**
 * HITL 运行态统一入口：pending interrupt 与 resume 幂等。
 *
 * 主线 run 编排只依赖此接口，具体存储由 session 层实现：pending interrupt 落 Mongo
 * （[com.tencent.bkrepo.agent.session.MongoAgentPendingInterruptStore]），resume 幂等落 Mongo
 * （只是防重放的短期标记，靠唯一索引保证原子性，到期由 TTL 索引回收）。
 */
interface AgentInterruptStateRepository {

    fun savePendingInterrupt(threadId: String, session: PendingInterruptSession)

    fun getPendingInterrupt(threadId: String): PendingInterruptSession?

    fun clearPendingInterrupt(threadId: String)

    /** @return true 表示首次见到该 resume 指纹 */
    fun tryMarkResume(threadId: String, interruptId: String, fingerprint: String): Boolean
}

/** 由 [com.tencent.bkrepo.agent.session.AgentSessionConfiguration] 装配，底层两个 store 刻意不是 bean。 */
class DefaultAgentInterruptStateRepository(
    private val pendingInterruptStore: AgentPendingInterruptStore,
    private val resumeIdempotencyStore: AgentResumeIdempotencyStore,
) : AgentInterruptStateRepository {

    override fun savePendingInterrupt(threadId: String, session: PendingInterruptSession) {
        pendingInterruptStore.save(threadId, session)
    }

    /**
     * 整组确认卡片都过期时视同"没有未决中断"，并顺手把记录删掉。
     *
     * 不这么做会把会话锁死：卡片上的 `expiresAt` 按 `activeRunTtl`（默认 11 分钟）填，而
     * `agent_pending_interrupt` 文档的 TTL 是 `sessionTtl`（默认 30 天）。11 分钟之后用户点卡片会被
     * [AguiResumeValidator] 判为过期，发新消息又会被判为"thread 上有未决 interrupt 必须带 resume"，
     * 两条路都堵死，要一直等到那份文档过期。过期的确认卡片本来就不该再拦住任何人。
     *
     * 判断放在这个唯一的读入口而不是各个调用方：resume 校验、拒绝审计、coordinator 回灌、运行状态
     * 查询、断线重连回放全都走这里，分散判断迟早会出现某一处认为"还有未决中断"、另一处认为没有。
     *
     * 只在**整组**过期时才算数。同一份快照里的卡片是同一时刻按同一个 TTL 写的，正常会一起过期；万一
     * 出现部分过期，[AguiResumeValidator.assertNotExpired] 仍会逐条拦住过期的那几张——此时宁可继续
     * 要求 resume，也不能把还有效的卡片一起丢掉。
     */
    override fun getPendingInterrupt(threadId: String): PendingInterruptSession? {
        val session = pendingInterruptStore.get(threadId) ?: return null
        if (session.interrupts.isNotEmpty() && session.interrupts.all { it.isExpired() }) {
            pendingInterruptStore.clear(threadId)
            return null
        }
        return session
    }

    /** `expiresAt` 缺失按"不过期"处理，与 [AguiResumeValidator.assertNotExpired] 保持一致。 */
    private fun PendingInterruptSnapshot.isExpired(): Boolean {
        val deadline = expiresAt?.takeIf { it.isNotBlank() } ?: return false
        return runCatching { Instant.parse(deadline) }.getOrNull()?.isBefore(Instant.now()) ?: true
    }

    override fun clearPendingInterrupt(threadId: String) {
        pendingInterruptStore.clear(threadId)
    }

    override fun tryMarkResume(threadId: String, interruptId: String, fingerprint: String): Boolean =
        resumeIdempotencyStore.tryMark(threadId, interruptId, fingerprint)
}
