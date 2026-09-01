/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Agent LLM 网关配置（`agent.llm`）。
 *
 * 业务代码应注入 [EffectiveAgentLlmProperties]。
 */
@ConfigurationProperties("agent.llm")
data class AgentLlmProperties(
    var baseUrl: String = "",
    var apiKey: String = "",
    var bkAppCode: String = "",
    var bkAppSecret: String = "",
    var modelName: String = "",
    var reasoningEffort: String? = null,
    var stream: Boolean = DEFAULT_STREAM,
    /**
     * 备用模型名。非空时启用框架的单级 fallback：主模型耗尽重试预算后整条流切到备用模型。
     * 复用同一个网关地址与鉴权，只换模型名——跨网关容灾需要另一套凭据，不在这里解决。
     */
    var fallbackModelName: String = "",
    /**
     * 单次模型调用（**每次尝试**，不是整个 run）的超时。
     *
     * 框架默认 5 分钟（[io.agentscope.core.model.ExecutionConfig.MODEL_DEFAULTS]），配合默认 3 次尝试，
     * 最坏一次模型调用能占掉 15 分钟——超过会话锁 TTL（`agent.runtime.active-run-ttl`，默认 11 分钟），
     * 锁会在 run 还活着的时候过期，同一会话上可能并发起第二个 run。所以这里显式压到分钟级以内。
     */
    var requestTimeout: Duration = DEFAULT_REQUEST_TIMEOUT,
    /** 含首次在内的尝试次数，1 表示不重试。只对超时/429/5xx/IO 生效，4xx 与鉴权失败不重试。 */
    var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    var initialBackoff: Duration = DEFAULT_INITIAL_BACKOFF,
    var maxBackoff: Duration = DEFAULT_MAX_BACKOFF,
) {
    override fun toString(): String =
        "AgentLlmProperties(baseUrl=$baseUrl, modelName=$modelName, stream=$stream, " +
            "apiKey=${AgentPropertiesRedaction.redactSecret(apiKey)}, " +
            "bkAppCode=${AgentPropertiesRedaction.redactSecret(bkAppCode)}, " +
            "bkAppSecret=${AgentPropertiesRedaction.redactSecret(bkAppSecret)}, " +
            "reasoningEffort=${reasoningEffort ?: "<unset>"}, " +
            "fallbackModelName=${fallbackModelName.ifBlank { "<unset>" }}, " +
            "requestTimeout=$requestTimeout, maxAttempts=$maxAttempts)"

    companion object {
        const val DEFAULT_STREAM = true
        const val DEFAULT_MAX_ATTEMPTS = 2
        val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(90)
        val DEFAULT_INITIAL_BACKOFF: Duration = Duration.ofSeconds(2)
        val DEFAULT_MAX_BACKOFF: Duration = Duration.ofSeconds(10)
    }
}

/**
 * 启动时解析后的 LLM 配置，供 Model Bean 与 AG-UI 层使用。
 */
data class EffectiveAgentLlmProperties(
    val baseUrl: String,
    val apiKey: String,
    val bkAppCode: String,
    val bkAppSecret: String,
    val modelName: String,
    val reasoningEffort: String?,
    val stream: Boolean,
    val authMode: AgentLlmAuthMode,
    val fallbackModelName: String,
    val requestTimeout: Duration,
    val maxAttempts: Int,
    val initialBackoff: Duration,
    val maxBackoff: Duration,
) {
    fun effectiveReasoningEffort(): String? = reasoningEffort?.takeIf { it.isNotBlank() }

    fun fallbackEnabled(): Boolean = fallbackModelName.isNotBlank()

    /**
     * 一次模型调用在最坏情况下能占用的墙钟时间：每次尝试各自超时，加上重试之间的退避。
     *
     * 用途是把这个预算与会话锁 TTL、停机窗口对齐（见
     * [com.tencent.bkrepo.agent.config.AgentModelBudgetValidator]）。退避按框架的 jitter 上界
     * （`Retry.backoff(...).jitter(0.5)`，即最多 1.5 倍）估算；配了备用模型时翻倍——框架把同一份
     * ExecutionConfig 传给备用模型，它有自己完整的重试预算。
     */
    fun worstCaseModelCallBudget(): Duration {
        var budget = requestTimeout.multipliedBy(maxAttempts.toLong())
        var backoff = initialBackoff
        repeat(maxAttempts - 1) {
            budget = budget.plus(minOf(backoff, maxBackoff).multipliedBy(3).dividedBy(2))
            backoff = backoff.multipliedBy(2)
        }
        return if (fallbackEnabled()) budget.multipliedBy(2) else budget
    }

    override fun toString(): String =
        "EffectiveAgentLlmProperties(baseUrl=$baseUrl, modelName=$modelName, authMode=$authMode, " +
            "stream=$stream, apiKey=${AgentPropertiesRedaction.redactSecret(apiKey)}, " +
            "bkAppCode=${AgentPropertiesRedaction.redactSecret(bkAppCode)}, " +
            "bkAppSecret=${AgentPropertiesRedaction.redactSecret(bkAppSecret)}, " +
            "reasoningEffort=${reasoningEffort ?: "<unset>"}, " +
            "fallbackModelName=${fallbackModelName.ifBlank { "<unset>" }}, " +
            "requestTimeout=$requestTimeout, maxAttempts=$maxAttempts, " +
            "worstCaseModelCallBudget=${worstCaseModelCallBudget()})"

    companion object {
        fun defaults(): EffectiveAgentLlmProperties = AgentLlmPropertiesResolver.resolve(AgentLlmProperties())
    }
}

object AgentLlmPropertiesResolver {

    fun resolve(llm: AgentLlmProperties): EffectiveAgentLlmProperties = EffectiveAgentLlmProperties(
        baseUrl = llm.baseUrl,
        apiKey = llm.apiKey,
        bkAppCode = llm.bkAppCode,
        bkAppSecret = llm.bkAppSecret,
        modelName = llm.modelName,
        reasoningEffort = llm.reasoningEffort,
        stream = llm.stream,
        authMode = resolveAuthMode(llm.bkAppCode, llm.apiKey),
        fallbackModelName = llm.fallbackModelName,
        requestTimeout = llm.requestTimeout,
        maxAttempts = llm.maxAttempts.coerceAtLeast(1),
        initialBackoff = llm.initialBackoff,
        maxBackoff = llm.maxBackoff,
    )

    private fun resolveAuthMode(bkAppCode: String, apiKey: String): AgentLlmAuthMode = when {
        bkAppCode.isNotBlank() -> AgentLlmAuthMode.BK_GATEWAY
        apiKey.isNotBlank() -> AgentLlmAuthMode.API_KEY
        else -> AgentLlmAuthMode.BK_GATEWAY
    }
}
