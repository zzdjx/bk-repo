/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2026 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package com.tencent.bkrepo.agent.service.run

import com.tencent.bkrepo.agent.config.properties.AgentRuntimeProperties
import com.tencent.bkrepo.agent.model.TAgentRun
import com.tencent.bkrepo.agent.pojo.AgentRunStatus
import com.tencent.bkrepo.agent.runtime.ActiveRunManager
import com.tencent.bkrepo.agent.runtime.ActiveRunScope
import com.tencent.bkrepo.agent.service.AgentRunRecordService
import com.tencent.bkrepo.agent.service.AgentSessionService
import com.tencent.bkrepo.auth.pojo.enums.PermissionAction
import com.tencent.bkrepo.common.api.constant.HttpStatus
import com.tencent.bkrepo.common.api.exception.ErrorCodeException
import com.tencent.bkrepo.common.api.exception.TooManyRequestsException
import com.tencent.bkrepo.common.api.message.CommonMessageCode
import com.tencent.bkrepo.common.metadata.permission.PermissionManager
import com.tencent.bkrepo.common.security.exception.PermissionException
import io.agentscope.core.agui.model.RunAgentInput
import io.agentscope.core.shutdown.GracefulShutdownManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

/**
 * AG-UI run 的入口编排。
 *
 * 这里只做两件事：按顺序过一遍准入判断，然后把活交给三个协作者——[AgentRunInputPreparer] 清洗输入、
 * [AgentRunStarter] 拉起运行、[AgentRunEventPipeline] 消费事件流（收尾由 [AgentRunLifecycleManager]
 * 统一负责）。
 *
 * ## 准入判断的顺序是有理由的
 *
 * 五道闸按"拒绝得越便宜、越应该靠前"排列，这样被拒的请求不会白做后面的工作，客户端拿到的也是最切题
 * 的那个错误：
 *
 * 1. [assertAcceptingRequests]——本副本正在停机。最紧急，而且与请求内容无关。
 * 2. [assertGrayAllowed]——项目/用户不在放量名单里。名单外的用户该直接看到"未开通"，而不是先撞上
 *    一个参数校验错误。
 * 3. 输入清洗（[AgentRunInputPreparer]）——必须在鉴权之前，因为鉴权要用清洗后的 `threadId`。
 * 4. [assertAuthorized]——IAM 项目权限 + 会话归属。
 * 5. [resolveExistingRun] / [acquireRunLock]——幂等与并发。放最后是因为它们要查库、抢锁，是这一串
 *    里最贵的两步。
 */
