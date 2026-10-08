/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.session

import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver

/**
 * [RuntimeContext] 搭在 AG-UI 请求上的约定，[wrap] 与 [resolve] 是同一件事的两半。
 *
 * AgentScope 2.0.4（#3279）起 `AguiRequestProcessor.process()` 不再接受现成的 [RuntimeContext]，
 * 改为在 processor 上注册一个 [AguiRuntimeContextResolver]，由它从请求里解析出来。这个形状是为标准
 * MVC / WebFlux 端点设计的——上下文（租户、鉴权主体）本来就在 HTTP 请求里。
 *
 * 我们不是那种端点。运行上下文是
 * [com.tencent.bkrepo.agent.service.run.AgentRunOrchestrator] 在鉴权、抢会话锁、回灌权限确认结果之后
 * 自己组装的（userId / projectId / executionId / deviceId / 确认结果），resolver 拿着原始 HTTP 请求
 * 重建不出来。因此按 `Transport.CUSTOM` 把组装好的 context 放在 `nativeRequest` 泛型位上原样取回：
 * 那个位置本就是留给自定义传输的载荷。
 *
 * 之所以收在一个 object 里而不是两边各写一遍 lambda，是因为"放进去"和"取出来"必须对称，分开写容易
 * 只改一边——生产装配（[com.tencent.bkrepo.agent.config.AguiAgentConfiguration]）和 HITL 端到端用例
 * 都走这里。
 */
object AguiRuntimeContextCarrier : AguiRuntimeContextResolver {

    /** 把已组装好的 [runtimeContext] 连同 [input] 包成 processor 能收的请求。不设 header / path agentId，
     * agentId 走 [io.agentscope.core.agui.adapter.AguiAdapterConfig] 的默认值。 */
    fun wrap(input: RunAgentInput, runtimeContext: RuntimeContext): AguiRuntimeContextRequest<RuntimeContext> =
        AguiRuntimeContextRequest.builder<RuntimeContext>()
            .input(input)
            .transport(AguiRuntimeContextRequest.Transport.CUSTOM)
            .nativeRequest(runtimeContext)
            .build()

    override fun resolve(request: AguiRuntimeContextRequest<*>): RuntimeContext =
        request.nativeRequest as? RuntimeContext
            ?: throw IllegalStateException(
                "AG-UI 请求未携带 RuntimeContext，应经 AguiRuntimeContextCarrier.wrap 构造；" +
                    "实际 nativeRequest=${request.nativeRequest?.javaClass?.name}"
            )
}
