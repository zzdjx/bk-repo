/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.task

import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.bkrepo.agent.dao.AgentKvStoreDao
import com.tencent.bkrepo.agent.model.TAgentKvStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.Instant

/**
 * [MongoStore] 单测。
 *
 * 用一张内存 Map 驱动 [AgentKvStoreDao] 的 mock，不去断言具体的 Query/Update 文本——那是
 * Spring Data 的事，断它只会把测试焊死在写法上。这里验证的是 [MongoStore] 对外暴露的 `BaseStore`
 * 行为语义：CAS、分页、命名空间隔离、保留期与续期、以及 [MongoStore.scanNamespaces] 的 glob 过滤。
 *
 * 假 dao 照着真实现的语义实现版本自增与 CAS 判定（`put` 无条件 +1，`putIfVersion` 只在版本相符时写入，
 * 期望版本 0 表示"仅当不存在"），因此 CAS 的并发裁决逻辑在这里是真被执行的，而不是被 stub 掉。
 */
@DisplayName("MongoStore单测")
class MongoStoreTest {

    private val records = mutableMapOf<Triple<String, String, String>, TAgentKvStore>()
    private lateinit var dao: AgentKvStoreDao

    @BeforeEach
    fun setUp() {
        records.clear()
        dao = mock<AgentKvStoreDao>()
        whenever(dao.findItem(any(), any(), any())).thenAnswer { inv ->
            records[inv.key()]
        }
        whenever(dao.put(any(), any(), any(), any(), anyOrNull())).thenAnswer { inv ->
            val key = inv.key()
            val existing = records[key]
            val record = TAgentKvStore(
                scope = inv.getArgument(0),
                namespace = inv.getArgument(1),
                key = inv.getArgument(2),
                value = inv.getArgument(3),
                version = (existing?.version ?: 0L) + 1,
                expiresAt = inv.getArgument(4),
            )
            records[key] = record
            record
        }
        whenever(dao.putIfVersion(any(), any(), any(), any(), any(), anyOrNull())).thenAnswer { inv ->
            val key = inv.key()
            val expected = inv.getArgument<Long>(4)
            val current = records[key]?.version ?: 0L
            if (current != expected) {
                return@thenAnswer false
            }
            records[key] = TAgentKvStore(
                scope = inv.getArgument(0),
                namespace = inv.getArgument(1),
                key = inv.getArgument(2),
                value = inv.getArgument(3),
                version = current + 1,
                expiresAt = inv.getArgument(5),
            )
            true
        }
        whenever(dao.listByNamespace(any(), any(), any(), any())).thenAnswer { inv ->
            val scope = inv.getArgument<String>(0)
            val namespace = inv.getArgument<String>(1)
            records.values
                .filter { it.scope == scope && it.namespace == namespace }
                .sortedBy { it.key }
                .drop(inv.getArgument(3))
                .take(inv.getArgument(2))
        }
        whenever(dao.removeItem(any(), any(), any())).thenAnswer { inv ->
            records.remove(inv.key())
            Unit
        }
        whenever(dao.touch(any(), any(), any(), any())).thenAnswer { inv ->
            val scope = inv.getArgument<String>(0)
            val namespace = inv.getArgument<String>(1)
            val keys = inv.getArgument<Collection<String>>(2)
            val expiresAt = inv.getArgument<Instant>(3)
            keys.forEach { records[Triple(scope, namespace, it)]?.expiresAt = expiresAt }
            Unit
        }
        whenever(dao.distinctNamespaces(any())).thenAnswer { inv ->
            records.values.filter { it.scope == inv.getArgument<String>(0) }.map { it.namespace }.distinct()
        }
    }

    private fun storeWith(ttl: Duration? = null, refreshTtlOnRead: Boolean = false) = MongoStore(
        dao = dao,
        scope = MongoStore.Scope.TASK,
        objectMapper = ObjectMapper(),
        ttl = ttl,
        refreshTtlOnRead = refreshTtlOnRead,
    )