@Component
class AgentRunOrchestrator(
    private val runtimeProperties: AgentRuntimeProperties,
    private val shutdownManager: GracefulShutdownManager,
    private val permissionManager: PermissionManager,
    private val agentSessionService: AgentSessionService,
    private val agentRunRecordService: AgentRunRecordService,
    private val activeRunManager: ActiveRunManager,
    private val inputPreparer: AgentRunInputPreparer,
    private val runStarter: AgentRunStarter,
    private val agentRunStreamOrchestrator: AgentRunStreamOrchestrator,
    private val lifecycleManager: AgentRunLifecycleManager,
    private val eventPipeline: AgentRunEventPipeline,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    fun run(userId: String, projectId: String, input: RunAgentInput): SseEmitter {
        assertAcceptingRequests()
        assertGrayAllowed(userId, projectId)
        val prepared = inputPreparer.prepare(userId, projectId, input)
        assertAuthorized(userId, projectId, prepared.threadId)
        resolveExistingRun(prepared.input)?.let { return it }
        val runScope = ActiveRunScope(userId, projectId, prepared.threadId)
        acquireRunLock(runScope)
        val scope = runStarter.start(userId, projectId, prepared, runScope)
        return attachEventStream(scope)
    }

    fun replayTerminal(input: RunAgentInput, existingRun: TAgentRun): SseEmitter {
        return agentRunStreamOrchestrator.replayTerminalForRun(input, existingRun)
    }

    /**
     * 停机窗口内直接回 503，让客户端重试到别的副本，而不是先建 run 记录、抢会话锁，再在订阅事件流时被框架的
     * `AgentShuttingDownException` 打断——那条路径上异常发生在异步的 Flux 里，HTTP 状态码早已发出，客户端只会
     * 收到一个语义模糊的失败流。
     *
     * K8s 摘流量是异步的：`preStop`/SIGTERM 之后 Service 的 endpoint 更新要等一小会儿，这段时间里新请求照旧
     * 会打到正在停机的副本上，所以这个前置判断不是理论上的边角情况。
     *
     * 判断与订阅之间仍有竞态（判断通过之后才开始停机），那种情况下由 [AgentRunEventPipeline] 的错误处理按普通
     * run 失败收尾，用户重试即可。这里只负责把常见情况做成干净的 503。
     */
    private fun assertAcceptingRequests() {
        if (shutdownManager.isAcceptingRequests()) {
            return
        }
        logger.info("Rejecting new agent run: replica is shutting down")
        throw ErrorCodeException(
            status = HttpStatus.SERVICE_UNAVAILABLE,
            messageCode = CommonMessageCode.SYSTEM_ERROR,
        )
    }

    /**
     * 灰度总闸：只回答"这个项目/用户能不能进"，不管进来之后能用哪些子 Agent 或工具——那需要把
     * `AgentCatalog`/`PermissionEngine`/`FrontendToolRegistrar` 这些启动期装配好的全局单例改造成
     * 按请求动态计算，是明显更大的改造，见 [AgentRuntimeProperties.Gray] 的说明。
     *
     * 故意读 [runtimeProperties]（live 的 `@ConfigurationProperties` bean）而不是启动期拍平一次的
     * [com.tencent.bkrepo.agent.config.properties.EffectiveAgentRuntimeProperties] 快照：前者的字段
     * 会在配置中心推送变更后被 Spring Cloud Context 原地 rebind，这样改名单/开关灰度不需要重启服务。
     * 那份快照里刻意没有 `gray` 字段，就是为了不给人留下"读快照也行"的错觉。
     */
    private fun assertGrayAllowed(userId: String, projectId: String) {
        if (runtimeProperties.gray.isAllowed(userId, projectId)) {
            return
        }
        logger.info("Rejecting agent run: project[$projectId] user[$userId] is not in the gray release allowlist")
        throw PermissionException("Project[$projectId] is not in the agent gray release allowlist")
    }

    /** 项目级 IAM 权限 + 会话归属：threadId 必须属于这个用户在这个项目下的活跃会话。 */
    private fun assertAuthorized(userId: String, projectId: String, threadId: String) {
        permissionManager.checkProjectPermission(PermissionAction.READ, projectId, userId)
        agentSessionService.assertActiveSession(userId, projectId, threadId)
    }

    /**
     * 幂等：同一个 runId 重复 POST 时不重新执行 Agent。已经跑完的直接回放事件，还在跑的拒绝掉——
     * 想跟上进度应该走 stream 接口，而不是再 POST 一次。
     */
    private fun resolveExistingRun(input: RunAgentInput): SseEmitter? {
        val existingRun = agentRunRecordService.findByRunId(input.runId) ?: return null
        return when (existingRun.status) {
            AgentRunStatus.RUNNING -> throw TooManyRequestsException("Run[${input.runId}] is already running")
            else -> agentRunStreamOrchestrator.replayTerminalForRun(input, existingRun)
        }
    }

    /** 一个会话同一时刻只允许一次 run。锁的释放在 [AgentRunLifecycleManager] 的收尾里。 */
    private fun acquireRunLock(scope: ActiveRunScope) {
        if (!activeRunManager.tryAcquire(scope)) {
            throw TooManyRequestsException("Session[${scope.threadId}] is already running")
        }
    }

    private fun attachEventStream(scope: AgentRunScope): SseEmitter {
        lifecycleManager.registerHandle(scope)
        lifecycleManager.attachEmitterCallbacks(scope)
        eventPipeline.subscribe(scope)
        return scope.emitter
    }
}
