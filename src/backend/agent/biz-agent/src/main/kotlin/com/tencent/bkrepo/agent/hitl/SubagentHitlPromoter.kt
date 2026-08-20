/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import com.tencent.bkrepo.agent.permission.AgentPermissionRulesConfiguration
import com.tencent.bkrepo.agent.tool.frontend.FrontendToolCatalog
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.event.AgentResultEvent
import io.agentscope.core.event.RequireUserConfirmEvent
import io.agentscope.core.message.GenerateReason
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 将子 Agent 的 `subagent.require_confirm` 等 Custom 事件上冒为标准 AG-UI `RUN_FINISHED(interrupt)`。
 *
 * AgentScope 2.0.1 默认（[io.agentscope.core.agui.adapter.strategy.SubagentEventConverter]，即
 * [io.agentscope.core.agui.adapter.AguiAdapterConfig.Builder.emitSubagentEventsAsNative] 保持默认 false）
 * 会把子 Agent 自身的生命周期、工具调用、[RequireUserConfirmEvent] 等事件整体降级为
 * `AguiEvent.Custom(subagent.*)`，不会产出与顶层同构的原生 RUN_STARTED/RUN_FINISHED/TOOL_CALL_START——
 * 这正是框架用来避免 `agent_spawn` 同步阻塞期间子 Agent 事件污染顶层 AG-UI 协议状态机的机制，无需
 * 自行实现事件过滤。代价是 Custom 降级会丢失 toolCallId/toolName 等细节，因此这里按 `source` 关联
 * 前置的 `subagent.tool_call` 事件补全（`onRawEvent`/`ToolCallStart` 分支为兼容未来切回 native 时保留）。
 * 父 run 在 `agent_spawn` 同步阻塞期间也不会自然发出 interrupt 终态，需要本类合成一次 `RUN_FINISHED(interrupt)`。
 *
 * ## `agent_spawn` 同步路径下 `TOOL_SUSPENDED` 永远拿不到的已确认根因（上游 agentscope-harness/core
 * 2.0.1 的框架行为，见 `AgentSpawnSetDownloadPathSuspensionEndToEndTest` 的端到端回归测试）
 *
 * `agent_spawn` 走 `AgentSpawnTool.execLocalSync` → `DefaultAgentManager.invokeAgent` →
 * `ReActAgent.call()` → `callInternal()`。`callInternal` 对 `buildAgentStream(...)` 做的是局部订阅，
 * 其中真正携带 `GenerateReason.TOOL_SUSPENDED` 的 `AgentResultEvent`（以及紧随其后的 `AgentEndEvent`）
 * 是直接调用 `buildAgentStream` 自己局部捕获的 `sink`，完全不经过
 * [io.agentscope.core.event.AgentEventEmitter.fromForwardingContext]（`agent_spawn` 通过
 * `FORWARDING_CONTEXT_KEY` 注入、专门用来把子 Agent 事件转发进父 run 事件流的机制）。因此
 * [onAgentResultRaw]/[buildFromToolSuspended] 依赖监听的 `AgentResultEvent` **永远不会到达这里**——
 * 这不是本类促升逻辑的 bug，而是框架同步委派路径本身丢失了子 Agent 的挂起信号。这两个方法作为面向未来
 * （框架修复后，或 `emitSubagentEventsAsNative=true` 时）的兼容分支保留，但当前生产链路不会命中。
 *
 * 该 bug 属于上游开源库 `io.agentscope:agentscope-harness`/`agentscope-core`（`agentscope-ai/
 * agentscope-java`，Apache 2.0），bk-repo 无法直接修改其字节码，因此这里换一条**已经在事件流里、
 * 不依赖 `AgentResultEvent` 的路径**做变通：细粒度的 `ModelCallStart`/`ToolCall*`/`ToolResult*` 事件
 * 是 reasoning/acting 内部通过 `AgentEventEmitter` 发出的，会正确走 forwarding 转发并被
 * `SubagentEventConverter` 降级为 `Custom(subagent.tool_result, ...)` 送达这里；而挂起结果的
 * `ToolResultEndEvent.state` 在 [io.agentscope.core.ReActAgent] 内部由 `determineToolResultState`
 * 计算——`result.isSuspended() == true` 时恒为 `RUNNING`（成功是 SUCCESS，失败是 ERROR，用户拒绝是
 * DENIED，唯独挂起是 RUNNING）。因此 [buildFromSuspendedToolResult] 直接监听
 * `Custom(subagent.tool_result, {type=TOOL_RESULT_END, state=RUNNING})`，一旦出现就等价于子 Agent
 * 的这次工具调用被挂起、需要客户端确认/执行，据此合成 `RUN_FINISHED(interrupt)`——完全在 bk-repo 自己
 * 的代码里解决，不依赖上游修复。
 *
 * ## 工具调用其实是两条完全不同的路径，[buildFromSuspendedToolResult] 只应该响应其中一条
 *
 * - **后台（服务端）工具路径**：[com.tencent.bkrepo.agent.tool.domain.DomainToolRegistrar] 注册的
 *   领域工具（`list_repositories`/`get_transfer_task_status` 等），是普通 Java 方法，在服务端
 *   `ToolExecutor.executeCore` 里同步跑到底，正常结束只会落到 SUCCESS/ERROR，`isSuspended()`
 *   永远是 false，因此 `ToolResultEndEvent.state` 永远不会是 RUNNING。
 * - **客户端本地工具路径**：[com.tencent.bkrepo.agent.tool.frontend.FrontendToolRegistrar] 用
 *   [io.agentscope.core.tool.SchemaOnlyTool]（或
 *   [com.tencent.bkrepo.agent.tool.local.ExternalLocalTool]）注册的 frontend tools（如
 *   `set_download_path`），`ToolBase.externalTool=true`，服务端 `ToolExecutor.executeCore` 会直接
 *   短路成 `ToolResultBlock.suspended(...)`，从不真正执行——这才是 `state=RUNNING` 的唯一来源。
 *
 * 二者共用同一个 `Custom(subagent.tool_result, TOOL_RESULT_END, ...)` 事件形态，理论上
 * `state=RUNNING` 已经是"仅可能来自客户端本地工具"的唯一编码，但这里仍然显式用
 * [frontendToolCatalog] 校验 `toolName` 确实在客户端本地工具 allowlist 里才促升——一是让"两条路径"
 * 在代码里也是显式的、不是靠隐含推理成立的；二是防御未来上游/领域工具行为变化时把后台工具误判成挂起。
 */
