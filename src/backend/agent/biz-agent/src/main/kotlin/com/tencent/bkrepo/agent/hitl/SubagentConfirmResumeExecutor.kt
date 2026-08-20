/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.hitl

import io.agentscope.core.ReActAgent
import io.agentscope.core.agent.Agent
import io.agentscope.core.agent.RuntimeContext
import io.agentscope.core.agui.event.AguiEvent
import io.agentscope.core.message.GenerateReason
import io.agentscope.core.message.Msg
import io.agentscope.core.message.MsgRole
import io.agentscope.core.message.ToolResultBlock
import io.agentscope.core.message.ToolUseBlock
import io.agentscope.harness.agent.HarnessAgent
import io.agentscope.harness.agent.gateway.SessionIdUtils
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * 绕开协调者大模型推理，直接重新驱动子代理（如 `client`）会话续跑，完成写操作确认的恢复。
 *
 * ## 为什么不能像协调者自身的 ASK 那样走 [PermissionConfirmResumeMiddleware]
 *
 * [PermissionConfirmResumeMiddleware] 把确认结果注入的是**协调者自己**这次调用的输入，只有当
 * ASKING 的工具调用挂在协调者自己身上时才有意义。但 `set_download_path` 之类写操作的 ASKING 实际发生
 * 在 `client` 等声明式子代理内部（见 [com.tencent.bkrepo.agent.tool.local.ExternalLocalTool]），协调者
 * 自己没有对应的 pending 工具调用；如果依旧把确认结果只喂给协调者，大模型只会看到一条空文本消息，只能
 * 随意作答（例如自我介绍），会话也无法正常收尾——这是本类要解决的问题。
 *
 * ## 关键前提：必须能独立算出与第一次 `agent_spawn` 完全相同的子代理 sessionId
 *
 * [io.agentscope.core.ReActAgent] 的状态是按 `RuntimeContext.getSessionId()`（经
 * `activateSlotForContext`）寻址持久化/恢复的，**与创建 Agent 实例时用的 `RuntimeContext` 无关**——
 * `createAgentIfPresent` 只是拿到一个"空壳"实例，真正决定读到哪份挂起状态的是随后 `call(msgs, ctx)`
 * 传入的 `ctx.getSessionId()`。而 [io.agentscope.harness.agent.tool.AgentSpawnTool] 在
 * `persistSession(false)`（子代理声明的默认值）时，每次 `agent_spawn` 都会生成一个**随机**
 * `sessionId`（`"sub-"+UUID`），本类事后完全无法复原——这是最初设计（假设"用协调者的
 * `(userId, threadId)` 就能找回子代理状态"）实测失败后才发现的真实框架行为。
 *
 * 因此前提是：[com.tencent.bkrepo.agent.agent.AgentFactory] 必须给 `client` 的
 * `SubagentDeclaration` 配置 `persistSession(true)`，这样 `AgentSpawnTool` 改用
 * `"sub-" + SessionIdUtils.deterministicHash(threadId, agentId)`（未带 label 时）——纯函数，本类可以
 * 用同样的 `threadId`/`agentId` 独立重算出一模一样的字符串，从而在 resume 时用它作为
 * `ctx.sessionId` 精确定位回第一次挂起时写入 [io.agentscope.core.state.AgentStateStore] 的那份状态。
 *
 * ## 为什么直接调用 `agent.call(msgs, ctx)` 就能解除 ASKING，不需要 middleware
 *
 * [io.agentscope.core.ReActAgent] 的 `call(List<Msg>, RuntimeContext)` 在进入推理循环之前，会先检查
 * 传入的 `msgs` 里是否带 [Msg.METADATA_CONFIRM_RESULTS]（`doCallInner`/`extractConfirmResults`），一旦
 * 命中就直接 `applyConfirmResults` + `resumeAgent()`——这个检查只依赖传进来的消息本身，不依赖任何
 * middleware 或 `RuntimeContext` 里的自定义属性。所以这里只需要构造一条携带该 metadata 的空文本消息，
 * 用上面算出的确定性 `ctx` 直接调用子代理实例的 `call(...)` 即可，完全不经过协调者，也就不依赖大模型
 * "猜到"要重新调用 `agent_spawn` 续跑同一个子代理。
 */
