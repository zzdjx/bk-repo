/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.config

import com.tencent.bkrepo.agent.config.properties.AgentLlmAuthMode
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentLlmProperties
import io.agentscope.core.model.ExecutionConfig
import io.agentscope.core.model.GenerateOptions
import io.agentscope.core.model.Model
import io.agentscope.extensions.model.openai.OpenAIChatModel
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class AgentModelConfig {

    @Bean
    fun agentChatModel(properties: EffectiveAgentLlmProperties): Model =
        buildModel(properties, properties.modelName)

    /**
     * 模型调用的韧性配置，与主模型 bean 分开是因为它要整体交给 HarnessAgent.Builder：
     * 超时/重试走 `modelExecutionConfig`，备用模型走 `fallbackModel`，两者都是 Agent 级而非 Model 级配置。
     *
     * 备用模型是一个独立的 [Model] 实例，但**不注册成 bean**——容器里出现第二个 [Model] 会让现有的
     * `model: Model` 注入点变歧义，而它除了喂给 Builder 之外没有别的消费方。
     */
    @Bean
    fun agentModelResilience(properties: EffectiveAgentLlmProperties): AgentModelResilience {
        val executionConfig = ExecutionConfig.builder()
            .timeout(properties.requestTimeout)
            .maxAttempts(properties.maxAttempts)
            .initialBackoff(properties.initialBackoff)
            .maxBackoff(properties.maxBackoff)
            // 沿用框架的可重试判定：超时、IO、429、5xx 重试，4xx 与鉴权失败立即失败。
            .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
            .build()
        val fallbackModel = properties.fallbackModelName
            .takeIf { it.isNotBlank() }
            ?.let { buildModel(properties, it) }
        logger.info(
            "agent model resilience: requestTimeout={}, maxAttempts={}, fallbackModel={}, worstCaseBudget={}",
            properties.requestTimeout,
            properties.maxAttempts,
            properties.fallbackModelName.ifBlank { "<unset>" },
            properties.worstCaseModelCallBudget(),
        )
        return AgentModelResilience(executionConfig, fallbackModel)
    }

    private fun buildModel(properties: EffectiveAgentLlmProperties, modelName: String): Model {
        require(properties.baseUrl.isNotBlank()) { "agent.llm.base-url is required" }
        require(properties.modelName.isNotBlank()) { "agent.llm.model-name is required" }

        val reasoningEffort = properties.effectiveReasoningEffort()
        logger.info(
            "Initializing agent chat model: baseUrl={}, modelName={}, authMode={}, reasoningEffort={}",
            properties.baseUrl,
            modelName,
            properties.authMode,
            reasoningEffort ?: "<unset>",
        )

        val builder = OpenAIChatModel.builder()
            .baseUrl(properties.baseUrl)
            .modelName(modelName)
            .stream(properties.stream)

        when (properties.authMode) {
            AgentLlmAuthMode.BK_GATEWAY -> {
                require(properties.bkAppCode.isNotBlank()) {
                    "agent.llm.bk-app-code is required when using bk gateway auth"
                }
                require(properties.bkAppSecret.isNotBlank()) {
                    "agent.llm.bk-app-secret is required when using bk gateway auth"
                }
                val authJson =
                    """{"bk_app_code":"${properties.bkAppCode}","bk_app_secret":"${properties.bkAppSecret}"}"""
                applyGenerateOptions(builder, gatewayAuthJson = authJson, reasoningEffort = reasoningEffort)
                builder.endpointPath("")
            }
            AgentLlmAuthMode.API_KEY -> {
                require(properties.apiKey.isNotBlank()) {
                    "agent.llm.api-key is required when bk-app-code is empty"
                }
                builder.apiKey(properties.apiKey)
                applyGenerateOptions(builder, reasoningEffort = reasoningEffort)
            }
        }
        return builder.build()
    }

    private fun applyGenerateOptions(
        builder: OpenAIChatModel.Builder,
        gatewayAuthJson: String? = null,
        reasoningEffort: String? = null,
    ) {
        if (gatewayAuthJson == null && reasoningEffort == null) return
        val optionsBuilder = GenerateOptions.builder()
        gatewayAuthJson?.let { optionsBuilder.additionalHeader("X-Bkapi-Authorization", it) }
        reasoningEffort?.let { optionsBuilder.reasoningEffort(it) }
        builder.generateOptions(optionsBuilder.build())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(AgentModelConfig::class.java)
    }
}

/**
 * 交给 `HarnessAgent.Builder` 的模型韧性配置。[fallbackModel] 为 null 表示未配备用模型。
 */
data class AgentModelResilience(
    val executionConfig: ExecutionConfig,
    val fallbackModel: Model?,
)