@Component
class SubagentHitlPromoter(
    private val frontendToolCatalog: FrontendToolCatalog,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class PendingToolCall(
        val toolCallId: String,
        val toolName: String,
        val source: String,
        val toolInput: Map<String, Any?>? = null,
    )

    class State {
        val pendingBySource = mutableMapOf<String, MutableList<PendingToolCall>>()
        val pendingInOrder = mutableListOf<PendingToolCall>()
        var promoted = false
    }

    fun onEvent(event: AguiEvent, interruptState: AguiInterruptTracker.State, state: State) {
        if (state.promoted) return
        when (event) {
            is AguiEvent.Custom -> onCustomEvent(event, interruptState, state)
            is AguiEvent.Raw -> onRawEvent(event, interruptState, state)
            is AguiEvent.ToolCallStart -> trackNativeToolCall(event, interruptState, state)
            else -> Unit
        }
    }

    fun buildRunFinishedIfNeeded(
        event: AguiEvent,
        threadId: String,
        runId: String,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ): AguiEvent.RunFinished? {
        if (state.promoted) return null
        return when (event) {
            is AguiEvent.Custom ->
                buildFromRequireConfirm(event, threadId, runId, interruptState, state)
                    ?: buildFromSuspendedToolResult(event, threadId, runId, state)
            is AguiEvent.Raw -> buildFromRawEvent(event, threadId, runId, state)
            else -> null
        }
    }

    private fun onCustomEvent(
        event: AguiEvent.Custom,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        // TODO 临时诊断日志：定位 agent_spawn 同步阻塞期间子 Agent Custom 事件是否实际到达本类。
        // 确认 HITL 促升链路打通后可删除。
        if (event.name() == NAME_LIFECYCLE || event.name() == NAME_TOOL_CALL || event.name() == NAME_REQUIRE_CONFIRM) {
            logger.info("subagent custom event received: name={} value={}", event.name(), event.value())
        }
        when (event.name()) {
            NAME_TOOL_CALL -> trackToolCall(event, interruptState, state)
        }
    }

    private fun onRawEvent(
        event: AguiEvent.Raw,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        when (val agentEvent = event.event()) {
            is RequireUserConfirmEvent -> trackRequireConfirm(event, agentEvent, interruptState, state)
            is AgentResultEvent -> onAgentResultRaw(event, agentEvent, interruptState)
            else -> Unit
        }
    }

    private fun buildFromRawEvent(
        event: AguiEvent.Raw,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        return when (val agentEvent = event.event()) {
            is RequireUserConfirmEvent -> buildFromRawRequireConfirm(event, agentEvent, threadId, runId, state)
            is AgentResultEvent -> buildFromToolSuspended(event, threadId, runId, state)
            else -> null
        }
    }

    private fun buildFromRequireConfirm(
        event: AguiEvent.Custom,
        threadId: String,
        runId: String,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ): AguiEvent.RunFinished? {
        if (event.name() != NAME_REQUIRE_CONFIRM) return null

        val payload = asStringKeyMap(event.value()) ?: return null
        val source = payload["source"] as? String ?: return null
        val toolCallCount = (payload["toolCallCount"] as? Number)?.toInt() ?: 1
        val targets = resolvePendingTargets(source, toolCallCount, interruptState, state)
        if (targets.isEmpty()) {
            logger.warn(
                "subagent require_confirm without resolvable tool calls: source={} toolCallCount={} " +
                    "pendingSources={}",
                source,
                toolCallCount,
                state.pendingBySource.keys,
            )
            return null
        }

        val interrupts = targets.map { pendingCall -> buildPermissionInterrupt(pendingCall) }
        state.promoted = true
        logger.info(
            "promoted subagent permission HITL: source={} toolCalls={}",
            source,
            targets.map { it.toolName },
        )
        return runFinished(threadId, runId, interrupts)
    }

    private fun buildFromRawRequireConfirm(
        event: AguiEvent.Raw,
        confirmEvent: RequireUserConfirmEvent,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        val source = event.source()?.takeIf { it.isNotBlank() } ?: return null
        val targets = confirmEvent.toolCalls.mapNotNull { tool ->
            val toolCallId = tool.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val toolName = tool.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            PendingToolCall(toolCallId, toolName, source, tool.input?.takeIf { it.isNotEmpty() })
        }
        if (targets.isEmpty()) return null

        state.promoted = true
        logger.info(
            "promoted subagent permission HITL from raw RequireUserConfirmEvent: source={} toolCalls={}",
            source,
            targets.map { it.toolName },
        )
        val interrupts = targets.map { buildPermissionInterrupt(it) }
        return runFinished(threadId, runId, interrupts)
    }

    private fun buildFromToolSuspended(
        event: AguiEvent.Raw,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        if (event.source().isNullOrBlank()) return null
        val agentEvent = event.event() as? AgentResultEvent ?: return null
        val msg = agentEvent.result ?: return null
        if (msg.generateReason != GenerateReason.TOOL_SUSPENDED) return null

        val toolUses = msg.getContentBlocks(ToolUseBlock::class.java).orEmpty()
        val toolResults = msg.getContentBlocks(ToolResultBlock::class.java).orEmpty()
        val useById = toolUses.associateBy { it.id }
        val interrupts = toolResults.mapNotNull { result ->
            if (!result.isSuspended) return@mapNotNull null
            val callId = result.id ?: return@mapNotNull null
            val use = useById[callId]
            buildToolCallInterrupt(callId, use, result, event.source())
        }
        if (interrupts.isEmpty()) return null

        state.promoted = true
        logger.info(
            "promoted subagent TOOL_SUSPENDED HITL: source={} toolCalls={}",
            event.source(),
            interrupts.map { it.metadata()["toolName"] },
        )
        return runFinished(threadId, runId, interrupts)
    }

    /**
     * 监听 `Custom(subagent.tool_result, {type=TOOL_RESULT_END, state=RUNNING, ...})`。
     *
     * `state=RUNNING` 是 [io.agentscope.core.ReActAgent] 内部 `determineToolResultState` 对
     * `ToolResultBlock.isSuspended()==true` 的唯一编码（成功/失败/拒绝分别是 SUCCESS/ERROR/DENIED），
     * 因此只要观测到这个组合，就等价于子 Agent 这次工具调用被挂起、等待客户端执行——
     * 不需要（也拿不到）永远不会到达的 `AgentResultEvent(TOOL_SUSPENDED)`。
     */
    private fun buildFromSuspendedToolResult(
        event: AguiEvent.Custom,
        threadId: String,
        runId: String,
        state: State,
    ): AguiEvent.RunFinished? {
        if (event.name() != NAME_TOOL_RESULT) return null
        val payload = asStringKeyMap(event.value()) ?: return null
        if (payload["type"] != TYPE_TOOL_RESULT_END) return null
        if (payload["state"] != STATE_SUSPENDED) return null

        val toolCallId = payload["toolCallId"] as? String ?: return null
        val toolName = payload["toolName"] as? String ?: return null
        val source = payload["source"] as? String ?: ""
        if (toolCallId.isBlank() || toolName.isBlank()) return null

        // 只信任客户端本地工具路径：后台领域工具永远不会真正挂起（isSuspended()==false），
        // state=RUNNING 理论上不会出现在它们身上，这里显式校验只是让这条边界条件在代码里可见。
        if (frontendToolCatalog.find(toolName) == null) {
            logger.warn(
                "ignoring RUNNING tool_result for non-frontend tool (unexpected, backend tools " +
                    "should never report isSuspended()): toolCallId={} toolName={} source={}",
                toolCallId,
                toolName,
                source,
            )
            return null
        }

        val pending = state.pendingInOrder.find { it.toolCallId == toolCallId }
        val interrupt = buildSuspendedToolResultInterrupt(toolCallId, toolName, source, pending?.toolInput)

        state.promoted = true
        logger.info(
            "promoted subagent suspended tool_result HITL: source={} toolCallId={} toolName={}",
            source,
            toolCallId,
            toolName,
        )
        return runFinished(threadId, runId, listOf(interrupt))
    }

    private fun buildSuspendedToolResultInterrupt(
        toolCallId: String,
        toolName: String,
        source: String,
        toolInput: Map<String, Any?>?,
    ): AguiEvent.Interrupt {
        val metadata = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "subagentSource" to source,
        )
        toolInput?.let { metadata["toolInput"] = it }
        return AguiEvent.Interrupt(
            interruptId(toolCallId, REASON_TOOL_CALL),
            REASON_TOOL_CALL,
            "等待客户端执行 $toolName",
            toolCallId,
            null,
            null,
            metadata,
        )
    }

    private fun trackRequireConfirm(
        event: AguiEvent.Raw,
        confirmEvent: RequireUserConfirmEvent,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        val source = event.source()?.takeIf { it.isNotBlank() } ?: return
        confirmEvent.toolCalls.forEach { tool ->
            val toolCallId = tool.id?.takeIf { it.isNotBlank() } ?: return@forEach
            val toolName = tool.name?.takeIf { it.isNotBlank() } ?: return@forEach
            trackPending(
                PendingToolCall(
                    toolCallId = toolCallId,
                    toolName = toolName,
                    source = source,
                    toolInput = tool.input?.takeIf { it.isNotEmpty() },
                ),
                interruptState,
                state,
            )
        }
    }

    private fun onAgentResultRaw(
        event: AguiEvent.Raw,
        agentEvent: AgentResultEvent,
        interruptState: AguiInterruptTracker.State,
    ) {
        if (event.source().isNullOrBlank()) return
        val msg = agentEvent.result ?: return
        if (msg.generateReason != GenerateReason.TOOL_SUSPENDED) return

        val toolUses = msg.getContentBlocks(ToolUseBlock::class.java).orEmpty()
        val toolResults = msg.getContentBlocks(ToolResultBlock::class.java).orEmpty()
        val useById = toolUses.associateBy { it.id }
        for (result in toolResults) {
            if (!result.isSuspended) continue
            val callId = result.id ?: continue
            val use = useById[callId]
            val toolName = use?.name ?: result.name ?: continue
            interruptState.toolNameByCallId[callId] = toolName
            use?.input?.let { input ->
                interruptState.argsBufferByCallId[callId] = StringBuilder(input.toString())
            }
        }
    }

    private fun trackNativeToolCall(
        event: AguiEvent.ToolCallStart,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        val toolCallId = event.toolCallId()?.takeIf { it.isNotBlank() } ?: return
        val toolName = event.toolCallName()?.takeIf { it.isNotBlank() } ?: return
        if (toolName in AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS) return

        val source = subagentSourceFromRawEvent(event.rawEvent())
        trackPending(PendingToolCall(toolCallId, toolName, source), interruptState, state)
    }

    private fun subagentSourceFromRawEvent(rawEvent: Any?): String {
        if (rawEvent !is io.agentscope.core.event.AgentEvent) return ""
        return rawEvent.source?.takeIf { it.isNotBlank() } ?: ""
    }

    private fun trackToolCall(
        event: AguiEvent.Custom,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        val payload = asStringKeyMap(event.value()) ?: return
        if (payload["type"] != TYPE_TOOL_CALL_START) return

        val source = payload["source"] as? String ?: return
        val toolCallId = payload["toolCallId"] as? String ?: return
        val toolName = payload["toolName"] as? String ?: return
        if (toolCallId.isBlank() || toolName.isBlank()) return

        trackPending(PendingToolCall(toolCallId, toolName, source), interruptState, state)
    }

    private fun trackPending(
        pending: PendingToolCall,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ) {
        interruptState.toolNameByCallId[pending.toolCallId] = pending.toolName
        state.pendingBySource.computeIfAbsent(pending.source) { mutableListOf() }.add(pending)
        state.pendingInOrder.add(pending)
    }

    private fun resolvePendingTargets(
        source: String,
        toolCallCount: Int,
        interruptState: AguiInterruptTracker.State,
        state: State,
    ): List<PendingToolCall> {
        val count = toolCallCount.coerceAtLeast(1)

        state.pendingBySource[source]?.takeIf { it.isNotEmpty() }?.let { return it.takeLast(count) }

        state.pendingBySource.entries
            .filter { (key, _) -> sourcesMatch(key, source) }
            .flatMap { it.value }
            .takeIf { it.isNotEmpty() }
            ?.let { return it.takeLast(count) }

        state.pendingInOrder
            .filter { it.toolName !in AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS }
            .takeIf { it.isNotEmpty() }
            ?.let { return it.takeLast(count) }

        return interruptState.toolNameByCallId.entries
            .filter { (_, toolName) -> toolName !in AgentPermissionRulesConfiguration.HARNESS_ORCHESTRATION_TOOLS }
            .takeLast(count)
            .map { (toolCallId, toolName) ->
                PendingToolCall(toolCallId, toolName, source)
            }
    }

    private fun sourcesMatch(storedSource: String, requestedSource: String): Boolean {
        if (storedSource == requestedSource) return true
        val storedTail = storedSource.substringAfterLast('/')
        val requestedTail = requestedSource.substringAfterLast('/')
        return storedTail.isNotBlank() && storedTail == requestedTail
    }

    @Suppress("UNCHECKED_CAST")
    private fun asStringKeyMap(value: Any?): Map<String, Any?>? {
        if (value !is Map<*, *>) return null
        return value.entries.associate { (key, entryValue) ->
            key.toString() to entryValue
        }
    }

    private fun buildPermissionInterrupt(pending: PendingToolCall): AguiEvent.Interrupt {
        val metadata = linkedMapOf<String, Any?>(
            "agentscope.interruptKind" to "permission_confirm",
            "toolName" to pending.toolName,
            "subagentSource" to pending.source,
        )
        pending.toolInput?.let { metadata["toolInput"] = it }
        return AguiEvent.Interrupt(
            interruptId(pending.toolCallId, REASON_PERMISSION_CONFIRM),
            REASON_PERMISSION_CONFIRM,
            confirmMessage(pending.toolName),
            pending.toolCallId,
            null,
            null,
            metadata,
        )
    }

    /**
     * 供本类内部（[buildFromToolSuspended]）与 [SubagentConfirmResumeExecutor] 复用：把子代理挂起的
     * 工具调用统一编码为"请客户端本地执行"形状的 `AguiEvent.Interrupt`（`reason=tool_call`，不带
     * `agentscope.interruptKind=permission_confirm`，因此不会被误判为需要再次弹确认框）。
     */
    internal fun buildToolCallInterrupt(
        toolCallId: String,
        use: ToolUseBlock?,
        result: ToolResultBlock,
        source: String?,
    ): AguiEvent.Interrupt {
        val toolName = use?.name?.takeIf { it.isNotBlank() } ?: result.name.orEmpty()
        val metadata = linkedMapOf<String, Any?>(
            "toolName" to toolName,
            "subagentSource" to source,
        )
        use?.input?.takeIf { it.isNotEmpty() }?.let { metadata["toolInput"] = it }
        return AguiEvent.Interrupt(
            interruptId(toolCallId, REASON_TOOL_CALL),
            REASON_TOOL_CALL,
            result.output.joinToString("") { block -> block.toString() },
            toolCallId,
            null,
            null,
            metadata,
        )
    }

    private fun runFinished(
        threadId: String,
        runId: String,
        interrupts: List<AguiEvent.Interrupt>,
    ): AguiEvent.RunFinished = AguiEvent.RunFinished(
        threadId,
        runId,
        null,
        AguiEvent.RunFinishedInterruptOutcome(interrupts),
    )

    private fun interruptId(toolCallId: String, reason: String): String = "$reason-$toolCallId"

    private fun confirmMessage(toolName: String): String = "确认执行 $toolName？"

    companion object {
        private const val NAME_LIFECYCLE = "subagent.lifecycle"
        private const val NAME_TOOL_CALL = "subagent.tool_call"
        private const val NAME_TOOL_RESULT = "subagent.tool_result"
        private const val NAME_REQUIRE_CONFIRM = "subagent.require_confirm"
        private const val TYPE_TOOL_CALL_START = "TOOL_CALL_START"
        private const val TYPE_TOOL_RESULT_END = "TOOL_RESULT_END"
        private const val STATE_SUSPENDED = "RUNNING"
        private const val REASON_PERMISSION_CONFIRM = "permission_confirm"
        private const val REASON_TOOL_CALL = "tool_call"
    }
}
