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

package com.tencent.bkrepo.agent.memory

import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem

/**
 * 纯内存的 [BaseStore] 假实现，供 [MemoryFilesystemAccessTest] 使用，不需要真实 Redis 或
 * Mockito 驱动的假 Redis（[com.tencent.bkrepo.agent.task.LettuceStoreTest] 用的那种）——
 * 因为这里要测的是 [MemoryFilesystemAccess] 自己的命名空间/路由逻辑是否正确，不是 Redis 交互细节。
 */
class FakeBaseStore : BaseStore {

    private val values = mutableMapOf<String, MutableMap<String, MutableMap<String, Any>>>()
    private val versions = mutableMapOf<String, MutableMap<String, Long>>()

    override fun get(namespace: MutableList<String>, key: String): StoreItem? {
        val ns = nsKey(namespace)
        val value = values[ns]?.get(key) ?: return null
        return StoreItem(key, value, versions[ns]?.get(key) ?: 0L)
    }

    override fun put(namespace: MutableList<String>, key: String, value: MutableMap<String, Any>) {
        val ns = nsKey(namespace)
        values.getOrPut(ns) { mutableMapOf() }[key] = value
        val versionMap = versions.getOrPut(ns) { mutableMapOf() }
        versionMap[key] = (versionMap[key] ?: 0L) + 1
    }

    override fun putIfVersion(
        namespace: MutableList<String>,
        key: String,
        value: MutableMap<String, Any>,
        expectedVersion: Long,
    ): Boolean {
        val ns = nsKey(namespace)
        val versionMap = versions.getOrPut(ns) { mutableMapOf() }
        val current = versionMap[key] ?: 0L
        if (current != expectedVersion) {
            return false
        }
        values.getOrPut(ns) { mutableMapOf() }[key] = value
        versionMap[key] = current + 1
        return true
    }

    override fun search(namespace: MutableList<String>, limit: Int, offset: Int): MutableList<StoreItem> {
        val ns = nsKey(namespace)
        val keys = values[ns]?.keys?.sorted() ?: return mutableListOf()
        return keys.drop(offset).take(limit)
            .map { key -> StoreItem(key, values[ns]!!.getValue(key), versions[ns]?.get(key) ?: 0L) }
            .toMutableList()
    }

    override fun delete(namespace: MutableList<String>, key: String) {
        val ns = nsKey(namespace)
        values[ns]?.remove(key)
        versions[ns]?.remove(key)
    }

    private fun nsKey(namespace: List<String>): String = namespace.joinToString("\u0000")
}