@Component
class SubagentConfirmResumeExecutor(
    private val harnessAgent: HarnessAgent,
    private val subagentHitlPromoter: SubagentHitlPromoter,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun resume(
        threadId: String,
        runId: String,
        userId: String,
        target: AguiPermissionResumeAdapter.SubagentResumeTarget,
    ): Flux<AguiEvent> {
        val agentManager = harnessAgent.subagentAgentManager
            ?: return errorFlux(threadId, runId, "coordinator has no subagent manager registered")

        val parentRc = RuntimeContext.builder().userId(userId).sessionId(threadId).build()
        val agent = agentManager.createAgentIfPresent(target.agentId, parentRc).orElse(null)
            ?: return errorFlux(threadId, runId, "unknown or unspawnable subagent: ${target.agentId}")

        val confirmMsg = Msg.builder()
            .role(MsgRole.USER)
            .textContent("")
            .metadata(mapOf(Msg.METADATA_CONFIRM_RESULTS to listOf(target.confirmResult)))
            .build()
        // 必须与 AgentSpawnTool 在 persistSession(true) 时使用的算法完全一致，否则找不回挂起状态——
        // 见类注释「关键前提」。要求 AgentFactory 已经给该 agentId 的 SubagentDeclaration 配置了
        // persistSession(true)，且 agent_spawn 调用时协调者/大模型没有传自定义 label（label 会改变哈希
        // 输入，本类无法获知那次调用用了什么 label，因此不支持带 label 的委派场景）。
        val childSessionId = "sub-" + SessionIdUtils.deterministicHash(threadId, target.agentId)
        val childCtx = RuntimeContext.builder(parentRc)
            .sessionId(childSessionId)
            .userId(userId)
            .build()

        val resultMono: Mono<Msg> = callAgent(agent, confirmMsg, childCtx)
            ?: return errorFlux(threadId, runId, "subagent '${target.agentId}' is not resumable: ${agent.javaClass}")

        return resultMono
            .flatMapMany { msg -> Flux.fromIterable(toEvents(msg, threadId, runId, target.agentId)) }
            .onErrorResume { ex ->
                logger.error(
                    "failed to resume subagent confirm: threadId={} agentId={}",
                    threadId,
                    target.agentId,
                    ex,
                )
                Flux.just(
                    AguiEvent.RunError(
                        threadId,
                        runId,
                        ex.message ?: "subagent resume failed",
                        "subagent_resume_failed",
                    ),
                )
            }
    }

    /** 与 [io.agentscope.harness.agent.subagent.DefaultAgentManager.invokeAgent] 相同的类型分支处理。 */
    private fun callAgent(agent: Agent, msg: Msg, ctx: RuntimeContext): Mono<Msg>? = when (agent) {
        is ReActAgent -> agent.call(listOf(msg), ctx)
        is HarnessAgent -> agent.call(listOf(msg), ctx)
        else -> null
    }

    private fun toEvents(msg: Msg, threadId: String, runId: String, agentId: String): List<AguiEvent> {
        val suspended = msg.getContentBlocks(ToolResultBlock::class.java).orEmpty().firstOrNull { it.isSuspended }
        if (msg.generateReason == GenerateReason.TOOL_SUSPENDED && suspended != null) {
            return suspendedToolCallEvents(msg, suspended, threadId, runId, agentId)
        }
        return finalReplyEvents(msg, threadId, runId, agentId)
    }

    /**
     * 确认后工具真正被执行、再次挂起等待客户端本地执行（例如 `set_download_path`）——复用
     * [SubagentHitlPromoter.buildToolCallInterrupt] 产出与 Bug 1 修复完全一致的"请客户端执行"形状，
     * 不带 `agentscope.interruptKind=permission_confirm`，因此不会被误判为需要再弹一次确认框。
     */
    private fun suspendedToolCallEvents(
        msg: Msg,
        suspended: ToolResultBlock,
        threadId: String,
        runId: String,
        agentId: String,
    ): List<AguiEvent> {
        val toolCallId = suspended.id
            ?: return finalReplyEvents(msg, threadId, runId, agentId)
        val use = msg.getContentBlocks(ToolUseBlock::class.java).orEmpty().firstOrNull { it.id == toolCallId }
        val interrupt = subagentHitlPromoter.buildToolCallInterrupt(toolCallId, use, suspended, agentId)
        logger.info(
            "subagent confirm resumed into new suspension: threadId={} agentId={} toolName={}",
            threadId,
            agentId,
            interrupt.metadata()?.get("toolName"),
        )
        return listOf(
            AguiEvent.RunStarted(threadId, runId),
            AguiEvent.RunFinished(threadId, runId, null, AguiEvent.RunFinishedInterruptOutcome(listOf(interrupt))),
        )
    }

    /**
     * 拒绝、或工具执行完成后子代理给出的自然语言回复（例如"已取消该操作"）——原样转发给客户端，
     * 不经协调者的人设二次包装。
     */
    private fun finalReplyEvents(msg: Msg, threadId: String, runId: String, agentId: String): List<AguiEvent> {
        val text = msg.textContent.orEmpty()
        logger.info("subagent confirm resumed with final reply: threadId={} agentId={} hasText={}", threadId, agentId, text.isNotBlank())
        val messageId = msg.id ?: UUID.randomUUID().toString()
        return buildList {
            add(AguiEvent.RunStarted(threadId, runId))
            if (text.isNotBlank()) {
                add(AguiEvent.TextMessageStart(threadId, runId, messageId, MsgRole.ASSISTANT.name.lowercase()))
                add(AguiEvent.TextMessageContent(threadId, runId, messageId, text))
                add(AguiEvent.TextMessageEnd(threadId, runId, messageId))
            }
            add(AguiEvent.RunFinished(threadId, runId, null, AguiEvent.RunFinishedSuccessOutcome()))
        }
    }

    private fun errorFlux(threadId: String, runId: String, message: String): Flux<AguiEvent> {
        logger.warn("subagent confirm resume failed to start: threadId={} message={}", threadId, message)
        return Flux.just(AguiEvent.RunError(threadId, runId, message, "subagent_resume_failed"))
    }
}
