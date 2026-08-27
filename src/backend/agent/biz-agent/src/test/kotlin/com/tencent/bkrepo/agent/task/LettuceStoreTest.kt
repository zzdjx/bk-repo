/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.bkrepo.agent.task

import io.lettuce.core.Limit
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer

/**
 * [LettuceStore] 单测。
 *
 * 用一个基于 [Answer] 的内存假 Redis（哈希 + 有序集合两张表）驱动 [RedisCommands] mock，
 * 不去断言具体 Lua 脚本文本——脚本文本本身是从框架自带的
 * `io.agentscope.extensions.redis.store.RedisStore`（已随 AgentScope 发布验证过）逐字迁移的，
 * 这里只验证 [LettuceStore] 对外暴露的 `BaseStore` 行为语义（CAS、分页、命名空间隔离）是否正确。
 *
 * `eval` 调用按 vararg 元素个数区分是 put（2 个：value、key）、putIfVersion（3 个：value、key、
 * expectedVersion）还是 delete（1 个：key）——个数足以稳定区分，不依赖脚本文本内容。
 */
@DisplayName("LettuceStore单测")
class LettuceStoreTest {

    /** key -> (field -> value)，模拟 Redis Hash：item hash 存 value/version 两个字段。 */
    private val hashes = mutableMapOf<String, MutableMap<String, String>>()

    /** key -> 有序成员列表（测试里 key 本身按预期字典序构造，用 sorted() 模拟 ZRANGEBYLEX）。 */
    private val sortedSets = mutableMapOf<String, MutableList<String>>()

    private lateinit var store: LettuceStore

    @BeforeEach
    fun setUp() {
        hashes.clear()
        sortedSets.clear()
        val commands: RedisCommands<String, String> =
            mock(defaultAnswer = Answer { invocation -> fakeRedis(invocation) })
        val connection = mock<StatefulRedisConnection<String, String>>()
        whenever(connection.sync()).thenReturn(commands)
        val client = mock<RedisClient>()
        whenever(client.connect()).thenReturn(connection)
        store = LettuceStore(client, "test:task-store:")
    }

    @Suppress("UNCHECKED_CAST")
    private fun fakeRedis(invocation: InvocationOnMock): Any? {
        return when (invocation.method.name) {
            "hgetall" -> {
                val key = invocation.arguments[0] as String
                hashes[key] ?: emptyMap<String, String>()
            }

            "eval" -> {
                // 真实 eval 签名是 eval(script, type, K[] keys, V... values)：values 是 vararg 参数，
                // Mockito 的 InvocationOnMock.getArguments() 会把 vararg 展开成一个个独立参数而不是
                // 保留成数组（keys 是普通数组参数，不受影响），所以这里要用 drop(3) 收集剩余参数，
                // 不能像 keys 那样直接把 arguments[3] 当数组强转。
                val keys = invocation.arguments[2] as Array<String>
                val values = invocation.arguments.drop(3).map { it as String }
                val itemKey = keys[0]
                val idxKey = keys[1]
                when (values.size) {
                    2 -> { // put(namespace, key, value)
                        val (json, member) = values
                        val newVersion = (hashes[itemKey]?.get("version")?.toLong() ?: 0L) + 1
                        hashes[itemKey] = mutableMapOf("value" to json, "version" to newVersion.toString())
                        sortedSets.getOrPut(idxKey) { mutableListOf() }.let { if (member !in it) it.add(member) }
                        newVersion.toString()
                    }

                    3 -> { // putIfVersion(namespace, key, value, expectedVersion)
                        val (json, member, expectedVersionStr) = values
                        val expectedVersion = expectedVersionStr.toLong()
                        val currentVersion = hashes[itemKey]?.get("version")?.toLong() ?: 0L
                        if (currentVersion != expectedVersion) {
                            "0"
                        } else {
                            val newVersion = currentVersion + 1
                            hashes[itemKey] = mutableMapOf("value" to json, "version" to newVersion.toString())
                            sortedSets.getOrPut(idxKey) { mutableListOf() }.let { if (member !in it) it.add(member) }
                            newVersion.toString()
                        }
                    }

                    1 -> { // delete(namespace, key)
                        val member = values[0]
                        hashes.remove(itemKey)
                        sortedSets[idxKey]?.remove(member)
                        1L
                    }

                    else -> error("unexpected eval values arity: ${values.size}")
                }
            }

            "zrangebylex" -> {
                val idxKey = invocation.arguments[0] as String
                val limit = invocation.arguments[2] as Limit
                val all = sortedSets[idxKey].orEmpty().sorted()
                val offset = limit.offset.toInt()
                val count = limit.count.toInt()
                if (offset >= all.size) {
                    mutableListOf()
                } else {
                    all.subList(offset, minOf(all.size, offset + count)).toMutableList()
                }
            }

            else -> null
        }
    }

