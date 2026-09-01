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
import java.time.Duration

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
 * ## 数据保留（`ttl` / `refreshTtlOnRead`）
 *
 * 框架的 `BaseStore` 接口没有过期概念，而这个 store 承载的后台任务记录与长期记忆都是"只写不删"的
 * （`WorkspaceTaskRepository` 的孤儿清扫只处理僵死任务，不清历史），不设上限就会单调增长。因此这里在
 * store 层统一给 key 挂 TTL：每次写入都把 item hash 与命名空间索引一起续期到"现在 + ttl"。
 *
 * `refreshTtlOnRead` 决定语义差别：
 * - 关闭（后台任务）：过期时刻只由写入推进，等于"最后一次更新之后再放多久"。
 * - 开启（长期记忆）：[get]/[search] 也会续期，等于"最后一次使用之后再放多久"，避免用户半年前保存、
 *   之后一直在读的偏好被静默清掉。
 *
 * 残留的不一致是有意接受的：命名空间索引整体续期，但索引成员没有单独的过期时间，所以一个仍在活跃写入的
 * 命名空间里会留下少量指向已过期 hash 的陈旧成员。[search] 本来就会跳过缺失的记录，成员本身只是几十字节
 * 的短字符串，而这个规模（每个有过后台任务的会话一条、每个用户的记忆文件各一条）远远算不上问题；反过来，
 * 若要精确清理就得把索引改成按过期时间打分的 ZSET，从而放弃 `ZRANGEBYLEX` 分页、偏离框架 `RedisStore`
 * 的行为，代价明显更大。整个命名空间停止读写一个保留期之后，索引与 hash 会一起消失，不留残余。
 *
 * 另外，把 TTL 从"有值"改回"不设过期"时，已经带着过期时间的 key 仍会过期一次（脚本只在 ttl 大于 0 时
 * 调 `PEXPIRE`，不会主动 `PERSIST`），之后重建的 key 才不再过期。
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
    ttl: Duration? = null,
    private val refreshTtlOnRead: Boolean = false,
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

    /** 0 表示不设过期，直接传给 Lua 由脚本判断，省掉两套脚本。 */
    private val ttlMillis: Long = ttl?.takeIf { !it.isZero && !it.isNegative }?.toMillis() ?: NO_TTL

    override fun get(namespace: MutableList<String>, key: String): StoreItem? {
        validateKey(key)
        val itemKey = itemKey(namespace, key)
        val hash = commands.hgetall(itemKey)
        if (hash.isNullOrEmpty()) {
            return null
        }
        touch(itemKey, indexKey(namespace))
        return toItem(key, hash)
    }

    override fun put(namespace: MutableList<String>, key: String, value: MutableMap<String, Any>) {
        validateKey(key)
        val itemKey = itemKey(namespace, key)
        val idxKey = indexKey(namespace)
        val json = serialize(value)
        commands.eval<String>(
            PUT_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf(itemKey, idxKey),
            json,
            key,
            ttlMillis.toString(),
        )
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
            ttlMillis.toString(),
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
            val itemKey = itemKey(namespace, k)
            val hash = commands.hgetall(itemKey)
            if (hash.isNullOrEmpty()) {
                continue // 索引里的陈旧条目——容忍
            }
            touch(itemKey)
            items.add(toItem(k, hash))
        }
        touch(idxKey)
        return items
    }

    override fun delete(namespace: MutableList<String>, key: String) {
        validateKey(key)
        val itemKey = itemKey(namespace, key)
        val idxKey = indexKey(namespace)
        commands.eval<Long>(DELETE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(itemKey, idxKey), key)
    }

    /**
     * 把过期时刻往后推一个完整保留期。只有同时配了 TTL 且开启 [refreshTtlOnRead] 才生效，
     * 用于长期记忆那种"最后一次使用之后再放多久"的语义。
     */
    private fun touch(vararg keys: String) {
        if (ttlMillis == NO_TTL || !refreshTtlOnRead) {
            return
        }
        keys.forEach { commands.pexpire(it, ttlMillis) }
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

        /** TTL 参数取这个值时不给 key 设置过期时间。 */
        private const val NO_TTL = 0L

        /**
         * TTL 片段：item hash 与命名空间索引一起续期。
         *
         * 之所以连索引一起续期，是为了让整个命名空间"最后一次写入之后再放一个保留期"就彻底消失，
         * 而不是留下一个只剩成员、指向的 hash 全都过期了的空索引。
         */
        private const val EXPIRE_FRAGMENT =
            "local ttl = tonumber(ARGV[%d]) " +
                "if ttl > 0 then redis.call('PEXPIRE', KEYS[1], ttl) redis.call('PEXPIRE', KEYS[2], ttl) end "

        /** 原子写入：无条件把版本 +1，写入 value + version，并写入索引条目。返回新版本号（字符串）。 */
        private val PUT_SCRIPT =
            "local v = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') + 1 " +
                "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) " +
                "redis.call('ZADD', KEYS[2], 0, ARGV[2]) " +
                EXPIRE_FRAGMENT.format(3) +
                "return tostring(v)"

        /**
         * 原子 CAS 写入：只有当前版本等于 ARGV[3] 时才写入。成功返回新版本号，版本不匹配时返回字符串 "0"。
         * 期望版本为 0 表示"仅当不存在时才创建"。
         *
         * 版本不匹配时直接返回、不续期：这条路径什么都没写，不应该延长记录寿命。
         */
        private val PUT_IF_VERSION_SCRIPT =
            "local cur = tonumber(redis.call('HGET', KEYS[1], 'version') or '0') " +
                "if cur ~= tonumber(ARGV[3]) then return '0' end " +
                "local v = cur + 1 " +
                "redis.call('HSET', KEYS[1], 'value', ARGV[1], 'version', tostring(v)) " +
                "redis.call('ZADD', KEYS[2], 0, ARGV[2]) " +
                EXPIRE_FRAGMENT.format(4) +
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
