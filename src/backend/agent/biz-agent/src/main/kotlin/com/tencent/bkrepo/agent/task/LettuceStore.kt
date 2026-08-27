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

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem
import io.lettuce.core.Limit
import io.lettuce.core.Range
import io.lettuce.core.RedisClient
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands

/**
 * 基于 Lettuce 的 [BaseStore] 实现，行为对齐框架自带的
 * `io.agentscope.extensions.redis.store.RedisStore`（Jedis 版本），
 * 只是把底层客户端换成项目里统一使用的 Lettuce，避免额外引入 Jedis。
 *
 * ## Key 布局（与框架 RedisStore 完全一致）
 *
 * 对于命名空间 `[a, b, c]` 下 key 为 `k` 的一条记录，使用两个 Redis key：
 * - **item hash**：`<prefix>item:<ns>\u0000<k>` —— 一个 Redis Hash，字段 `value`（JSON 序列化的
 *   `Map<String, Any?>`）与 `version`（字符串化的 long）。
 * - **namespace index**：`<prefix>idx:<ns>` —— 一个 Sorted Set（所有 score 都是 0），保存该命名空间下
 *   所有的 `k`，使 [search] 可以用 `ZRANGEBYLEX` 分页枚举而不用扫描整个 keyspace。
 *
 * `<ns>` 是命名空间各段用 `\u0000` 拼接的结果。
 *
 * ## 并发语义
 *
 * [put] 与 [putIfVersion] 都通过单条 Lua `EVAL` 执行，把"读版本 + 写 hash + 更新索引"合并成一次原子
 * Redis 操作，因此 [putIfVersion] 可以安全地作为跨进程的分布式 CAS 原语使用
 * （这正是 [io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository] 依赖的语义）。
 *
 * [search] 不与 [put] 处于同一事务：索引成员可能短暂指向一个 hash 尚未写入的记录（`ZADD` 与 `HSET`
 * 之间的窗口，已被上面的 Lua 原子性关闭），或指向一个刚被并发删除的记录。这里的实现容忍后者，跳过缺失的记录。
 *
 * 之所以自建而不是直接使用框架自带的 `RedisDistributedStore`/`RedisStore`，是因为它们强绑定
 * `redis.clients.jedis.UnifiedJedis`，而本项目所有其它 Redis 相关代码
 * （[io.agentscope.extensions.redis.state.RedisAgentStateStore]、`RedisLock` 等）统一使用 Lettuce，
 * 引入 Jedis 会让同一个服务里并存两套 Redis 客户端。
 */
