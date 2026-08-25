/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.pojo

/** 一次工具调用尝试在权限层面的裁决结果。 */
enum class AgentToolCallDecision {
    /** 无需询问，直接放行（ALLOW 规则或已通过的 ASKING）。 */
    ALLOWED,

    /** 命中 ASK 规则，正在等待用户在确认卡片上作出选择。 */
    ASKING,

    /** 命中 DENY 规则，未询问用户即被拒绝。 */
    RULE_DENIED,

    /** 用户在确认卡片上点了拒绝。 */
    ASK_DENIED,
}
