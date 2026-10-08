/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.agui

/** [RunAgentInput.forwardedProps] 白名单键：仅允许客户端传递设备与追踪元数据。 */
object AgentForwardedPropsKeys {
    const val DEVICE_ID = "deviceId"
    const val TRACE_ID = "traceId"
}

/**
 * 清洗 AG-UI [RunAgentInput.forwardedProps]：去掉不可信身份键与已由服务端接管的 transport 键。
 *
 * deviceId/traceId 提取见 [com.tencent.bkrepo.agent.context.AgentChatContextResolver]。
 */
object AgentForwardedPropsSupport {

    private val BLOCKED_KEYS = setOf(
        "userId",
        "projectId",
        "ticket",
        "token",
        AgentForwardedPropsKeys.DEVICE_ID,
        AgentForwardedPropsKeys.TRACE_ID,
    )

    fun sanitizeForwardedProps(forwardedProps: Any?): Map<String, Any>? {
        val props = asStringMap(forwardedProps) ?: return null
        val sanitized = props.filterKeys { key -> key !in BLOCKED_KEYS }
        return sanitized.takeIf { it.isNotEmpty() }
    }

    /**
     * 把 AG-UI 传上来的 `forwardedProps`（声明类型是 `Any?`，实际由 JSON 反序列化而来）规范成
     * `Map<String, Any>`：非 Map、空 Map、键空白的条目一律丢掉，拿不到任何有效键时返回 `null`。
     *
     * 对外开放是因为 [com.tencent.bkrepo.agent.context.AgentChatContextResolver] 也要从同一份
     * `forwardedProps` 里读 deviceId/traceId，两边必须用同一套规范化规则，否则会出现"清洗时认得这个键、
     * 提取时认不得"的偏差。
     */
    @Suppress("UNCHECKED_CAST")
    fun asStringMap(value: Any?): Map<String, Any>? {
        if (value == null) return null
        if (value !is Map<*, *>) return null
        return value.entries
            .mapNotNull { (key, v) ->
                val name = key?.toString()?.trim().orEmpty()
                if (name.isBlank()) null else name to v as Any
            }
            .toMap()
            .takeIf { it.isNotEmpty() }
    }
}