class LettuceStore(
    lettuceClient: RedisClient,
    keyPrefix: String = DEFAULT_KEY_PREFIX,
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : BaseStore {

    /**
     * 每个 [LettuceStore] 独占一条连接。Lettuce 的连接是线程安全的（内部按命令排队/多路复用），
     * 可以被多个线程并发共享，不需要连接池。
     *
     * 这条连接由本类自己创建，因此生命周期也由本类负责；但由于本类实例通常是进程级单例（Spring bean），
     * 只会在 JVM 退出时才需要释放，不在此处显式管理关闭。
     */
    private val connection: StatefulRedisConnection<String, String> = lettuceClient.connect()
    private val commands: RedisCommands<String, String> = connection.sync()
    private val keyPrefix: String = normalizePrefix(keyPrefix)

    override fun get(namespace: MutableList<String>, key: String): StoreItem? {
        validateKey(key)
        val hash = commands.hgetall(itemKey(namespace, key))
        if (hash.isNullOrEmpty()) {
            return null
        }
        return toItem(key, hash)
    }

    override fun put(namespace: MutableList<String>, key: String, value: MutableMap<String, Any>) {
        validateKey(key)
        val itemKey = itemKey(namespace, key)
        val idxKey = indexKey(namespace)
        val json = serialize(value)
        commands.eval<String>(PUT_SCRIPT, ScriptOutputType.VALUE, arrayOf(itemKey, idxKey), json, key)
    }

    override fun putIfVersion(
        namespace: MutableList<String>,
        key: String,
        value: MutableMap<String, Any>,
        expectedVersion: Long,
    ): Boolean {
        validateKey(key)
        require(expectedVersion >= 0) { "expectedVersion must be non-negative" }
        val itemKey = itemKey(namespace, key)
        val idxKey = indexKey(namespace)
        val json = serialize(value)
        val result = commands.eval<String>(
            PUT_IF_VERSION_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf(itemKey, idxKey),
            json,
            key,
            expectedVersion.toString(),
        )
        return result != "0"
    }

    override fun search(namespace: MutableList<String>, limit: Int, offset: Int): MutableList<StoreItem> {
        if (limit <= 0) {
            return mutableListOf()
        }
        val safeOffset = maxOf(offset, 0)
        val idxKey = indexKey(namespace)
        val keys = commands.zrangebylex(
            idxKey,
            Range.unbounded<String>(),
            Limit.create(safeOffset.toLong(), limit.toLong()),
        )
        if (keys.isNullOrEmpty()) {
            return mutableListOf()
        }
        val items = mutableListOf<StoreItem>()
        for (k in keys) {
            val hash = commands.hgetall(itemKey(namespace, k))
            if (hash.isNullOrEmpty()) {
                continue // 索引里的陈旧条目——容忍
            }
            items.add(toItem(k, hash))
        }
        return items
    }

    override fun delete(namespace: MutableList<String>, key: String) {
        validateKey(key)
        val itemKey = itemKey(namespace, key)
        val idxKey = indexKey(namespace)
        commands.eval<Long>(DELETE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(itemKey, idxKey), key)
    }

    private fun toItem(key: String, hash: Map<String, String>): StoreItem {
        val json = hash["value"]
        val version = hash["version"]?.toLongOrNull() ?: 0L
        val value = deserialize(json)
        return StoreItem(key, value, version)
    }

    private fun serialize(value: Map<String, Any?>?): String =
        objectMapper.writeValueAsString(value ?: emptyMap<String, Any?>())

    private fun deserialize(json: String?): MutableMap<String, Any> {
        if (json.isNullOrEmpty()) {
            return mutableMapOf()
        }
        return objectMapper.readValue(json, object : TypeReference<MutableMap<String, Any>>() {})
    }

    private fun itemKey(namespace: List<String>, key: String): String =
        keyPrefix + "item:" + namespacePath(namespace) + NS_SEPARATOR + key

    private fun indexKey(namespace: List<String>): String =
        keyPrefix + "idx:" + namespacePath(namespace)

    companion object {
        /** 与框架 `RedisStore.DEFAULT_KEY_PREFIX` 保持同构风格的默认前缀。 */
        const val DEFAULT_KEY_PREFIX = "bkrepo:agent:task-store:"

        private const val NS_SEPARATOR = "\u0000"

        /** 原子写入：无条件把版本 +1，写入 value + version，并写入索引条目。返回新版本号（字符串）。 */
        private const val PUT_SCRIPT =
            "local v = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') + 1 " +
                "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) " +
                "redis.call('ZADD', KEYS[2], 0, ARGV[2]) " +
                "return tostring(v)"

        /**
         * 原子 CAS 写入：只有当前版本等于 ARGV[3] 时才写入。成功返回新版本号，版本不匹配时返回字符串 "0"。
         * 期望版本为 0 表示"仅当不存在时才创建"。
         */
        private const val PUT_IF_VERSION_SCRIPT =
            "local cur = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') " +
                "if cur ~= tonumber(ARGV[3]) then return '0' end " +
                "local v = cur + 1 " +
                "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) " +
                "redis.call('ZADD', KEYS[2], 0, ARGV[2]) " +
                "return tostring(v)"

        /** 原子删除：同时移除 item hash 与其索引成员。 */
        private const val DELETE_SCRIPT =
            "redis.call('DEL', KEYS[1]) redis.call('ZREM', KEYS[2], ARGV[1]) return 1"

        private fun namespacePath(namespace: List<String>): String = namespace.joinToString(NS_SEPARATOR)

        private fun validateKey(key: String) {
            require(key.isNotEmpty()) { "key must not be null or empty" }
            require(!key.contains('\u0000')) { "key must not contain the NUL character" }
        }

        private fun normalizePrefix(prefix: String): String {
            if (prefix.isBlank()) {
                return DEFAULT_KEY_PREFIX
            }
            return if (prefix.endsWith(":")) prefix else "$prefix:"
        }
    }
}
