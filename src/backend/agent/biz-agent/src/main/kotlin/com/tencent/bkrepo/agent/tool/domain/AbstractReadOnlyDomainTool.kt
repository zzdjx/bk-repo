/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.tool.domain

import com.tencent.bkrepo.common.security.exception.PermissionException
import io.agentscope.core.message.TextBlock
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.permission.PermissionContextState
import io.agentscope.core.permission.PermissionDecision
import io.agentscope.core.tool.ToolBase
import io.agentscope.core.tool.ToolCallParam
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * 服务端只读领域工具基类：统一 PermissionEngine 放行，IAM 在 [execute] 内执行。
 */
abstract class AbstractReadOnlyDomainTool(
    name: String,
    description: String,
    inputSchema: Map<String, Any>,
) : ToolBase(
    ToolBase.builder()
        .name(name)
        .description(description)
        .inputSchema(inputSchema)
        .readOnly(true)
        .concurrencySafe(true),
) {

    override fun checkPermissions(
        toolInput: MutableMap<String, Any>,
        context: PermissionContextState,
    ): Mono<PermissionDecision> = Mono.just(
        PermissionDecision.allow("Read-only domain tool '$name'"),
    )

    override fun callAsync(param: ToolCallParam): Mono<ToolResultBlock> = Mono.fromCallable {
        try {
            val payload = execute(param)
            ToolResultBlock.of(
                param.toolUseBlock.id,
                name,
                TextBlock.builder().text(payload).build(),
            )
        } catch (ex: PermissionException) {
            ToolResultBlock.error("permission_denied: ${ex.message}")
        } catch (ex: IllegalArgumentException) {
            ToolResultBlock.error("invalid_argument: ${ex.message}")
        } catch (ex: Exception) {
            // 依赖下游服务的工具会抛出远程调用异常，必须转成工具结果而不是让 Mono 以错误信号终止，
            // 否则整轮对话会直接失败，模型没有机会向用户解释查询未完成
            logger.error("domain tool [$name] failed", ex)
            ToolResultBlock.error("upstream_error: ${ex::class.simpleName}: ${ex.message}")
        }
    }

    protected abstract fun execute(param: ToolCallParam): String

    companion object {
        private val logger = LoggerFactory.getLogger(AbstractReadOnlyDomainTool::class.java)
    }
}
