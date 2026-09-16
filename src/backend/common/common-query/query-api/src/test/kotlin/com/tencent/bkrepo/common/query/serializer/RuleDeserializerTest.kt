package com.tencent.bkrepo.common.query.serializer

import com.sun.management.ThreadMXBean
import com.tencent.bkrepo.common.api.util.JsonUtils
import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.exception.QueryModelException
import com.tencent.bkrepo.common.query.model.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.management.ManagementFactory

@DisplayName("Rule 反序列化")
internal class RuleDeserializerTest {

    private val mapper = JsonUtils.objectMapper

    @Test
    fun `should parse nested query rules`() {
        val json = """
            {
              "relation": "AND",
              "rules": [
                {"field": "projectId", "value": "p1", "operation": "EQ"},
                {
                  "relation": "OR",
                  "rules": [
                    {"field": "repoName", "value": ["r1", "r2"], "operation": "IN"}
                  ]
                }
              ]
            }
        """.trimIndent()

        val rule = mapper.readValue(json, Rule::class.java) as Rule.NestedRule
        assertEquals(Rule.NestedRule.RelationType.AND, rule.relation)
        assertEquals(2, rule.rules.size)

        val first = rule.rules[0] as Rule.QueryRule
        assertEquals("projectId", first.field)
        assertEquals("p1", first.value)
        assertEquals(OperationType.EQ, first.operation)

        val nested = rule.rules[1] as Rule.NestedRule
        assertEquals(Rule.NestedRule.RelationType.OR, nested.relation)
        val leaf = nested.rules[0] as Rule.QueryRule
        assertEquals("repoName", leaf.field)
        assertEquals(OperationType.IN, leaf.operation)
        assertEquals(listOf("r1", "r2"), leaf.value)
    }

    @Test
    fun `should reject non-array rules`() {
        assertThrows<QueryModelException> {
            mapper.readValue("""{"relation":"AND","rules":{"field":"a"}}""", Rule::class.java)
        }
        assertThrows<QueryModelException> {
            mapper.readValue("""{"relation":"AND"}""", Rule::class.java)
        }
        assertThrows<QueryModelException> {
            mapper.readValue("""{"relation":"AND","rules":null}""", Rule::class.java)
        }
    }

    @Test
    fun `should parse deep nesting without amplifying allocation`() {
        val depth = 300
        val json = nestedPayload(depth, leafWidth = 2000)
        mapper.readValue(json, Rule::class.java)

        val mxBean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        require(mxBean.isThreadAllocatedMemorySupported)
        if (!mxBean.isThreadAllocatedMemoryEnabled) {
            mxBean.isThreadAllocatedMemoryEnabled = true
        }
        val before = mxBean.currentThreadAllocatedBytes
        val rule = mapper.readValue(json, Rule::class.java)
        val allocated = mxBean.currentThreadAllocatedBytes - before

        var cursor: Rule = rule
        var actualDepth = 0
        while (cursor is Rule.NestedRule) {
            actualDepth++
            cursor = cursor.rules.first()
        }
        assertEquals(depth, actualDepth)
        assertEquals("name", (cursor as Rule.QueryRule).field)

        // 修复前每层 toString 使分配约随 depth 线性放大；50x 给 JsonNode 开销留余量
        assertTrue(
            allocated < json.length * 50L,
            "allocated=$allocated payload=${json.length} ratio=${allocated / json.length.toDouble()}"
        )
    }

    private fun nestedPayload(depth: Int, leafWidth: Int): String {
        val leaf = buildString {
            repeat(leafWidth) { index ->
                if (index > 0) append(',')
                append("""{"field":"name","value":"v$index","operation":"EQ"}""")
            }
        }
        return """{"relation":"AND","rules":[""".repeat(depth) + leaf + "]}".repeat(depth)
    }
}
