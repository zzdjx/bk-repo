/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.agui.AguiMessageArchiveHandler
import com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties
import com.tencent.bkrepo.agent.constant.AGENT_RUN_ID_PREFIX
import com.tencent.bkrepo.agent.context.AgentChatContext
import com.tencent.bkrepo.agent.context.AgentChatContextResolver
import com.tencent.bkrepo.agent.hitl.AguiInterruptTracker
import com.tencent.bkrepo.agent.pojo.AgentRunTriggerType
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.runtime.ActiveRunKey
import com.tencent.bkrepo.agent.runtime.AgentRunReplaySinkRegistry
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import com.tencent.bkrepo.agent.service.AgentSessionService
import com.tencent.bkrepo.agent.session.AguiRuntimeContextCarrier
import com.tencent.bkrepo.common.api.constant.StringPool
import io.agentscope.core.agui.processor.AguiRequestProcessor
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * 启动一次 run：登记运行态、归档入站消息、拉起 AG-UI 事件流，产出 [AgentRunScope]。
 *
 * 调用这里时准入判断（停机、灰度、IAM、会话归属、幂等、并发锁）已经全部通过，[AgentRunInputPreparer]
 * 也已经把输入清洗完毕——这个类只负责"开始跑"，不再做任何拒绝。
 *
 * ## 登记顺序
 *
 * [registerRunState] 里那五步不是随手排的：`startRun` 必须最先，它是这次 run 的事实来源，后面几步
 * 挂的都是指向它的运行态；`replaySinkRegistry.open` 必须最后，因为它一旦打开，重连的客户端就会开始
 * 从 sink 读事件，此时 run 记录必须已经可查。中间三步（会话时间戳、会话归属、活跃 run 绑定）彼此
 * 无序。
 *
 * ## 为什么归档要在 `process()` 之前
 *
 * [AguiMessageArchiveHandler.archiveIncomingUserMessages] 先落用户消息，再调
 * [AguiRequestProcessor.process]。反过来的话，Agent 可能在用户消息入库之前就产出了回复，聊天记录
 * 按时间排序会出现"先有答后有问"。
 */
@Component
class AgentRunStarter(
    private val properties: EffectiveAgentRuntimeProperties,
    private val agentChatContextResolver: AgentChatContextResolver,
    private val agentRunRecordService: AgentRunRecordService,
    private val agentSessionService: AgentSessionService,
    private val activeRunManager: ActiveRunManager,
    private val replaySinkRegistry: AgentRunReplaySinkRegistry,
    private val messageArchiveHandler: AguiMessageArchiveHandler,
    private val aguiRequestProcessor: AguiRequestProcessor,
) {

    fun start(userId: String, projectId: String, prepared: PreparedRunInput, runKey: ActiveRunKey): AgentRunScope {
        val executionId = AGENT_RUN_ID_PREFIX + StringPool.uniqueId()
        val chatContext = agentChatContextResolver.resolve(userId, projectId, prepared.input, executionId)
        registerRunState(chatContext, prepared, runKey)
        return buildRunScope(chatContext, prepared)
    }

    private fun registerRunState(
        chatContext: AgentChatContext,
        prepared: PreparedRunInput,
        runKey: ActiveRunKey,
    ) {
        agentRunRecordService.startRun(
            runId = prepared.runId,
            executionId = chatContext.executionId,
            threadId = prepared.threadId,
            userId = chatContext.userId,
            projectId = chatContext.projectId,
            deviceId = chatContext.deviceId,
            entryAgentId = properties.name,
            triggerType = if (prepared.input.hasResume()) {
                AgentRunTriggerType.AGUI_RESUME
            } else {
                AgentRunTriggerType.USER_INPUT
            },
        )
        agentSessionService.touchSession(prepared.threadId, prepared.runId, chatContext.userId, chatContext.projectId)
        activeRunManager.bindActiveRun(runKey, prepared.runId)
        replaySinkRegistry.open(prepared.runId)
    }

    private fun buildRunScope(chatContext: AgentChatContext, prepared: PreparedRunInput): AgentRunScope {
        messageArchiveHandler.archiveIncomingUserMessages(
            prepared.input,
            prepared.threadId,
            prepared.runId,
            chatContext.userId,
            chatContext.projectId,
        )
        val runtimeContext = chatContext.toRuntimeContext()
        // 2.0.4（#3279）：RuntimeContext 不再作为 process() 的入参，改为搭在请求上由 resolver 取回
        val processRequest = AguiRuntimeContextCarrier.wrap(prepared.input, runtimeContext)
        return AgentRunScope(
            userId = chatContext.userId,
            projectId = chatContext.projectId,
            input = prepared.input,
            threadId = prepared.threadId,
            runId = prepared.runId,
            executionId = chatContext.executionId,
            runtimeContext = runtimeContext,
            eventFlux = aguiRequestProcessor.process(processRequest).events(),
            archiveState = AguiMessageArchiveHandler.State(),
            interruptState = AguiInterruptTracker.State(),
            emitter = SseEmitter(properties.sseTimeout.toMillis()),
        )
    }
}
