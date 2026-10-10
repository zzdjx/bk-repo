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
 * 框架 `BaseStore` 的 Mongo 承载，对应集合 `agent_kv_store`。
 *
 * 长期记忆与后台任务两份数据结构完全同构（都是 namespace + key -> 带版本的 JSON），只是保留期不同，
 * 因此共用一张表、用 [scope] 区分。TTL 存的是绝对时刻而不是"写入后多少秒"，所以同一个 TTL 索引就能
 * 同时服务两种保留期，不需要拆集合。
 */
@Document("agent_kv_store")
@CompoundIndexes(
    CompoundIndex(
        name = "scope_namespace_key_idx",
        def = "{'scope': 1, 'namespace': 1, 'key': 1}",
        unique = true,
        background = true,
    ),
)
data class TAgentKvStore(
    var id: String? = null,
    /** 数据归属，见 [com.tencent.bkrepo.agent.task.MongoStore.Scope]。 */
    var scope: String,
    /** 命名空间各段用 `\u0000` 拼接。拼成一个串而不是数组，是为了让 `(scope, namespace, key)` 能走同一个索引。 */
    var namespace: String,
    var key: String,
    /** JSON 序列化的 `Map<String, Any>`；不展开成子文档，避免 map key 里的点号撞上 Mongo 的字段名限制。 */
    var value: String,
    /** 乐观锁版本，由 `$inc` 维护，`putIfVersion` 的 CAS 就比它。 */
    var version: Long,
    /** 到期由 Mongo TTL 索引回收；`null` 表示不参与清理。 */
    @Indexed(expireAfterSeconds = 0, background = true)
    var expiresAt: Instant? = null,
)