    @Test
    fun `写入后应能读回值与版本`() {
        val store = storeWith()
        store.put(mutableListOf("a", "b"), "k", mutableMapOf("x" to 1))

        val item = store.get(mutableListOf("a", "b"), "k")

        assertNotNull(item)
        assertEquals(1L, item!!.version())
        assertEquals(1, item.value()["x"])
    }

    @Test
    fun `同名 key 在不同命名空间下互不可见`() {
        val store = storeWith()
        store.put(mutableListOf("a"), "k", mutableMapOf("ns" to "a"))
        store.put(mutableListOf("b"), "k", mutableMapOf("ns" to "b"))

        assertEquals("a", store.get(mutableListOf("a"), "k")!!.value()["ns"])
        assertEquals("b", store.get(mutableListOf("b"), "k")!!.value()["ns"])
    }

    @Test
    fun `每次 put 都应把版本加一`() {
        val store = storeWith()
        store.put(mutableListOf("a"), "k", mutableMapOf("v" to 1))
        store.put(mutableListOf("a"), "k", mutableMapOf("v" to 2))

        assertEquals(2L, store.get(mutableListOf("a"), "k")!!.version())
    }

    /** `WorkspaceTaskRepository` 把它当跨副本 CAS 原语用，版本不符必须写不进去。 */
    @Test
    fun `putIfVersion 版本不符时应失败且不改动已有值`() {
        val store = storeWith()
        store.put(mutableListOf("a"), "k", mutableMapOf("v" to "original"))

        val accepted = store.putIfVersion(mutableListOf("a"), "k", mutableMapOf("v" to "stale"), 99L)

        assertFalse(accepted)
        assertEquals("original", store.get(mutableListOf("a"), "k")!!.value()["v"])
    }

    @Test
    fun `putIfVersion 版本相符时应写入并把版本加一`() {
        val store = storeWith()
        store.put(mutableListOf("a"), "k", mutableMapOf("v" to "original"))

        val accepted = store.putIfVersion(mutableListOf("a"), "k", mutableMapOf("v" to "next"), 1L)

        assertTrue(accepted)
        val item = store.get(mutableListOf("a"), "k")!!
        assertEquals("next", item.value()["v"])
        assertEquals(2L, item.version())
    }

    /** 期望版本 0 的语义是"仅当不存在时才创建"，第二次必须被拒——这正是并发创建的裁决点。 */
    @Test
    fun `putIfVersion 期望版本为零时只应创建一次`() {
        val store = storeWith()

        assertTrue(store.putIfVersion(mutableListOf("a"), "k", mutableMapOf("v" to "first"), 0L))
        assertFalse(store.putIfVersion(mutableListOf("a"), "k", mutableMapOf("v" to "second"), 0L))
        assertEquals("first", store.get(mutableListOf("a"), "k")!!.value()["v"])
    }

    @Test
    fun `search 应按 key 字典序分页`() {
        val store = storeWith()
        listOf("c", "a", "d", "b").forEach { store.put(mutableListOf("ns"), it, mutableMapOf("k" to it)) }

        assertEquals(listOf("a", "b"), store.search(mutableListOf("ns"), 2, 0).map { it.key() })
        assertEquals(listOf("c", "d"), store.search(mutableListOf("ns"), 2, 2).map { it.key() })
    }

    @Test
    fun `search 的 limit 非正时应直接返回空且不查库`() {
        val store = storeWith()
        store.put(mutableListOf("ns"), "k", mutableMapOf("v" to 1))

        assertTrue(store.search(mutableListOf("ns"), 0, 0).isEmpty())
    }

    @Test
    fun `delete 之后应读不到`() {
        val store = storeWith()
        store.put(mutableListOf("ns"), "k", mutableMapOf("v" to 1))

        store.delete(mutableListOf("ns"), "k")

        assertNull(store.get(mutableListOf("ns"), "k"))
    }

    @Test
    fun `配了保留期时写入应带上过期时刻`() {
        val store = storeWith(ttl = Duration.ofDays(7))
        store.put(mutableListOf("ns"), "k", mutableMapOf("v" to 1))

        assertNotNull(records.values.single().expiresAt)
    }

