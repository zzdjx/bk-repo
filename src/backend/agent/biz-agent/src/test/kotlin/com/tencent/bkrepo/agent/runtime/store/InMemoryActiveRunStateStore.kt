/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.runtime.store

import java.util.concurrent.ConcurrentHashMap

/**
 * [ActiveRunStateStore] 的内存替身，仅供测试。
 *
 * 生产实现是 [MongoActiveRunStateStore]，不存在内存降级路径——这份实现放在 `src/test` 就是为了让这件事
 * 在目录结构上一眼可见：它不实现过期语义（锁永不过期），拿它当生产降级会悄悄丢掉死锁回收。
 */
class InMemoryActiveRunStateStore : ActiveRunStateStore {

    private data class Entry(var runId: String? = null, var stopRequestedRunId: String? = null)

    private val entries = ConcurrentHashMap<String, Entry>()

    override fun tryAcquireLock(userId: String, threadId: String): Boolean {
        return entries.putIfAbsent(scopeKey(userId, threadId), Entry()) == null
    }

    /** 内存存储没有过期，续期只需回答"运行权还是不是这个 run 的"。 */
    override fun renewLock(userId: String, threadId: String, runId: String): Boolean {
        return entries[scopeKey(userId, threadId)]?.runId == runId
    }

    override fun isLockHeld(userId: String, threadId: String): Boolean {
        return entries.containsKey(scopeKey(userId, threadId))
    }

    override fun bindActiveRun(userId: String, threadId: String, runId: String) {
        entries.compute(scopeKey(userId, threadId)) { _, existing ->
            (existing ?: Entry()).apply { this.runId = runId }
        }
    }

    override fun getActiveRunId(userId: String, threadId: String): String? {
        return entries[scopeKey(userId, threadId)]?.runId
    }

    override fun releaseRun(userId: String, threadId: String, runId: String) {
        entries.computeIfPresent(scopeKey(userId, threadId)) { _, existing ->
            if (existing.runId == runId) null else existing
        }
    }

    override fun clear(userId: String, threadId: String) {
        entries.remove(scopeKey(userId, threadId))
    }

    override fun requestStop(userId: String, threadId: String, runId: String) {
        entries.computeIfPresent(scopeKey(userId, threadId)) { _, existing ->
            existing.apply { stopRequestedRunId = runId }
        }
    }

    override fun isStopRequested(userId: String, threadId: String, runId: String): Boolean {
        return entries[scopeKey(userId, threadId)]?.stopRequestedRunId == runId
    }

    private fun scopeKey(userId: String, threadId: String): String = "$userId:$threadId"
}
