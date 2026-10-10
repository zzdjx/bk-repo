/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.model

import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.CompoundIndexes
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * 会话维度的运行态，对应 Mongo 集合 `agent_active_run`。
 *
 * 一个会话同一时刻只允许一个 run 在跑，这条文档就是那把运行权锁：文档存在且未过期 == 锁被占着。
 * [scopeKey] 上的唯一索引是并发抢占的最终仲裁者，抢锁是一次 `findAndModify(upsert)`，撞索引即失败。
 *
 * 之前这份状态是 Redis 上的三个 key（锁、活跃 runId、停止标志）。合成一个文档不只是换存储：三个 key
 * 没法一起原子更新，续期时只能拿"活跃 run 绑定"当所有权凭证间接比对；现在 run 归属和过期时间在同一条
 * 文档里，续期就是一次带 [runId] 条件的单文档更新，不需要任何间接推断。
 */
@Document("agent_active_run")
@CompoundIndexes(
    CompoundIndex(
        name = "scopeKey_idx",
        def = "{'scopeKey': 1}",
        unique = true,
        background = true,
    ),
)
data class TAgentActiveRun(
    var id: String? = null,
    /** `userId:threadId`，运行权的粒度。 */
    var scopeKey: String,
    /** 抢到锁到绑定 runId 之间有一小段空窗，此时为 `null`。 */
    var runId: String? = null,
    /**
     * 跨副本停止信号，存的是"要停哪个 run"而不是一个布尔：持有该 run 的副本轮询到即自行中止。
     *
     * 记 runId 解决了两件事。一是停止请求可能先于 [runId] 绑定到达——run 记录已经建好、用户就点了停止，
     * 而此时锁文档上的 [runId] 还是 `null`；记成布尔的话这个信号无处安放，只能另外维护一张进程内的
     * "早到停止"表，而那张表天然是单副本的。二是上一轮 run 残留的停止信号不会误伤下一轮：runId 对不上
     * 自然不生效，不需要在绑定新 run 时专门清一次。
     */
    var stopRequestedRunId: String? = null,
    /**
     * 运行权的到期时间，由 [com.tencent.bkrepo.agent.runtime.ActiveRunLockHeartbeat] 持续续期。
     *
     * 它同时是 TTL 索引的目标，但正确性不依赖 TTL：Mongo 的 TTL 线程约 60s 才扫一轮，过期文档在这个
     * 窗口里仍然读得到，所以抢锁和读取都按 [expiresAt] 自己判一次。TTL 索引只负责回收持锁副本崩溃后
     * 再也没人来抢的残留文档。
     */
    @Indexed(expireAfterSeconds = 0, background = true)
    var expiresAt: Instant,
)