    @Test
    fun `get在key不存在时返回null`() {
        assertNull(store.get(mutableListOf("agents", "coordinator", "tasks"), "missing"))
    }

    @Test
    fun `put写入后get应读到相同内容且version从1开始递增`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        store.put(ns, "task-1.json", mutableMapOf<String, Any>("content" to "hello"))

        val item = store.get(ns, "task-1.json")
        assertEquals("hello", item!!.value()["content"])
        assertEquals(1L, item.version())

        store.put(ns, "task-1.json", mutableMapOf<String, Any>("content" to "world"))
        val updated = store.get(ns, "task-1.json")
        assertEquals("world", updated!!.value()["content"])
        assertEquals(2L, updated.version())
    }

    @Test
    fun `putIfVersion在expectedVersion为0且key不存在时应create成功`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        val written = store.putIfVersion(ns, "task-1.json", mutableMapOf<String, Any>("content" to "v1"), 0L)
        assertTrue(written)
        assertEquals(1L, store.get(ns, "task-1.json")!!.version())
    }

    @Test
    fun `putIfVersion在expectedVersion为0且key已存在时应CAS失败`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        store.put(ns, "task-1.json", mutableMapOf<String, Any>("content" to "v1"))

        val written =
            store.putIfVersion(ns, "task-1.json", mutableMapOf<String, Any>("content" to "should-not-land"), 0L)

        assertFalse(written)
        assertEquals("v1", store.get(ns, "task-1.json")!!.value()["content"])
    }

    @Test
    fun `putIfVersion在版本匹配时应写入并版本号加一`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        store.put(ns, "task-1.json", mutableMapOf<String, Any>("content" to "v1"))
        val current = store.get(ns, "task-1.json")!!

        val written =
            store.putIfVersion(ns, "task-1.json", mutableMapOf<String, Any>("content" to "v2"), current.version())

        assertTrue(written)
        val updated = store.get(ns, "task-1.json")!!
        assertEquals("v2", updated.value()["content"])
        assertEquals(current.version() + 1, updated.version())
    }

    @Test
    fun `delete之后get应返回null且search不再列出该条目`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        store.put(ns, "task-1.json", mutableMapOf<String, Any>("content" to "v1"))

        store.delete(ns, "task-1.json")

        assertNull(store.get(ns, "task-1.json"))
        assertTrue(store.search(ns, 100, 0).isEmpty())
    }

    @Test
    fun `search应按offset和limit分页返回`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        for (i in 1..5) {
            store.put(ns, "session-$i.json", mutableMapOf<String, Any>("idx" to i))
        }

        val page1 = store.search(ns, 2, 0)
        val page2 = store.search(ns, 2, 2)
        val page3 = store.search(ns, 2, 4)

        assertEquals(listOf("session-1.json", "session-2.json"), page1.map { it.key() })
        assertEquals(listOf("session-3.json", "session-4.json"), page2.map { it.key() })
        assertEquals(listOf("session-5.json"), page3.map { it.key() })
    }

    @Test
    fun `不同命名空间的同名key应彼此隔离`() {
        val nsA = mutableListOf("agents", "coordinator-a", "tasks")
        val nsB = mutableListOf("agents", "coordinator-b", "tasks")

        store.put(nsA, "task-1.json", mutableMapOf<String, Any>("owner" to "a"))
        store.put(nsB, "task-1.json", mutableMapOf<String, Any>("owner" to "b"))

        assertEquals("a", store.get(nsA, "task-1.json")!!.value()["owner"])
        assertEquals("b", store.get(nsB, "task-1.json")!!.value()["owner"])
        assertEquals(1, store.search(nsA, 100, 0).size)
        assertEquals(1, store.search(nsB, 100, 0).size)
    }

    @Test
    fun `search在limit小于等于0时应直接返回空列表不查Redis`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        store.put(ns, "task-1.json", mutableMapOf<String, Any>("content" to "v1"))

        assertTrue(store.search(ns, 0, 0).isEmpty())
        assertTrue(store.search(ns, -1, 0).isEmpty())
    }

    @Test
    fun `key包含NUL字符时应拒绝`() {
        val ns = mutableListOf("agents", "coordinator", "tasks")
        assertThrows(IllegalArgumentException::class.java) {
            store.put(ns, "bad\u0000key", mutableMapOf<String, Any>("content" to "v1"))
        }
    }
}
