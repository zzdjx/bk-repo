/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.constant.RUNTIME_CONTEXT_PERMISSION_CONFIRM_RESULTS
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.event.AgentEvent
import io.agentscope.core.event.ConfirmResult
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.middleware.AgentInput
import io.agentscope.core.middleware.MiddlewareBase
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.function.Function

/** 在 resume 时将 approval payload 转为 AgentScope Permission HITL 所需的 ConfirmResult 消息。 */
@Component
class PermissionConfirmResumeMiddleware : MiddlewareBase {

    override fun onAgent(
        agent: Agent,
        ctx: RuntimeContext,
        input: AgentInput,
        next: Function<AgentInput, Flux<AgentEvent>>,
    ): Flux<AgentEvent> {
        @Suppress("UNCHECKED_CAST")
        val confirmResults = ctx.get(RUNTIME_CONTEXT_PERMISSION_CONFIRM_RESULTS) as? List<ConfirmResult>
        if (confirmResults.isNullOrEmpty()) {
            return next.apply(input)
        }

        val confirmMsg = Msg.builder()
            .role(MsgRole.USER)
            .textContent("")
            .metadata(mapOf(Msg.METADATA_CONFIRM_RESULTS to confirmResults))
            .build()
        val merged = ArrayList<Msg>(confirmResults.size + input.msgs().size)
        merged.add(confirmMsg)
        merged.addAll(input.msgs())
        return next.apply(AgentInput(merged))
    }
}
