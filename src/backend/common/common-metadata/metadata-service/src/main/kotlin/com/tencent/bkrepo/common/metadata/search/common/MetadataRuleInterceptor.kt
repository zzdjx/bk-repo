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

package com.tencent.bkrepo.common.metadata.search.common

import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.interceptor.QueryContext
import com.tencent.bkrepo.common.query.interceptor.QueryRuleInterceptor
import com.tencent.bkrepo.common.query.model.Rule
import com.tencent.bkrepo.repository.constant.METADATA_PREFIX
import com.tencent.bkrepo.common.metadata.model.TMetadata
import com.tencent.bkrepo.common.metadata.model.TNode
import org.springframework.data.mongodb.core.query.Criteria
import java.util.Locale

/**
 * 元数据规则拦截器
 *
 * 条件构造器中传入元数据的条件是`metadata.key=value`，需要适配成mongodb的查询条件
 */
class MetadataRuleInterceptor : QueryRuleInterceptor {

    override fun match(rule: Rule): Boolean {
        return rule is Rule.QueryRule && rule.field.startsWith(METADATA_PREFIX)
    }

    override fun intercept(rule: Rule, context: QueryContext): Criteria {
        require(rule is Rule.QueryRule)
        val key = rule.field.removePrefix(METADATA_PREFIX)
        val valueRule = if (rule.operation == OperationType.EXISTS) {
            null
        } else {
            Rule.QueryRule(TMetadata::value.name, rule.value, rule.operation).toFixed()
        }
        val criteria = resolveKeyCriteria(key, valueRule, context)
        // 在流水线含有pipelineId或者buildId时，使用METADATA_IDX查询效率更高
        if (isInPipelineSearch(key)) {
            context.mongoQuery.withHint(TNode.METADATA_IDX)
        }
        return Criteria.where(TNode::metadata.name).elemMatch(criteria)
    }

    /**
     * 匹配 metadata 数组元素的 key；[valueRule] 为 null 时只判断 key 存在。
     * 历史数据以 X-BKREPO-META-{key} 写入时未忽略大小写，需同时查原值与小写 key。
     */
    private fun resolveKeyCriteria(key: String, valueRule: Rule?, context: QueryContext): Criteria {
        val primary = combineKeyRule(key, valueRule)
        if (!ALPHA_REGEX.containsMatchIn(key)) {
            return context.interpreter.resolveRule(primary, context)
        }
        val lower = combineKeyRule(key.lowercase(Locale.getDefault()), valueRule)
        return context.interpreter.resolveRule(
            Rule.NestedRule(mutableListOf(primary, lower), Rule.NestedRule.RelationType.OR),
            context,
        )
    }

    private fun combineKeyRule(key: String, valueRule: Rule?): Rule {
        val keyRule = Rule.QueryRule(TMetadata::key.name, key, OperationType.EQ).toFixed()
        return valueRule?.let { Rule.NestedRule(mutableListOf(keyRule, it)) } ?: keyRule
    }

    /**
     * 是否是流水线相关查询
     * */
    private fun isInPipelineSearch(key: String): Boolean {
        return key == PIPELINE_ID || key == BUILD_ID
    }

    companion object {
        const val ALPHA_PATTERN = "[A-Z]"
        const val PIPELINE_ID = "pipelineId"
        const val BUILD_ID = "buildId"
        private val ALPHA_REGEX = Regex(ALPHA_PATTERN)
    }
}
