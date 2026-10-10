package com.tencent.bkrepo.common.query.matcher.impl

import com.tencent.bkrepo.common.query.enums.OperationType
import com.tencent.bkrepo.common.query.matcher.RuleMatcher
import com.tencent.bkrepo.common.query.model.Rule

class ExistsMatcher : RuleMatcher() {
    override fun supportOperationType() = OperationType.EXISTS

    override fun isSupportValueType(rule: Rule.QueryRule, valueToMatch: Any?): Boolean {
        return true
    }

    override fun doMatch(rule: Rule.QueryRule, valueToMatch: Any?): Boolean {
        return valueToMatch != null
    }
}
