package com.tencent.bkrepo.common.redis

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript

@DisplayName("Redis getAndDelete 兼容旧 Redis")
class RedisOperationTest {

    @Test
    @DisplayName("使用 Lua GET+DEL 而不是 GETDEL")
    fun `getAndDelete uses lua get plus del instead of getdel`() {
        val redisTemplate = mockk<RedisTemplate<String, String>>()
        val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
        every { redisTemplate.opsForValue() } returns valueOps
        every {
            redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>())
        } answers {
            val script = firstArg<RedisScript<String>>().scriptAsString.lowercase()
            assertTrue(script.contains("redis.call('get'"))
            assertTrue(script.contains("redis.call('del'"))
            assertFalse(script.contains("getdel"))
            assertEquals(listOf(KEY), secondArg<List<String>>())
            VALUE
        }

        val result = RedisOperation(redisTemplate).getAndDelete(KEY)

        assertEquals(VALUE, result)
        verify(exactly = 0) { valueOps.getAndDelete(any()) }
    }

    @Test
    @DisplayName("key 不存在时返回 null")
    fun `getAndDelete returns null when lua returns null`() {
        val redisTemplate = mockk<RedisTemplate<String, String>>()
        every {
            redisTemplate.execute(any<RedisScript<String>>(), any<List<String>>())
        } answers { null }

        assertNull(RedisOperation(redisTemplate).getAndDelete("missing"))
    }

    companion object {
        private const val KEY = "client:code:userId"
        private const val VALUE = "user-1"
    }
}