    @Test
    fun `没配保留期时不应设置过期时刻`() {
        val store = storeWith()
        store.put(mutableListOf("ns"), "k", mutableMapOf("v" to 1))

        assertNull(records.values.single().expiresAt)
    }

    /**
     * Mongo 的 TTL 线程约 60s 才扫一轮，过期文档在这个窗口里仍然查得到。
     * store 自己不判一次，用户早该消失的记忆就会被当成有效内容返回。
     */
    @Test
    fun `已过期的记录即使还在库里也应读不到`() {
        val store = storeWith(ttl = Duration.ofDays(7))
        store.put(mutableListOf("ns"), "k", mutableMapOf("v" to 1))
        records.values.single().expiresAt = Instant.now().minusSeconds(1)

        assertNull(store.get(mutableListOf("ns"), "k"))
        assertTrue(store.search(mutableListOf("ns"), 10, 0).isEmpty())
    }

    @Test
    fun `开启读续期时 get 应把过期时刻往后推`() {
        val store = storeWith(ttl = Duration.ofDays(7), refreshTtlOnRead = true)
        store.put(mutableListOf("ns"), "k", mutableMapOf("v" to 1))
        val soon = Instant.now().plusSeconds(60)
        records.values.single().expiresAt = soon

        store.get(mutableListOf("ns"), "k")

        assertTrue(records.values.single().expiresAt!!.isAfter(soon))
    }

    /** 后台任务用的是这一侧：事后翻查一条旧任务不应该延长它的寿命。 */
    @Test
    fun `关闭读续期时 get 不应改动过期时刻`() {
        val store = storeWith(ttl = Duration.ofDays(7))
        store.put(mutableListOf("ns"), "k", mutableMapOf("v" to 1))
        val soon = Instant.now().plusSeconds(60)
        records.values.single().expiresAt = soon

        store.get(mutableListOf("ns"), "k")

        assertEquals(soon, records.values.single().expiresAt)
    }

    /** `*` 要能跨 `\u0000` 分隔的段通配，否则 AgentMemoryMaintenanceJob 的用户反查会一个都匹配不上。 */
    @Test
    fun `scanNamespaces 的通配符应跨命名空间段匹配`() {
        val store = storeWith()
        store.put(mutableListOf("agents", "bk", "users", "alice", "memory"), "k", mutableMapOf("v" to 1))
        store.put(mutableListOf("agents", "bk", "users", "bob", "memory"), "k", mutableMapOf("v" to 1))
        store.put(mutableListOf("agents", "bk", "tasks"), "k", mutableMapOf("v" to 1))

        val matched = store.scanNamespaces("agents\u0000bk\u0000users\u0000*\u0000memory")

        assertEquals(2, matched.size)
        assertTrue(matched.all { it.endsWith("\u0000memory") })
    }

    @Test
    fun `scanNamespaces 应把 glob 里的正则特殊字符当字面值`() {
        val store = storeWith()
        store.put(mutableListOf("a.b"), "k", mutableMapOf("v" to 1))
        store.put(mutableListOf("axb"), "k", mutableMapOf("v" to 1))

        assertEquals(listOf("a.b"), store.scanNamespaces("a.b"))
    }

    @Test
    fun `空 key 与含 NUL 的 key 应被拒绝`() {
        val store = storeWith()

        assertThrows<IllegalArgumentException> { store.put(mutableListOf("ns"), "", mutableMapOf()) }
        assertThrows<IllegalArgumentException> { store.put(mutableListOf("ns"), "a\u0000b", mutableMapOf()) }
    }

    @Test
    fun `putIfVersion 的期望版本不能为负`() {
        val store = storeWith()

        assertThrows<IllegalArgumentException> {
            store.putIfVersion(mutableListOf("ns"), "k", mutableMapOf(), -1L)
        }
    }

    private fun org.mockito.invocation.InvocationOnMock.key(): Triple<String, String, String> =
        Triple(getArgument(0), getArgument(1), getArgument(2))
}
