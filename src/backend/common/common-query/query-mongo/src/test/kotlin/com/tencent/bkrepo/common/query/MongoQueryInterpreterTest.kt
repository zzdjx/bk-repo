/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2020 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.tencent.bkrepo.common.query

import com.tencent.bkrepo.common.api.exception.ParameterInvalidException
import com.tencent.bkrepo.common.query.builder.MongoQueryInterpreter
import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.model.PageLimit
import com.tencent.bkrepo.common.query.model.QueryModel
import com.tencent.bkrepo.common.query.model.Rule
import com.tencent.bkrepo.common.query.model.Sort
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Mongo 查询解释器测试")
class MongoQueryInterpreterTest {

    @Test
    fun buildTest() {
        val projectId = Rule.QueryRule("projectId", "1")
        val repoName = Rule.QueryRule("repoName", "repoName")
        val path = Rule.QueryRule("path", "/a/b/c")
        val rule1 = Rule.NestedRule(mutableListOf(path, projectId), Rule.NestedRule.RelationType.AND)

        val rule2 = Rule.NestedRule(mutableListOf(repoName, rule1), Rule.NestedRule.RelationType.AND)

        val queryModel = QueryModel(
            page = PageLimit(0, 10),
            sort = Sort(listOf("name"), Sort.Direction.ASC),
            select = mutableListOf("projectId", "repoName", "fullPath", "metadata"),
            rule = rule2
        )

        val builder = MongoQueryInterpreter()
        val query = builder.interpret(queryModel).mongoQuery
        println(query.queryObject)
    }

    @Test
    fun `reject mongo operator in rule field`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(rule = Rule.QueryRule("\$where", "return true"))
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator nested under metadata prefix`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(rule = Rule.QueryRule("metadata.\$where", "x"))
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator in nested rule field`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(
            rule = Rule.NestedRule(
                mutableListOf(
                    Rule.QueryRule("projectId", "p1"),
                    Rule.QueryRule("\$expr", mapOf("\$eq" to listOf(1, 1)))
                )
            )
        )
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator in select`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(select = listOf("projectId", "\$slice"))
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator in sort`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(sort = Sort(listOf("\$natural"), Sort.Direction.ASC))
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator in eq value`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(rule = Rule.QueryRule("name", mapOf("\$gt" to "")))
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator nested in eq value`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(rule = Rule.QueryRule("metadata", mapOf("label" to mapOf("\$ne" to ""))))
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator in ne value`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(rule = Rule.QueryRule("name", mapOf("\$exists" to true), OperationType.NE))
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `reject mongo operator in in list element`() {
        val interpreter = MongoQueryInterpreter()
        val queryModel = queryModel(
            rule = Rule.QueryRule("name", listOf("ok", mapOf("\$regex" to ".*")), OperationType.IN)
        )
        assertThrows<ParameterInvalidException> { interpreter.interpret(queryModel) }
    }

    @Test
    fun `accept ordinary map and list values`() {
        val interpreter = MongoQueryInterpreter()
        interpreter.interpret(queryModel(rule = Rule.QueryRule("metadata", mapOf("env" to "prod"))))
        interpreter.interpret(queryModel(rule = Rule.QueryRule("repoName", listOf("r1", "r2"), OperationType.IN)))
    }

    private fun queryModel(
        rule: Rule = Rule.QueryRule("projectId", "p1"),
        select: List<String>? = null,
        sort: Sort? = null
    ): QueryModel {
        return QueryModel(page = PageLimit(1, 10), sort = sort, select = select, rule = rule)
    }
}
