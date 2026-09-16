package com.tencent.bkrepo.common.metadata.search.common

import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.metadata.model.TNode
import com.tencent.bkrepo.common.query.builder.MongoQueryInterpreter
import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.common.query.model.Rule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.mongodb.core.query.Query

@DisplayName("通用查询上下文测试")
class CommonQueryContextTest {

    @Test
    fun `should find projectId when operation is EQ`() {
        val context = buildContext(Rule.QueryRule(TNode::projectId.name, PROJECT_ID, OperationType.EQ))
        assertEquals(PROJECT_ID, context.findProjectId())
    }

    /**
     * 非EQ操作的查询语义与鉴权使用的projectId不一致，必须拒绝：
     * NE、NOT_NULL查询除该项目外的所有项目，PREFIX、REGEX等可匹配到其它项目；
     * IN虽然限定在指定项目内，但value为列表无法用于鉴权，只支持单项目查询
     */
    @Test
    fun `should reject projectId rule with non EQ operation`() {
        listOf(
            OperationType.NE,
            OperationType.NOT_NULL,
            OperationType.NULL,
            OperationType.IN,
            OperationType.NIN,
            OperationType.PREFIX,
            OperationType.SUFFIX,
            OperationType.CONTAINS,
            OperationType.REGEX,
            OperationType.REGEX_I,
            OperationType.MATCH
        ).forEach { operation ->
            val context = buildContext(Rule.QueryRule(TNode::projectId.name, PROJECT_ID, operation))
            assertThrows<ErrorCodeException>("operation [$operation] should be rejected") {
                context.findProjectId()
            }
        }
    }

    /**
     * 同时存在EQ与NE时，鉴权取EQ的值，查询条件相交为空集，不产生越权
     */
    @Test
    fun `should find EQ rule when mixed with non EQ rule`() {
        val context = buildContext(
            Rule.QueryRule(TNode::projectId.name, PROJECT_ID, OperationType.NE),
            Rule.QueryRule(TNode::projectId.name, PROJECT_ID, OperationType.EQ)
        )
        assertEquals(PROJECT_ID, context.findProjectId())
    }

    private fun buildContext(vararg rules: Rule): CommonQueryContext {
        val queryModel = QueryModel(
            page = PageLimit(),
            sort = null,
            select = null,
            rule = Rule.NestedRule(rules.toMutableList(), Rule.NestedRule.RelationType.AND)
        )
        return CommonQueryContext(queryModel, false, Query(), MongoQueryInterpreter())
    }

    companion object {
        private const val PROJECT_ID = "ut-project"
    }
}
