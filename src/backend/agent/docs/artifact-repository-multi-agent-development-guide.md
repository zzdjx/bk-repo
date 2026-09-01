# 制品库多 Agent 后台开发手册

> 本文设计一个基于 AgentScope Java 2.0.1、通过官方 AG-UI 协议连接客户端的制品库多 Agent 后台。
>
> 重点覆盖服务架构、Agent 拓扑、身份、权限、工具、状态、会话、消息、记忆、知识、执行、观测，以及 Electron 本地工具在 AG-UI frontend tool / interrupt / resume 链路中的边界。
>
> 版本基线不能省略：bk-ci 使用 AgentScope 1.0.11，只能作为业务能力参考；本项目不得复制其兼容代码，应以 AgentScope 2.0.1 的 `RunAgentInput`、`AguiEvent`、HITL interrupt 和 `resume[]` 为准。AgentScope 2.0.0 的 `RunAgentInput` 尚无 `resume[]`，不能满足“不自定义协议”的本地工具和确认恢复要求。

## 1. 目标与边界

目标是建设一个独立部署、与 bk-repo 同仓、复用 bk-repo 认证和权限体系的多 Agent 服务。它能够：

- 理解用户关于项目、仓库、包、版本、制品、下载、上传、复制、配额、策略和权限的问题；
- 将复杂问题拆成有依赖关系的步骤，按前序结果决定下一步，而不是无条件同时启动全部 Agent；
- 将工作委派给职责明确、工具受限的专业 Agent；
- 对只读查询、诊断分析和写操作采用不同的安全策略；
- 在多轮会话、多副本部署、进程重启和模型失败时恢复运行；
- 保留用户可查看的完整会话原文，同时允许模型上下文独立压缩；
- 使用标准 AG-UI 请求、事件、消息 ID 和中断恢复语义连接客户端；
- 对每一次模型调用、Agent 委派、工具调用、权限检查和写操作进行追踪与审计。

明确不做：

- 不让模型直接访问数据库、DAO 或全权限内部接口；
- 不把用户身份作为模型可填写的工具参数；
- 不把 AgentScope 内部事件直接作为长期稳定的外部协议；
- 不自定义 `run` 请求、SSE 事件、本地工具结果或 HITL 恢复协议；
- 不把模型上下文等同于用户会话归档；
- 不为体现“多 Agent”而强行拆分简单任务；
- 不允许模型动态生成 Agent、扩大工具权限或修改安全规则；
- 不使用 Agent 服务账号替代真实用户执行资源操作。

## 2. 总体架构

```text
AG-UI 客户端（`@ag-ui/client`）
  ├─ `RunAgentInput(threadId, runId, messages, tools, context, resume)`
  ├─ 标准 AG-UI SSE 事件
  └─ Electron 本地工具经 IPC 执行
  │
  ▼
AG-UI Spring Boot Adapter
  ├─ `AguiRequestProcessor`
  ├─ `AguiAgentAdapter`
  └─ `AguiRuntimeContextResolver`
  │
  ▼
bk-repo common-security
  ├─ 认证用户
  ├─ 解析 userId / appId / tenant
  └─ 校验 project_view（PROJECT + READ）
  │
  ▼
Agent Application Service
  ├─ 会话归属校验
  ├─ 将可信 userId 注入 RuntimeContext
  ├─ 运行互斥、超时、取消
  ├─ 按 AG-UI messageId/runId 归档
  └─ 调用主 Agent
  │
  ▼
Coordinator Agent
  ├─ 理解意图
  ├─ 建立/更新任务计划
  ├─ 选择专业 Agent
  ├─ 基于前序结果继续委派
  └─ 汇总最终回答
  │
  ├─────────────┬─────────────┬─────────────┬─────────────┐
  ▼             ▼             ▼             ▼             ▼
Discovery     Transfer      Governance    Operations    Knowledge
Agent         Agent         Agent         Agent         Agent
制品检索       传输诊断       策略/权限分析   受控写操作      知识检索
  │             │             │             │             │
  └─────────────┴─────────────┴─────────────┴─────────────┘
                                │
                                ▼
                        Domain Tool Gateway
                        ├─ 参数校验
                        ├─ 从 RuntimeContext 取身份
                        ├─ bk-repo IAM 鉴权
                        ├─ 风险策略/HITL
                        ├─ 调用 bk-repo Service/API
                        ├─ 脱敏与结果裁剪
                        └─ 审计
                                │
                                ▼
                      bk-repo 领域服务与基础设施
```

服务分为三面：

- **控制面**：Agent 定义、模型配置、工具目录、权限规则、提示词版本、灰度和评估；
- **运行面**：会话、Agent 调用、子 Agent 任务、状态恢复、工具执行和事件输出；
- **数据面**：bk-repo 原有领域服务、认证服务、IAM、MongoDB、Redis 和检索设施。

Agent 服务应作为独立 Spring Boot Deployment，不与制品库核心 API 共用 JVM、线程池和资源配额。模型请求、长连接、子任务和上下文压缩都可能长时间占用资源，独立部署才能单独扩缩容、熔断和限流。

## 3. 多 Agent 拓扑

### 3.1 Coordinator Agent

Coordinator 是唯一对外主 Agent，负责：

- 判断请求是否属于制品库领域；
- 澄清缺失信息；
- 将复杂任务拆成有序步骤；
- 选择专业 Agent；
- 只在任务真正独立时并行委派；
- 验证专业 Agent 结果是否解决当前步骤；
- 综合证据、冲突和不确定性后生成最终回答。

Coordinator 不拥有制品删除、仓库配置修改、权限修改等业务写工具。它只拥有专业 Agent 委派工具、任务工具和少量无资源风险的只读上下文工具。

框架方案：

- 使用 `HarnessAgent` 作为主 Agent；
- 使用 `SubagentsMiddleware` 提供 `agent_spawn`、`agent_send`、`agent_list` 和任务工具；
- 使用 `enableTaskList(true)` 管理复杂任务步骤；
- 使用 `SubagentDeclaration` 注册固定专业 Agent；
- 禁用动态 Agent 和 Agent 生成能力。

不单独引入通用 Planner Agent。普通问题由 Coordinator 的 ReAct 循环规划；确定性业务事务由应用层状态机固定步骤。模型规划不能替代业务事务。

### 3.2 Artifact Discovery Agent

负责项目、仓库、包、版本和制品的发现与解释：

- 列出用户有权访问的项目和仓库；
- 查询包、版本、制品节点和元数据；
- 解释版本关系、依赖和制品来源；
- 比较多个版本或仓库；
- 为后续诊断或操作解析准确资源标识。

它只拥有只读查询工具。工具输出必须包含明确资源键，例如 `projectId`、`repoName`、`packageKey`、`version`、`path`，避免下游 Agent 根据自然语言猜测资源。

### 3.3 Transfer Diagnostics Agent

负责下载、上传、复制和分发问题：

- 读取传输状态、错误码、节点元数据和服务端日志摘要；
- 判断认证、权限、网络、磁盘、路径、存储和复制链路问题；
- 按证据逐步增加探针；
- 给出根因、置信度、证据和下一步行动；
- 必要时请求 Knowledge Agent 补充错误码或运维知识。

该 Agent 以观察工具为主。工具负责观察事实，Agent 负责解释和选择下一步，不设计 `diagnose_xxx` 巨型工具。

### 3.4 Governance Agent

负责治理与安全解释：

- 查询仓库策略、保留规则、配额、访问控制和审计记录；
- 解释用户为什么可以或不可以执行某个动作；
- 对策略变更做影响分析；
- 检查待执行操作是否违反组织规则；
- 输出治理建议，但不直接修改策略。

它可拥有权限和策略的只读工具，但不得读取用户无权查看的成员或敏感凭证。

### 3.5 Operations Agent

负责需要修改状态的操作：

- 创建或更新仓库配置；
- 删除、晋级、复制或移动制品；
- 修改保留策略、配额或元数据；
- 重试、暂停或恢复后台任务。

Operations Agent 不是高权限 Agent，而是工具集合更严格的专业 Agent：

- 所有工具显式 allowlist；
- `inheritParentPermissions(true)`；
- 写工具默认由 `PermissionEngine` 返回 `ASK`；
- 工具执行前使用真实用户身份校验原有 bk-repo 权限；
- 用户确认后、真正写入前再次鉴权；
- 所有写操作必须有幂等键和审计记录；
- 批量删除、权限扩大等高风险操作可直接 `DENY`。

### 3.6 Knowledge Agent

负责检索领域知识，不负责业务操作：

- 错误码、产品文档、运维手册和 FAQ；
- 制品格式和客户端兼容性；
- 已知故障和修复方案；
- 与当前 bk-repo 版本匹配的功能说明。

Knowledge Agent 只拥有检索工具。检索结果必须带来源、版本和更新时间。模型记忆不能替代受版本控制的知识库。

### 3.7 内部隐藏 Agent

标题生成、上下文摘要和评估判分等内部任务可配置为隐藏 Agent：

- `mode=SUBAGENT`；
- `hidden=true`；
- 不出现在 Coordinator 可见列表；
- 不继承业务工具；
- 使用低成本模型和固定输出格式。

## 4. AgentScope 与 AG-UI 能力边界

### 4.1 版本调查结论

- bk-ci 固定在 AgentScope 1.0.11，并依赖 `agentscope-agui-spring-boot-starter`；
- bk-repo 当前代码已锁定 AgentScope 2.0.1，依赖 Core、Harness、OpenAI、Redis 与 AG-UI 扩展；
- `/api/agent/run` 已切换为标准 `RunAgentInput` → `AguiEvent` SSE；客户端本地工具续跑使用官方 `resume[]`；
- 当前客户端手写 SSE parser、事件字段兼容映射和外部工具续跑循环，也不属于 `@ag-ui/client` 标准链路；
- AgentScope 2.0.1 已提供本项目需要的 `resume[]`、frontend tool、permission HITL 和 v2 `streamEvents()` 适配，应作为迁移目标。

“不自定义协议”不等于不写业务代码。会话 CRUD、IAM、审计、运行锁、消息归档和本地 IPC 仍由 bk-repo 实现；`run` 请求体、流式事件、tool interrupt 和 resume 必须使用 AG-UI 标准类型。

### 4.2 直接使用框架

直接使用框架：

- `HarnessAgent` / `ReActAgent`：推理与行动循环；
- `SubagentsMiddleware`：专业 Agent 注册、委派和任务协调；
- `SubagentDeclaration`：Agent 身份、模型、步数、工具和权限继承；
- `RuntimeContext`：调用级身份和业务上下文；
- `Toolkit`、`ToolBase`、`McpClientManager`：工具注册和 MCP；
- `PermissionEngine`、`PermissionRule`、`PermissionDecision`：ALLOW、DENY、ASK；
- `AgentState`、`AgentStateStore`、`RedisAgentStateStore`：运行时状态；
- `CompactionMiddleware`、`CompactionConfig`：上下文压缩；
- `ToolResultEvictionMiddleware`：超大工具结果外置；
- `InterruptControl` 和 `agent.interrupt(userId, threadId)`：中断；
- `MiddlewareBase`：Agent、reasoning、acting、model call 和 system prompt 扩展；
- `OtelTracingMiddleware`：Agent、模型和工具追踪；
- `ModelRegistry`、`fallbackModel`、`maxRetries`：模型解析、重试和单级降级；
- `enablePendingToolRecovery(true)`：挂起工具恢复。
- `agentscope-agui-spring-boot-starter`：Spring MVC/WebFlux AG-UI 接入；
- `RunAgentInput`：`threadId`、`runId`、`messages`、`tools`、`context`、`state`、`forwardedProps` 和 `resume`；
- `AguiRequestProcessor`、`AguiAgentAdapter`：消息转换、运行和 `AgentEvent` 到 `AguiEvent` 的标准映射；
- `AguiRuntimeContextResolver`：从受信任的 HTTP 请求注入用户、项目和设备上下文；
- AG-UI `RUN_*`、`TEXT_MESSAGE_*`、`TOOL_CALL_*`、`REASONING_*`、`CUSTOM` 和 interrupt outcome；
- `@ag-ui/client`：客户端运行、消息状态和标准事件消费。

### 4.3 必须由 bk-repo 自建

必须由 bk-repo 自建：

- 业务会话、消息原文和会话列表；
- common-security 认证结果到 AgentScope `RuntimeContext` 的接入；
- bk-repo IAM 与 Agent 工具的桥接；
- 多副本运行锁和业务幂等；
- AG-UI 之外的会话 CRUD、运行状态、停止、重连和事件持久化；
- 后台子任务的分布式持久化；
- 用量聚合和业务配额；
- 领域 RAG 和知识版本管理；
- Agent 定义、提示词和工具目录的发布治理；
- 评估集、回归门禁和灰度。

生产环境必须显式关闭：

- `agent_generate` 和动态生成专业 Agent；
- 动态技能提升；
- 与制品库无关的 shell、文件系统和代码执行工具；
- 模型可修改的权限规则和 Agent 定义；
- `inheritParentPermissions(false)`。

特别注意：`SubagentDeclaration.tools` 为空表示继承全部父工具，不是“不继承工具”。每个专业 Agent 都必须配置显式 allowlist。

## 5. 服务模块设计

代码继续遵循 bk-repo 的 `api-agent`、`biz-agent`、`boot-agent` 三段式，`biz-agent` 内按职责分层：

```text
src/backend/agent
├─ api-agent
│  ├─ session/       会话请求与响应
│  ├─ run/           AG-UI 之外的运行状态、停止和重连
│  ├─ approval/      approval 审计；线上交互使用 AG-UI interrupt/resume
│  └─ admin/         Agent 配置与灰度接口
│
├─ biz-agent
│  └─ com.tencent.bkrepo.agent
│     ├─ controller/       HTTP 接入
│     ├─ application/      用例编排
│     ├─ identity/         认证结果解析与运行身份构造
│     ├─ session/          会话和消息归档
│     ├─ runtime/          Agent 运行、锁、取消和恢复
│     ├─ topology/         主 Agent 与专业 Agent 定义
│     ├─ prompt/           版本化提示词
│     ├─ tool/
│     │  ├─ catalog/       工具元数据和 allowlist
│     │  ├─ gateway/       权限、审计和执行统一入口
│     │  ├─ repository/    项目/仓库/制品工具
│     │  ├─ transfer/      传输诊断工具
│     │  ├─ governance/    策略/权限查询工具
│     │  ├─ operation/     写操作工具
│     │  └─ knowledge/     RAG 工具
│     ├─ permission/       PermissionEngine 规则与 IAM 桥接
│     ├─ memory/           压缩、长期记忆策略
│     ├─ knowledge/        文档索引与检索
│     ├─ protocol/         AG-UI 接入配置、可信上下文和业务事件增强
│     ├─ observability/    trace、usage、audit
│     ├─ evaluation/       评估集和回归
│     ├─ persistence/      Mongo/Redis DAO
│     └─ config/           纯 Spring 装配
│
└─ boot-agent
   └─ AgentApplication
```

`application` 只编排用例，不实现模型推理；`topology` 只装配 Agent，不处理 HTTP；`tool` 不能直接相信模型传入的身份；`config` 不承载业务判断。

## 6. 身份与权限模型

### 6.1 认证入口

HTTP 入口复用 bk-repo `common-security`：

- Controller 使用 `@Principal(PrincipalType.GENERAL)`；
- `HttpAuthInterceptor` 和具体 `AuthHandler` 完成认证；
- 从受信任 request attribute 获取 `userId`；
- 不接受请求体或模型提供的 `userId`；
- 请求必须携带 query 参数 `projectId`；
- 会话入口：`POST /api/agent/session/create?projectId=`；
- 运行入口接收官方 `RunAgentInput` 并输出官方 `AguiEvent` SSE；路径可由网关配置，但请求体和事件不得包成自定义协议；
- 入口权限调用现有 `RAuthClient.checkPermission(PROJECT, READ, projectId)`，**不新增 IAM 动作、不改 `support-files/bkiam`**；

`@Principal(PrincipalType.GENERAL)` 只保证当前请求来自非匿名登录用户。它不检查：

- 用户是否在当前 `projectId` 拥有项目读权限（`project_view` / `PROJECT + READ`）；
- `threadId` 是否属于当前用户；
- 用户是否有权访问某个项目、仓库或路径；
- 某个写操作是否需要用户确认。

这些检查分别由 Agent 应用层、会话层、IAM 和 AgentScope 权限引擎承担。

### 6.2 使用 RuntimeContext 传递真实用户

第一阶段不新增 `AgentPrincipal`，通过 `AguiRuntimeContextResolver` 将认证结果写入 AgentScope v2 `RuntimeContext`。`RunAgentInput.threadId` 只作为会话标识，不能证明身份：

```kotlin
val runtimeContext = RuntimeContext.builder()
    .userId(authenticatedUserId)
    .sessionId(runAgentInput.threadId)
    .put("projectId", authenticatedProjectId)
    .put("deviceId", authenticatedDeviceId)
    .build()
```

调用前必须按以下顺序处理：

```text
common-security 认证
  → 从受信任 request attribute 取得 userId
  → 校验 PROJECT + READ(userId, projectId)
  → 校验 session.owner == (userId, projectId, RunAgentInput.threadId)
  → AguiRuntimeContextResolver 构造可信 RuntimeContext
  → AguiRequestProcessor / AguiAgentAdapter 调用 Coordinator Agent
```

主 Agent 创建子 Agent 时会基于父 `RuntimeContext` 创建子上下文，因此专业 Agent 和工具可以继续取得相同的真实 `userId`，不需要复制 bk-ci 的 `threadId → userId` Map、ThreadLocal 或 `Supplier<String>`。

约束：

- `RuntimeContext.userId` 只能由应用层从认证结果写入；
- `projectId` 来自请求 query 参数 `?projectId=`，创建会话时固化；不能信任 `context` 或 `forwardedProps` 中的同名字段；
- tool schema 不声明 `userId`、角色、token 或 ticket；
- 系统提示词和用户消息中不注入 userId；
- 工具不能接受模型传来的 operator/creator 字段覆盖当前用户；
- 不依赖 `SecurityUtils` 或 Servlet ThreadLocal 跨 Reactor、异步任务和子 Agent 传递身份；
- `AgentState`、会话归档、trace 和日志中不保存用户凭证明文。

如果以后确实需要同时传递 `tenantId`、`platformId` 等可信属性，可以通过 `RuntimeContext.put(AgentIdentity::class.java, identity)` 增加精简类型化对象；`RuntimeContext.userId` 仍是唯一用户 ID 来源，避免两套身份不一致。

### 6.3 内部服务调用的身份模型

第一阶段沿用 bk-ci 已验证的模式：

```text
服务间认证
  + RuntimeContext 中的真实 userId
  + 下游领域接口重新鉴权
```

其中：

- 服务间 JWT、内部 token 或平台凭证只证明“调用方是 Agent 服务”；
- 显式 `userId` 表示“本次操作代表哪个用户”；
- 下游 IAM/领域服务根据该用户和具体资源重新判断权限；
- 服务身份不能替代用户资源权限；
- Agent 服务不能把模型参数中的 userId 传给下游。

调用链：

```text
Tool
  → 从 RuntimeContext.getUserId() 取得真实用户
  → 构造 CheckPermissionRequest(uid=userId, resource, action)
  → RAuthClient.checkPermission
  → 通过服务间认证调用领域 Service/API
  → 下游再次执行原有权限注解或权限服务
```

如果工具与领域 Service 在同一 JVM，可显式传递 `userId` 并调用原权限服务，不需要用户 token。

OBO/Token Exchange 暂不作为第一阶段依赖。只有出现以下情况时再引入用户身份委托：

- 下游只接受用户 token，不接受可信服务传入的显式 userId；
- 调用跨越了当前内部服务信任域；
- IAM 明确要求验证 token 的 audience/scope；
- 长期后台任务必须在 HTTP 请求结束后持有可独立验证的用户授权。

即使未来引入 OBO，也只能作为下游可验证的短期凭证，不能替代每次资源 IAM 检查，也不能进入模型上下文和 AgentState。

### 6.4 三层权限

权限拆成三层：

1. **Agent 入口权限**：复用现有 `project_view`（`CheckPermissionRequest`：`PROJECT + READ + projectId`）；
2. **资源权限**：原有 bk-repo/IAM 对项目、仓库、路径和动作的检查；
3. **风险确认**：AgentScope `PermissionEngine` 判断 ALLOW、DENY 或 ASK。

AgentScope 权限不能代替 IAM；IAM 也不能代替用户确认。

工具统一经过 `DomainToolGateway`：

```text
模型生成 Tool Call
  → schema 校验
  → 从 RuntimeContext 取真实 userId
  → 解析资源键
  → RAuthClient.checkPermission(CheckPermissionRequest)
  → PermissionEngine 风险裁决
  → ASK 时暂停
  → 用户确认
  → 再次 IAM 鉴权
  → 幂等执行领域 Service/API
  → 脱敏、裁剪、审计
  → 返回 ToolResult
```

`CheckPermissionRequest` 使用 `RuntimeContext.userId` 作为真实 `uid`，使用会话冻结的 `RuntimeContext.projectId` 作为项目，并填入原有 `resourceType`、`action`、`repoName` 和 `path`。工具不能接受模型传入的 `projectId` 覆盖会话项目，也不能直接访问 DAO 绕过领域服务和权限边界。

只读工具在每次调用时重新执行资源 IAM 检查。写工具执行以下双重检查：

1. 模型生成 Tool Call 后先检查当前用户是否具有资源权限；
2. `PermissionEngine` 返回 `ASK` 并等待用户确认；
3. 用户确认后、实际写入前再次检查 IAM；
4. 使用幂等键执行写操作并记录审计。

该模式保留 bk-ci“显式 userId + 下游鉴权”的简单性，同时用 AgentScope v2 `RuntimeContext` 解决异步和多 Agent 身份传递，并补上会话归属、HITL 和二次鉴权。

## 7. 工具体系

### 7.1 工具定义

每个工具至少声明：

- 稳定名称：`verb_domain_object`；
- 单一职责描述；
- 严格 JSON Schema；
- 所属领域；
- 只读或写入；
- 风险等级；
- 对应 IAM resource/action；
- 是否幂等；
- 超时和最大结果大小；
- 可使用该工具的 Agent allowlist；
- 审计级别；
- 稳定错误码。

风险等级：

- `READ_SAFE`：普通只读查询，默认 ALLOW；
- `READ_SENSITIVE`：成员、审计或敏感元数据，额外 IAM；
- `WRITE_REVERSIBLE`：可恢复写操作，默认 ASK；
- `WRITE_DESTRUCTIVE`：删除或覆盖，ASK 且二次鉴权；
- `PROHIBITED`：权限扩大、凭证读取、越权批量操作，直接 DENY。

### 7.2 工具边界

工具只做确定性能力：

- “读取下载日志”是工具，“判断下载为什么失败”是 Agent；
- “查询仓库保留策略”是工具，“决定修改哪条策略”是 Agent；
- “删除一个已确认的制品”是工具，“是否值得删除”是 Agent 与用户共同决策。

禁止将检索、判断、规划和执行合并成巨型工具。

### 7.3 实现方式

选择顺序：

1. 同服务可安全复用的领域 Service；
2. bk-repo 内部 API/Reactive Feign Client；
3. 已有标准 MCP 服务；
4. 最后才新增专用适配。

无论哪种方式，都必须经过统一工具网关。Agent 不直接持有 DAO、MongoTemplate 或全权限内部 Client。

## 8. 状态、会话、上下文与记忆

### 8.1 四类数据分离

**运行时状态**

- 内容：`AgentState.context`、pending tool、任务计划和权限上下文；
- 存储：`RedisAgentStateStore`；
- 生命周期：热数据，可过期、可重建；
- 所有者：AgentScope。

**业务会话**

- 内容：标题、用户、创建时间、更新时间、状态和入口；
- 存储：MongoDB；
- 生命周期：长期；
- 所有者：bk-repo Agent 服务。

**完整消息归档**

- 内容：未经压缩的用户消息、助手消息、工具摘要和事件引用；
- 存储：MongoDB，单份完整存储；
- 生命周期：按业务合规策略；
- 所有者：bk-repo Agent 服务。

**知识与长期记忆**

- 内容：产品知识、错误码、用户明确保存的偏好；
- 存储：知识索引或 AgentScope memory backend；
- 生命周期：独立于会话；
- 所有者：知识/记忆模块。

不得把 `AgentState.context` 当成会话历史，因为框架会压缩它。也不得因为完整归档存在，就禁止模型上下文裁剪。

### 8.2 上下文管理

使用框架：

- `CompactionConfig` 配置触发 token、保留窗口和摘要提示词；
- `ConversationCompactor` 安全切分，避免拆散 tool call/result；
- `ToolResultEvictionMiddleware` 外置超大工具结果；
- `maxContextTokens` 限制上限。

摘要必须保留用户目标、已确认资源、关键证据、权限拒绝、用户确认、未完成任务、专业 Agent 结论，以及不得重复执行的写操作幂等键。

### 8.3 长期记忆

可保存：

- 用户明确允许保存的偏好和工作习惯；
- 可跨会话复用的稳定业务事实。

不保存：

- token、ticket、密码和签名 URL；
- 一次性错误日志；
- 权限快照；
- 未经用户确认的模型猜测；
- 可从 bk-repo 实时查询的资源状态。

第一期可不启用长期记忆。若启用 Harness memory pipeline，必须接分布式存储并按用户/租户隔离。

### 8.4 领域知识

错误码、文档、运维手册和已知问题使用 RAG，而不是长期记忆：

- Knowledge Agent 通过检索工具查询；
- 每条结果返回来源、版本、更新时间和引用；
- 先按当前 bk-repo 版本、制品类型和场景过滤；
- 无足够证据时明确返回未知。

### 8.5 AG-UI 会话、运行与消息标识

三个 ID 不能混用：

- `threadId`：AG-UI 对话线程 ID，bk-repo 对外 API、Mongo 持久化与 `RunAgentInput` 统一使用该字段名；
- `runId`：一次 AG-UI 执行，由客户端在发请求前生成，并由请求、SSE、运行记录和 trace 全链路复用；
- `messageId`：一条消息的稳定 ID。客户端生成 USER 消息 ID；助手消息 ID 来自 `TEXT_MESSAGE_START`；工具调用使用独立的 `toolCallId`。

一次 run 因工具执行或用户确认中断后，以相同 `threadId`、新的 `runId` 和 `resume[]` 发起下一次 run。前后运行通过 `interruptId` 关联，不复用旧 `runId`。

标准请求示例：

```json
{
  "threadId": "s-7d35...",
  "runId": "run-0d98...",
  "messages": [
    {
      "id": "msg-f263...",
      "role": "user",
      "content": "为什么这个下载任务一直没有速度"
    }
  ],
  "tools": [],
  "forwardedProps": {
    "deviceId": "client-mac-xxx"
  },
  "context": [
    {
      "description": "projectName",
      "value": "示例项目"
    }
  ]
}
```

消息规则：

- 客户端发送本轮新增消息即可；服务端已有 AgentState 时不要求重传完整历史；
- USER 消息必须有 `messageId`，相同 `(threadId, messageId)` 重试不得重复归档或重复触发执行；
- 客户端不得发送 `system` 或伪造 `assistant` 消息；允许的 role 由服务端白名单校验；
- AgentScope 2.0.1 使用类型化 `MessageContent`，归档必须保留结构化内容，同时提供纯文本投影用于标题和搜索；
- SSE 增量按 `messageId` 聚合；`TEXT_MESSAGE_END` 后形成完整助手消息；
- 历史 API 返回持久化的原始 `messageId`，不得在客户端重新生成；
- `context`、`state` 和 `forwardedProps` 都是不可信业务输入，不能承载 userId、ticket、IAM 结论或资源权限。

### 8.6 Frontend tool 与 HITL

Electron 本地能力使用 AG-UI frontend tool：

1. 客户端在 `RunAgentInput.tools[]` 声明本地工具 schema；
2. 服务端按 allowlist 校验工具名、参数 schema 和风险等级，再由 AG-UI adapter 进行 run-scoped 注入；
3. AgentScope 输出标准 `TOOL_CALL_START/ARGS/END`；
4. 外部执行或 ASK 暂停通过 `RUN_FINISHED.outcome.interrupts[]` 返回；
5. 客户端展示确认、通过 IPC 执行本地工具；
6. 客户端以相同 `threadId`、新 `runId` 和标准 `resume[]` 恢复；
7. 服务端在真正执行写操作前重新检查 IAM、approval 有效期和幂等键。

禁止保留 `REQUIRE_EXTERNAL_EXECUTION`、`externalExecutionResults`、自定义确认事件或自定义 resume DTO。客户端工具 schema 不构成授权，服务端目录和 RuntimeContext 才是可信边界。

## 9. 运行、并发与恢复

### 9.1 运行实体

- `AgentSession`：AG-UI `threadId` 对应的用户会话；
- `AgentRun`：一次 `RunAgentInput` 对应的 AG-UI 运行；interrupt 恢复会创建新 run；
- `AgentTask`：主 Agent 委派给专业 Agent 的子任务；
- `AgentApproval`：高风险操作确认；
- `AgentToolInvocation`：一次工具执行；
- `AgentUsage`：模型和工具用量。

一个 Session 同一时间最多一个前台 Run。相同用户的不同 Session 可以并发。

### 9.2 多副本互斥

AgentScope 提供单 JVM 的同会话串行，但不能覆盖多个服务实例。需要 Redis 分布式锁：

- 锁键包含 `userId + threadId`；
- 获取失败立即返回“会话正在运行”，不堆积排队；
- 锁有租约和续期；
- run 结束、异常和取消均释放；
- 锁只解决互斥，不替代写工具幂等。

### 9.3 子 Agent 调度

第一期采用同步、递进委派：

1. Coordinator 调用一个专业 Agent；
2. 检查该 Agent 的证据和结论；
3. 根据结果决定是否调用下一个 Agent。

只有满足以下条件才并行：

- 子任务无前序依赖；
- 不修改同一资源；
- 不共享易变状态；
- 结果可独立合并；
- 并行能显著降低延迟。

AgentScope 支持 `agent_spawn(timeout_seconds=0)` 和后台任务，但框架默认的 `WorkspaceTaskRepository` 不满足多副本服务持久化要求。启用后台子 Agent 前，必须实现基于 Mongo/Redis 的 `TaskRepository`，保证状态、结果、取消和 delivery 标记可跨实例恢复。

### 9.4 中断与超时

- 会话取消调用 `agent.interrupt(userId, threadId)`；
- 模型调用和工具调用分别配置 `ExecutionConfig` 超时；
- 子 Agent 使用 `SubagentDeclaration.steps` 限制迭代；
- Coordinator 设置最大模型调用、委派数、工具调用和总时长；
- 超时不能自动重复写工具；
- 用户取消后，已提交领域任务按业务语义继续或补偿，不能假定 JVM 中断等于业务回滚。

### 9.5 恢复

- 使用 `AgentStateStore` 恢复主 Agent和持久化子 Agent 状态；
- 开启 `enablePendingToolRecovery(true)`；
- `persistSession=true` 只用于确实需要跨轮持续上下文的专业 Agent；
- 一次性检索和诊断 Agent 默认 `persistSession=false`；
- 恢复后重新校验当前用户权限，不复用旧权限判断；
- 已确认但未执行的写操作检查 approval 是否过期及幂等结果。

## 10. 模型层

模型通过 `ModelRegistry` 和 Provider 接入，优先使用蓝鲸模型网关的 OpenAI 兼容协议。

按角色选择模型：

- Coordinator：推理能力优先，温度低，支持稳定工具调用；
- Discovery、Diagnostics、Governance：按任务复杂度使用中等模型；
- Operations：低温度，要求稳定结构化输出；
- Knowledge、标题、摘要：低成本模型；
- 高风险写操作不能因为切换模型而改变权限策略。

使用框架：

- `model(modelId)`；
- `GenerateOptions`；
- `maxRetries(n)`；
- `fallbackModel(modelId)`；
- `modelExecutionConfig(ExecutionConfig)`；
- `toolExecutionConfig(ExecutionConfig)`。

自建 `ModelPolicy`：

- 根据 Agent 类型和任务等级选择模型；
- 控制 token、并发和成本；
- 模型不可用时只做同能力等级降级；
- 不支持可靠工具调用的模型不能承担 Operations Agent；
- 降级后仍通过原权限和工具网关。

## 11. 事件、观测与审计

### 11.1 对外事件

AgentScope `AgentEvent` 是内部事件源，必须由官方 `AguiAgentAdapter` 映射为 AG-UI：

- `RUN_STARTED`、`RUN_FINISHED`、`RUN_ERROR`；
- `TEXT_MESSAGE_START/CONTENT/END`；
- `TOOL_CALL_START/ARGS/END/RESULT`；
- `STATE_SNAPSHOT/DELTA`；
- `CUSTOM`；
- interrupt outcome 和后续 `resume[]`。

不得透传 `TEXT_BLOCK_DELTA`、`AGENT_END`、`REQUIRE_EXTERNAL_EXECUTION` 等 AgentScope 内部事件，也不得在客户端通过多组候选字段猜测事件结构。客户端使用 `@ag-ui/client` 消费事件。

生产环境默认关闭原始 reasoning 输出。需要展示过程时使用简短阶段说明、工具状态和证据摘要，不直接展示模型思维链。AgentScope 2.0.1 的正常错误终态使用 `RUN_ERROR`，不要求再补伪造的 `RUN_FINISHED`。

### 11.2 Trace

使用 `OtelTracingMiddleware` 采集：

- 主 Agent span；
- 子 Agent span；
- model call span；
- tool call span；
- token usage；
- 错误和耗时。

增加业务属性：

- 脱敏后的用户标识；
- `session_id`、`run_id`；
- `agent_id`、`tool_name`；
- 脱敏后的 project/repo 标识；
- permission decision；
- approval id；
- model id 和 prompt version。

### 11.3 用量

框架提供单次 `ChatUsage`（`ModelCallEndEvent`），跨调用聚合需自建，已落地为 `UsageTrackingMiddleware`：

- 落在 §12.3 `agent_run` 上，按 `runId` 聚合（不是按天/按用户聚合到独立集合）：一次 run 内可能发生多次模型调用（ReAct 工具调用循环、历史压缩摘要调用等），用 `$inc` 原子累加到对应 run 记录；
- 记录输入、输出、缓存 token、调用次数（含失败调用）和模型调用耗时之和；
- 计量失败只捕获日志，不阻塞主流程，也不重试或进入补偿队列（保持最简单实现，用量统计允许偶发丢失）；
- 配额、告警、成本分析看板尚未实现，留待后续单独排期。

### 11.4 审计

审计记录：

- 谁在何时通过哪个 Agent；
- 针对哪个资源；
- 请求什么动作；
- IAM 结果；
- PermissionEngine 结果；
- 是否经过用户确认；
- 工具结果状态和幂等键；
- 实际执行者仍为真实用户。

审计记录行为和资源摘要，不记录凭证、完整提示词或无必要的敏感工具结果。

**工具级审计（tool-level auditing）已落地为 `ToolAuditMiddleware` + `agent_tool_call`**（见 §12.7）。核心难点是拍平方案下本地写工具（`ExternalLocalTool`）要经历两段挂起——`PermissionEngine` 判 ASK 时工具还没跑，用户确认后工具才真正被放行调用、但服务端视角只能看到"已转发给客户端"（永远拿不到客户端真实的执行结果，因为服务端从不真正执行本地工具）；客户端本地执行完、把结果通过 resume 回传时，命中的是框架 `TOOL_SUSPENDED` 的"纯状态替换"恢复路径，完全不经过 `onActing`。因此审计分两个钩子：

- `onActing`：工具批次开始时按 `ALLOWED` 落一条初始记录（`recordCalled`），随后依据事件流回填终态——`RequireUserConfirmEvent` → `ASKING`；`ToolResultEndEvent(state=DENIED)` → `RULE_DENIED`；其余 `ToolResultEndEvent` → 按 `ToolResultState` 映射到 `AgentToolResultState`（`RUNNING` 即"已放行、挂起转发给客户端"）。
- `onAgent`：每次协调者被调用时检查输入消息里的 `ToolResultBlock`（resume 时由框架标准转换器产出），若其 `toolCallId` 命中一行还停在 `RUNNING` 的记录，就用其真实 `state` 补齐终态（`recordClientReportedResult`），这一步补上客户端真实执行结果，是本设计相对"只记服务端能看到的状态"最初方案的增量。
- 用户在确认卡片点"拒绝"时，`AguiPermissionResumeAdapter` 直接调用 `recordUserDenied` 落 `ASK_DENIED`，不依赖 `onActing` 是否会重放（被拒绝的工具调用不一定会再走一次 acting 阶段）。
- 已知的剩余缺口：同一批工具调用里如果同时出现"需要 ASK"和"命中 DENY 规则"两种工具（罕见的并行调用场景），框架会整批直接返回 `RequireUserConfirmEvent` 并跳过 `runToolBatch`，被规则拒绝的那个工具调用不会有 `ToolResultEndEvent`，对应审计行会停在初始态——这是框架事件流本身的限制，代价与收益不成正比，暂不特殊处理。
- 记录内容做了截断（`argsDigest`/`resultDigest` 上限 2000 字符）和字段级去敏（只存摘要，不存完整工具输出），任何审计写入失败只记日志、不影响主链路。

## 12. 数据模型

### 12.1 agent_session

- `threadId`、`userId`、`projectId`、`title`、`status`；
- `createdAt`、`updatedAt`；
- `lastRunId`、`deleted`、`promptVersion`。

创建接口返回服务端真实的 `threadId`、`title`、`status`、`createdTime` 和 `updatedTime`，客户端不自行虚构时间。索引：唯一 `threadId`，以及 `userId + projectId + updatedAt`。

### 12.2 agent_message

- `messageId`、`threadId`、`runId`；
- `role`、结构化 `content`、可搜索 `textContent`、`agentId`；
- `toolCallId`、`createdAt`；
- `metadata`、`redactionVersion`。

索引：唯一 `threadId + messageId`，以及 `threadId + createdAt`、`runId`。客户端重试相同 messageId 时返回已有结果或当前状态，不重复写入。

**开发阶段重建**：字段或索引变更时，可直接 `drop` 相关集合后重启服务自动建索引，无需迁移脚本。

### 12.3 agent_run

- `runId`（来自 `RunAgentInput`）、`threadId`、`userId`、`projectId`；
- `status`、`outcome`、`entryAgentId`、`triggerType`；
- `startedAt`、`finishedAt`；
- `cancelReason`、`errorCode`、`traceId`；
- 用量：`modelCallCount`（含失败调用）、`inputTokens`、`outputTokens`、`cachedTokens`、`totalModelDurationMs`（模型调用耗时之和，区别于 run 总时长 `durationMs`），由 `UsageTrackingMiddleware` 监听 `ModelCallEndEvent` 按 runId `$inc` 累加。

`runId` 全局唯一；服务端若需要内部尝试号，另建 `executionId`，不得替换 AG-UI runId。resume run 通过 interrupt/approval 记录关联前一 run，`agent_run` 本身不存父子 run 关联字段——HITL 场景下一次用户交互可能横跨两个 runId（挂起的 run 与 resume 的 run），但这层关联已经能从 `originRunId` 追溯，不在 `agent_run` 上冗余存储。

### 12.4 agent_task

- `taskId`、`runId`、`parentTaskId`；
- `agentId`、`status`；
- `inputDigest`、`resultRef`；
- `createdAt`、`startedAt`、`finishedAt`；
- `deliveredAt`、`retryCount`。

### 12.5 agent_approval

- `approvalId`、`interruptId`、`originRunId`、`resumeRunId`、`toolCallId`；
- `userId`、`resourceDigest`、`action`；
- `status`、`expiresAt`、`confirmedAt`；
- `idempotencyKey`。

### 12.6 用量统计（已并入 agent_run，未单独建表）

最初规划为独立的按天聚合集合 `agent_usage_daily`（维度：日期、用户、项目、Agent、模型），实现后发现按天聚合会把同一用户同一天开的多个新会话摞进同一条记录、且一旦写入就无法拆分回溯，不满足"按次可查"的诉求。改为直接把用量字段挂在 §12.3 `agent_run` 上，天然按 `runId` 区分，不需要额外的聚合维度和单独的集合。

### 12.7 agent_tool_call（工具级审计）

由 `ToolAuditMiddleware`（见 §11.4）写入，每一次工具调用尝试对应一行：

- `runId`、`threadId`、`userId`、`projectId`、`toolCallId`（唯一约束 `runId + toolCallId`）；
- `toolName`、`argsDigest`（截断后的参数摘要）；
- `decision`：`ALLOWED` / `ASKING` / `RULE_DENIED` / `ASK_DENIED`；
- `resultState`：`SUCCESS` / `ERROR` / `INTERRUPTED` / `DENIED` / `RUNNING`（`RUNNING` 即"已放行、挂起转发给客户端，尚未拿到客户端真实执行结果"）；
- `resultDigest`（截断后的结果摘要）；
- `calledAt`、`resolvedAt`、`durationMs`。

索引：`runId + toolCallId`（唯一）、`toolCallId + resultState`（供 `onAgent` 钩子按 `toolCallId` 快速定位仍处于 `RUNNING` 的行）、`threadId + calledAt`、`userId + projectId + calledAt`。不建独立的按天/按用户聚合视图，需要报表时直接对本集合做时间范围查询。

Redis 仅保存：

- AgentScope `AgentState`；
- 会话运行锁；
- 短期取消信号；
- 临时限流计数；
- 必要的凭证引用和短期幂等缓存。

## 13. 按实现顺序开发

以下阶段以返工风险和依赖关系排列。每个阶段必须形成端到端可验收闭环。

### 阶段 0：领域范围、威胁模型和成功标准

**目标**

- 确定首批用户场景；
- 列出数据读取、写入和禁止操作；
- 明确多 Agent 的必要性；
- 建立评估基线。

**框架使用**

无。

**设计产物**

- 用例清单；
- Agent 职责和工具矩阵；
- IAM action/resource 映射；
- 风险等级；
- 20—50 条初始评估用例。

**验收**

- 每个场景有明确负责 Agent；
- 每个写操作有 IAM action、确认策略和幂等方案；
- 简单问题不会被强制多 Agent 化。

### 阶段 1：独立服务骨架与认证入口

**目标**

- 建立 `api-agent`、`biz-agent`、`boot-agent`；
- 将 AgentScope 统一升级并锁定到 2.0.1；
- 引入 `agentscope-agui-spring-boot-starter` 和官方 AG-UI 类型；
- 接入 common-security；
- 入口调用 `RAuthClient.checkPermission(PROJECT, READ, projectId)`；
- 将可信 `userId` 接入 AgentScope `RuntimeContext`。

**框架使用**

- `AguiRequestProcessor` / `AguiAgentAdapter`；
- `AguiRuntimeContextResolver`；
- `RuntimeContext` 作为身份和调用上下文容器。

**自建设计**

- Controller 只读取受信任的 `userId`；
- 每次运行校验 `session.owner == (userId, projectId, threadId)`；
- 使用 `RuntimeContext.userId` 作为 Agent 和工具的唯一用户身份来源；
- 内部服务调用采用“服务间认证 + 显式 userId + 下游 IAM”；
- OBO/Token Exchange 暂不实现；
- Agent 服务独立线程池、限流和健康检查。

**验收**

- 未认证请求拒绝；
- 当前项目无 `project_view` 请求拒绝；
- 请求体伪造 userId 不生效；
- 相同 threadId 不能被其他用户使用；
- userId 不进入工具 schema 和系统提示词；
- 主 Agent、子 Agent 和工具取得相同的 `RuntimeContext.userId`；
- `context` 或 `forwardedProps` 伪造 userId/projectId 不生效；
- AG-UI endpoint 不存在绕过 common-security 的备用路径。

### 阶段 2：模型接入与最小主 Agent

**目标**

- 完成无工具对话；
- 验证流式输出、超时和模型降级；
- 建立 Coordinator 最小系统提示词。

**框架使用**

- `HarnessAgent`；
- `ModelRegistry` / `OpenAIChatModel`；
- `GenerateOptions`；
- `maxRetries`、`fallbackModel`；
- AG-UI `RunAgentInput` / `AguiEvent`；
- `@ag-ui/client`。

**设计**

- 关闭 shell、filesystem、动态 skill、动态 subagent；
- 主 Agent 只回答制品库范围问题；
- 定义最大迭代、token 和总时长；
- 由官方 adapter 将 `streamEvents()` 转为 AG-UI SSE，不手写事件映射。

**验收**

- 一轮 `threadId + runId + messages[]` 流式对话成功；
- 模型超时可控；
- fallback 生效；
- 不泄露框架内部事件和思维链；
- `RUN_STARTED`、`TEXT_MESSAGE_*`、`RUN_FINISHED/RUN_ERROR` 符合 AG-UI；
- 客户端不包含兼容多个内部字段名的 heuristic parser。

### 阶段 3：工具契约与第一个只读工具

**目标**

- 建立 Tool Catalog 和 DomainToolGateway；
- 跑通一个真实 bk-repo 只读工具。

**框架使用**

- `Toolkit`；
- `ToolBase` 或反射工具；
- `ToolCallParam`；
- `RuntimeContext` 类型化注入；
- `PermissionDecision`。

**设计**

- 选择“查询仓库详情”或“查询制品元数据”；
- 工具参数只有资源字段，没有身份字段；
- 网关调用 `RAuthClient.checkPermission`；
- 结果结构化、脱敏并限制大小；
- 工具失败返回稳定错误码和可读信息。

**验收**

- 有权限用户成功；
- 无权限用户被拒绝；
- 模型伪造 userId 无效；
- 工具不能直接访问 DAO。

### 阶段 4：专业 Agent 与递进委派

**目标**

- 建立 Coordinator、Discovery 和 Diagnostics；
- 跑通“委派、接收结果、决定下一步、汇总”。

**框架使用**

- `SubagentsMiddleware`；
- `SubagentDeclaration`；
- `AgentSpawnTool`；
- `TaskTool`；
- `enableTaskList(true)`。

**设计**

- 专业 Agent 使用 `inlineAgentsBody` 或版本化代码配置；
- 每个 Agent 配显式工具 allowlist；
- `inheritParentPermissions(true)`；
- `persistSession(false)`；
- 第一版只允许同步或顺序委派；
- Coordinator 根据上一 Agent 的证据决定下一 Agent。

**验收**

- Discovery 不能调用写工具；
- Diagnostics 不能读取无权限仓库；
- Coordinator 不会对单步查询无意义地启动多个 Agent；
- 父 DENY 规则对子 Agent 生效。

### 阶段 5：会话、归档和运行时状态

**目标**

- 支持多轮会话和进程重启；
- 用户能查询完整历史；
- 模型状态与业务归档分离。

**框架使用**

- `AgentState`；
- `RedisAgentStateStore`；
- `(userId, threadId)` 状态命名空间；
- `MiddlewareBase.onAgent`。

**自建设计**

- Mongo 保存 session/message/run；
- Redis 保存运行时 state；
- 每次运行先校验 session 属于当前 user；
- 归档采用单份完整存储；
- 归档 middleware 对失败进行补偿；
- USER 消息沿用客户端 AG-UI `messageId`，助手消息沿用 `TEXT_MESSAGE_START.messageId`；
- `runId` 使用 `RunAgentInput.runId`，不再生成第二套后台 runId；
- 为 `(threadId, messageId)` 和 `runId` 建唯一约束。

**验收**

- 重启后能继续对话；
- 历史原文不因上下文压缩丢失；
- 不同用户使用相同 threadId 不能互相访问；
- 删除会话时按策略清理 Mongo 和 Redis；
- 同一 messageId 网络重试不会产生重复消息或重复 run；
- 历史 API 返回与 AG-UI 流一致的 messageId。

### 阶段 6：权限规则、HITL 和第一个写工具

**目标**

- 上线 Operations Agent；
- 跑通写操作确认和二次鉴权。

**框架使用**

- `PermissionEngine`；
- `PermissionRule`；
- `PermissionBehavior.ALLOW/DENY/ASK/PASSTHROUGH`；
- `RequireUserConfirmEvent`；
- AG-UI `RUN_FINISHED.outcome.interrupts[]`；
- `RunAgentInput.resume[]`；
- `stopOnReject(true)`。

**设计**

- 只读工具 ALLOW；
- 写工具 PASSTHROUGH 到规则引擎并默认 ASK；
- 高风险禁止工具 DENY；
- approval 绑定用户、资源摘要、动作、interruptId、originRunId、toolCallId 和过期时间；
- 确认后重新检查 IAM；
- 写入使用 idempotencyKey；
- 恢复请求使用相同 threadId 和新 runId，resume 覆盖全部未决 interrupt；
- Electron 本地工具通过 `tools[]` 声明、标准 tool-call 事件触发并经 IPC 执行。

**验收**

- 未确认写操作不执行；
- 权限在确认期间被撤销时执行失败；
- 重复确认不会重复写；
- 子 Agent 不能绕过父级 DENY；
- 同意、拒绝、过期、重复 resume 和缺失 interrupt 均有确定结果；
- 链路中不存在 `externalExecutionResults` 或自定义确认事件。

### 阶段 7：上下文压缩和工具结果治理

**目标**

- 长会话不超上下文；
- 大型工具结果不污染模型上下文；
- 压缩后任务仍可继续。

**框架使用**

- `CompactionMiddleware`；
- `CompactionConfig`；
- `ToolResultEvictionMiddleware`；
- `maxContextTokens`。

**设计**

- 使用真实会话分布设置触发阈值；
- 使用制品库专用摘要提示词；
- 保留资源标识、证据、审批和幂等信息；
- 原始完整消息只存在业务归档，不被压缩覆盖。

**验收**

- 超长对话不会失败；
- tool call/result 不被错误拆分；
- 压缩后不会重复执行已完成写操作；
- 摘要失败可降级到保留窗口。

### 阶段 8：Knowledge Agent 与 RAG

**目标**

- 接入版本化领域知识；
- 支持错误码和运维文档检索。

**框架使用**

- Knowledge Agent；
- Toolkit/MCP 检索工具；
- `MiddlewareBase.onSystemPrompt` 注入轻量环境信息。

**自建设计**

- 文档解析、索引、版本和 ACL；
- 查询改写、召回、重排和引用；
- 检索工具执行用户可见性过滤；
- 回答附来源和版本。

**验收**

- 能区分不同 bk-repo 版本文档；
- 无权文档不被召回；
- 无证据时明确未知；
- RAG 内容不能覆盖系统权限规则。

### 阶段 9：分布式子任务、并发、取消与恢复

**目标**

- 支持多副本和耗时专业 Agent；
- 支持安全并行和后台任务。

**框架使用**

- `TaskRepository` 接口；
- `agent_spawn(timeout_seconds=0)`；
- `task_output`、`task_cancel`、`task_list`；
- `InterruptControl`；
- `enablePendingToolRecovery(true)`；
- `RedisDistributedStore` 或组合 `DistributedStore`。

**自建设计**

- Mongo/Redis 实现分布式 `TaskRepository`（已完成，见下方说明）；
- Redis session run lock（已具备，见 `ActiveRunManager`/`RedisActiveRunStateStore`）；
- 后台结果 delivery 状态（已具备，见框架 `SubagentsMiddleware` 的 `<system-reminder>` 投递机制）；
- 超时、取消和恢复策略（已具备，见框架 `task_cancel`/pending tool recovery）；
- 只有独立子任务才并行（`max-parallel-delegations` 并发上限已完成，见下方"混合方案"说明）。

**验收**

- 服务实例切换后任务可查询；
- 任务结果最多重复投递、不永久丢失；
- 同 session 不出现两个前台 run；
- 取消不会导致写工具自动重试。

**分布式 `TaskRepository` 存储（已完成，窄范围）**

只解决"`agent_spawn` 同步等待超时后被框架 promote 出的后台任务记录，跨副本可查询"这一个点，不改变现有同步委派行为，也不开放 `timeout_seconds=0` 主动异步委派或 `max-parallel-delegations` 并发上限——后者已在下方"混合方案"一节完成。

没有直接采用框架自带的 `RedisDistributedStore`（强绑定 Jedis `UnifiedJedis`），因为项目里所有其它 Redis 相关代码（`RedisAgentStateStore`、`RedisActiveRunStateStore`、`RedisLock` 等）统一使用 Lettuce，引入 Jedis 会让同一个服务并存两套 Redis 客户端。同时也没有直接换成 Mongo，因为框架的 `WorkspaceTaskRepository` 本身已经内置了 heartbeat（30s）、孤儿任务清扫（10 分钟超时、5 分钟扫描间隔、带跨节点节流 marker）、任务 JSON 记录格式等完整逻辑，且它只依赖 `WorkspaceManager`、不直接依赖任何具体存储实现，只要把 `WorkspaceManager` 背后的 `BaseStore`（namespace 化的 KV 接口：`get`/`put`/`putIfVersion`/`search`/`delete`）换成 Redis 实现即可复用这套逻辑，没必要另起一套 Mongo 版本重新实现相同的编排。

最终实现（详见 `com.tencent.bkrepo.agent.task.LettuceStore` 与 `com.tencent.bkrepo.agent.config.AgentTaskRepositoryConfiguration`）：

- `LettuceStore implements BaseStore`：把框架 `io.agentscope.extensions.redis.store.RedisStore`（Jedis 版）的 Redis Hash + Sorted Set + Lua `EVAL` 方案逐字迁移到 Lettuce（`RedisCommands`/`eval`/`zrangebylex`），`put`/`putIfVersion` 各用一条 Lua 脚本把"读版本 + 写值 + 更新索引"合并成一次原子操作，保证 CAS 语义在多副本下安全。
- 用一个**专属**的 `RemoteFilesystem(lettuceStore, listOf("agents", <coordinatorName>, "tasks"))` + 专属的 `WorkspaceManager` 只服务于 `WorkspaceTaskRepository`，不经过框架的 `RemoteFilesystemSpec`（那个会把 `memory/`、`skills/`、`subagents/`、`AGENTS.md` 等所有工作区路径一起路由到远程存储）。协调者自己的工作区文件读写工具本来就已经被 `disableFilesystemTools()`/`disableDynamicSkills()` 等禁用，这里新增的远程存储只影响任务 JSON，其余部分行为完全不变。
- 没有 Lettuce Redis 客户端（本地开发/未接 Redis）时，`AgentTaskRepositoryConfiguration.agentTaskRepository()` 返回 `null`，`AgentHarnessConfigurer` 跳过 `.taskRepository(...)` 装配，`HarnessAgent.build()` 退回框架默认的本地文件系统 `WorkspaceTaskRepository`——与升级前行为完全一致。
- 配置项：`agent.runtime.task.key-prefix`（默认 `bkrepo:agent:task-store:`）。

**`max-parallel-delegations` 并发上限（已完成，混合方案）**

在窄范围完成之后，继续调研了 `agent_spawn(timeout_seconds=0)` 主动异步委派、后台结果 delivery、取消/恢复策略、session run lock、HITL 豁免等能力，发现除了并发上限之外的其余能力框架本身或项目此前都已具备（异步委派与结果投递走 `SubagentsMiddleware` 的 `<system-reminder>` 机制、取消走 `task_cancel`、session 互斥走既有的 `ActiveRunManager`/`RedisActiveRunStateStore`、编排工具已豁免 HITL），真正缺失的只有 `agent.runtime.topology.coordinator.max-delegations`/`max-parallel-delegations` 这两个配置项——此前只做了参数校验，从未在 `agent_spawn`/`agent_send` 调用处真正生效。

`agent_spawn`/`agent_send` 有两条执行路径，对硬限制的可行性完全不同：

- **异步路径**（`timeout_seconds=0` 主动异步，或同步等待超时后被框架 promote）：调用最终一定会落进 `TaskRepository.putTask(...)`，而这个接口已经是项目自己接管的组件（见上一节），可以在真正提交任务前插入一次预算检查。
- **同步路径**（`timeout_seconds>0` 且未超时）：完全在框架内部的 `AgentSpawnTool`/`SubagentsMiddleware` 包私有逻辑里跑完，中途没有任何项目侧可挂钩的落点；唯一能接触到"替换整个委派工具"的入口是 `HarnessAgent.Builder.externalSubagentTool(Object)`，但这意味着要复制并长期维护框架内部子 Agent 的完整装配逻辑（工具 schema、参数校验、错误信息、事件流拼装等），维护成本和框架升级风险都远超收益。

因此采用**混合方案**：

- **异步任务：硬限制**——`com.tencent.bkrepo.agent.task.LimitedWorkspaceTaskRepository` 继承（而非包装）`WorkspaceTaskRepository`，只覆盖 `putTask`：提交前用 `listTasks(...)` 统计当前 session 未终态的后台任务数，加上下面提到的同步在跑计数，达到 `maxParallelDelegations` 时直接返回一个已失败的 `BackgroundTask`（`super.putTask(...)` 根本不会被调用，子 Agent 不会被真正启动），不需要修改或替换 `AgentSpawnTool`。选择继承而不是装饰器，是因为 `HarnessAgent` 关闭时用 `instanceof WorkspaceTaskRepository` 判断要不要调用 `.shutdown()`（停 heartbeat/孤儿任务清扫线程），装饰器会让这个判断失效导致线程泄漏。
- **同步委派：动态软提醒**——新增 `com.tencent.bkrepo.agent.subagent.DelegationConcurrencyGuard`，进程内按 `sessionId` 统计当前正在同步执行、尚未返回的 `agent_spawn`/`agent_send` 调用数（不需要跨副本：`ActiveRunManager` 已保证同一 session 同一时刻只有一个副本在跑前台 run）。配套的 `com.tencent.bkrepo.agent.subagent.DelegationBudgetMiddleware` 在 `onActing` 阶段给这个计数器加/减账，在 `onReasoning` 阶段把"计数器里同步在跑的数量"与"`TaskRepository` 里未终态的后台任务数量"相加，一旦达到或超过预算，就在当轮额外注入一条 `<system-reminder>` 系统提示，要求模型停止新开委派、等现有的跑完或自己完成工作。这是基于实时计数的动态提醒而不是写死的固定文案，但本质仍是软约束——模型可以选择不听，因此不覆盖"同步委派在超时前已经跑完"这条硬限制打不到的路径。
- 两者共享同一个 `DelegationConcurrencyGuard` 实例，保证"当前活跃委派数"这个概念在硬限制和软提醒之间口径一致。
- 没有 Redis（`agentTaskRepository` bean 为 `null`）时，异步任务退回框架默认的本地 `WorkspaceTaskRepository`，不再有硬限制，只剩 `DelegationBudgetMiddleware` 的软提醒兜底——与阶段 9 窄范围部分"无 Redis 退化为单副本本地行为"的原则一致。

已知的非原子性权衡：`LimitedWorkspaceTaskRepository.putTask` 里"读取当前活跃数 → 判断 → 写入新任务"不是一次原子操作，极端并发下可能短暂超出预算一两个名额；目标是防止后台任务无限堆积失控，不是做精确的信号量限流，用这个代价换取实现复杂度和框架侵入性的大幅降低。

### 阶段 10：长期记忆与个性化

**目标**

- 只保存明确有价值、获准保存的信息；
- 跨会话复用稳定偏好。

**框架使用**

- Harness `MemoryConfig`；
- `MemoryFlushMiddleware`；
- `MemorySaveTool`、`MemorySearchTool`、`MemoryGetTool`；
- 分布式 workspace/store。

**设计（窄范围，已完成）**

第一次开启长期记忆能力，选择的是窄范围：只接入 `memory_save`/`memory_search`/`memory_get` 三个框架
内置工具 + 复用阶段 9 的 `LettuceStore` 做跨副本、按用户隔离的存储；用户直接查看/删除记忆的独立接口
留给后续阶段补充（`memory_search`/`memory_get` 目前只能通过 LLM 间接查，没有绕开 Agent 的直连 API）。

**删除能力与用户直连接口（已完成）**

窄范围完成之后补齐了两块：框架内置工具只有 `memory_save`/`memory_search`/`memory_get`，没有对应的
删除工具；且用户没有任何绕开 LLM、直接管理自己长期记忆的入口。

- `com.tencent.bkrepo.agent.memory.MemoryFilesystemAccess`：直接访问长期记忆底层存储的公共组件，
  被 `memory_delete` 工具和用户直连 REST 接口共用。因为框架的 `WorkspaceManager` 是
  `HarnessAgent.Builder.build()` 内部构造的私有实例、没有公开注入点，这里选择照抄
  `RemoteFilesystemSpec` 对 `MEMORY.md`/`memory/` 两个路由的命名空间规则（`["agents", <agentId>,
  "users", <uid>, <段>]`），独立构造两个 `RemoteFilesystem` 直接读写同一份 Redis 数据。这段命名空间
  逻辑因此在代码里存在两份（框架内部一份、这里复刻一份），是刻意接受的成本——框架没有给"绕开
  `WorkspaceManager` 直接读写记忆存储"这条路径提供公开 API。依赖的是 `BaseStore` 接口而非具体的
  `LettuceStore`，且不加 `@Component`，改由 `AgentMemoryFilesystemConfiguration` 用 `@Bean` 工厂方法
  装配，测试时可以直接传一个内存态的 `BaseStore` 假实现，不需要真实 Lettuce 连接。
- `memory_delete` LLM 工具（`com.tencent.bkrepo.agent.tool.memory.MemoryDeleteTool`）：寻址方式与
  内置 `memory_get` 对齐——`path + startLine/endLine`，按 1-based 闭区间行号删除，要求模型先用
  `memory_search`/`memory_get` 定位到具体行号再调用。删除通过 `RemoteFilesystem.edit` 的 CAS 字符串
  替换实现整篇覆盖式更新（内部自带最多 5 次版本冲突重试），而不是自己实现"读版本号 → 拼装新内容 →
  CAS 写回"，因为 `RemoteFilesystem` 没有对外暴露底层 `BaseStore` 或版本号。接入
  `AgentPermissionRulesConfiguration` 的 `MEMORY_DELETE_TOOL` ASK 规则，跟 `memory_save` 一样需要用户
  确认。
- 用户直连 REST 接口（`UserAgentMemoryResource`/`AgentMemoryService`）：文件级粒度，四个接口——列出
  我保存过的全部记忆文件、查看某个文件的完整内容、删除单个文件、清空全部记忆。直接复用
  `MemoryFilesystemAccess`，不经过 LLM/Toolkit/PermissionEngine（用户对自己的数据有完全控制权，不需要
  再走一次 HITL 确认自己主动发起的删除请求）。
- `MemoryFilesystemAccess` 的 `listFiles`/`readFile`/`deleteFile`/`deleteAll` 只覆盖框架
  `OverlayFilesystem` 的 upper 层（per-user、持久化在 Redis 的用户实际保存内容），不处理 lower 层的只读
  本地模板兜底——这符合"用户自己保存过的记忆"的语义定位，代价是如果工作区配置了 `MEMORY.md` 模板且
  用户从未调用过 `memory_save`，这里会认为文件不存在而不是显示模板内容；当前部署形态下（协调者已
  `disableWorkspaceContext()`）这个模板文件通常并不存在，属于可接受的边界行为。

**同意机制与一个关键冲突：自动 flush 钩子无法接入 HITL**

产品要求"记忆写入需用户明确同意"，选择的实现方式是复用现有 HITL/PermissionEngine：`memory_save`
接入 ASK 规则，跟其它写工具走同一套确认弹窗（见 `AgentPermissionRulesConfiguration` 里的
`MEMORY_SAVE_TOOL`），不需要为此新建同意 UI；只读的 `memory_search`/`memory_get` 走 ALLOW
（`MEMORY_READ_TOOLS`），不需要每次打断用户。

调研中发现框架的长期记忆管线还有第二条写入路径——`MemoryFlushMiddleware`：不是工具调用，而是在每轮
`onAgent` 结束后自动触发的钩子，另起一次 LLM 调用从对话里提炼"事实"直接写入
`MEMORY.md`/`memory/*.md`，默认每轮都触发（`FlushMode.ALWAYS`），同一个钩子还会无条件把原始对话写进
`agents/<agentId>/sessions/*.log.jsonl`（框架自己的会话续跑/搜索机制，与 bk-repo 现有的 AG-UI 事件归档
是两套并行的东西）。这条路径完全不经过 Toolkit/PermissionEngine，结构上没有 HITL 钩子可挂——也就是说
"写入需用户同意"这个要求，天然只能覆盖 `memory_save`，覆盖不到自动 flush。

**这里还有一个值得记录的意外发现**：在这次改动之前，`AgentHarnessConfigurer` 只调用了
`disableMemoryTools()`，从未调用过 `disableMemoryHooks()`。而框架安装
`MemoryFlushMiddleware`/`MemoryMaintenanceMiddleware` 的条件是"配置了记忆模型（默认取 agent 主模型，
永远非空）且未调用 `disableMemoryHooks()`"——与 `disableMemoryTools()` 完全独立。这意味着自动 flush
钩子从项目一开始就在悄悄运行：每轮对话结束后都会额外发起一次 LLM 调用把内容写到本地磁盘（借助框架默认的
`IsolationScope.USER`，写入路径按 `userId` 自动加了前缀，不会跨用户串），只是因为
`memory_save`/`memory_search`/`memory_get` 工具当时被禁用，没有任何读路径会用到这些文件，所以从未被
发现。这次改动确认了这一权衡后，选择无条件调用 `disableMemoryHooks()`：长期记忆只能通过 `memory_save`
显式写入，彻底关闭自动 flush，順带修复了这个"每轮多一次静默 LLM 调用+本地磁盘写入"的历史行为。

代价：`disableMemoryHooks()` 是一个粗粒度开关，关掉自动 flush 的同时也关掉了
`MemoryMaintenanceMiddleware` 提供的每日文件归档、`MEMORY.md` 定期整理、旧会话日志清理——阶段 10
窄范围内暂不补，TTL/定期整理留给后续阶段用独立的定时任务实现（不依赖框架这个和自动 flush 绑在一起的
每轮 hook）。

**存储实现**

`com.tencent.bkrepo.agent.config.AgentMemoryFilesystemConfiguration` 提供一个 `RemoteFilesystemSpec?`
bean：有 Lettuce Redis 客户端时，用阶段 9 写好的 `LettuceStore`（换一个独立的 key 前缀
`agent.runtime.memory.key-prefix`，默认 `bkrepo:agent:memory-store:`）构造
`RemoteFilesystemSpec(store).isolationScope(IsolationScope.USER)`；没有 Redis 时返回 `null`。

选择 `RemoteFilesystemSpec`而不是像阶段 9 `TaskRepository` 那样另起一个专属 `WorkspaceManager`，是因为
框架的记忆工具/钩子在 `HarnessAgent.Builder.build()` 内部固定共享同一个 `WorkspaceManager`
实例，没有独立注入点，只能通过 `HarnessAgent.Builder.filesystem(RemoteFilesystemSpec)`
把协调者的整个工作区换成远程存储。这在当前装配下是安全的：协调者已经对
`disableFilesystemTools()`/`disableDynamicSkills()`/`disableDynamicSubagents()`/
`disableWorkspaceContext()`全部禁用，工作区里除了记忆文件之外没有任何工具会碰其它路径；`agent_spawn`
的后台任务另有阶段 9 建的专属 `WorkspaceManager`，不受这里影响。

没有 Redis 时不像 `TaskRepository` 一样退回本地文件系统实现，而是让 `AgentHarnessConfigurer` 保持
`disableMemoryTools()`、整个长期记忆能力关闭——因为长期记忆的价值在于跨会话/跨副本稳定可见，"只在恰好
落到同一个副本时才能看到之前保存的记忆"这种局部可用，比完全不可用更容易让用户困惑。`RemoteFilesystemSpec`
要求 `AgentStateStore` 必须是分布式实现（否则 `HarnessAgent` 构建时直接抛异常）——这里与
`AgentStateConfiguration.agentStateStore` 用的是同一个 Lettuce 客户端可用性信号，两者总是同时具备或同时
缺失，不会触发这个校验失败。

**验收**

- 用户可以查看和删除记忆（已完成：`UserAgentMemoryResource` 提供列表/查看/删除单个/清空全部四个接口，
  `memory_delete` 工具让 LLM 也能按用户指示精确删除指定行）；
- 不保存凭证和权限快照（`memory_save` 走 ASK 确认，模型没有理由主动把凭证类信息写入记忆，且没有自动
  flush 兜底扫描对话）；
- 记忆不会跨用户泄漏（`IsolationScope.USER` 按 `userId` 隔离存储命名空间）；
- 记忆冲突时实时工具结果优先（本阶段未接入任何"记忆内容注入上下文"的机制——`disableWorkspaceContext()`
  仍然禁用了 AGENTS.md/记忆的自动材料化，记忆只在 LLM 主动调用 `memory_search`/`memory_get` 时才会出现
  在对话里，不会静默覆盖实时工具结果）。

### 阶段 11：观测、用量、审计和评估

**目标**

- 能解释运行延迟、成本、决策路径和失败原因；
- 建立模型、提示词和工具变更门禁。

**框架使用**

- `OtelTracingMiddleware`；
- `MiddlewareBase.onModelCall/onActing/onAgent`；
- `ModelCallEndEvent` / `ChatUsage`。

**自建设计**

- usage 聚合（已完成，见 §11.3/§12.3：`UsageTrackingMiddleware` 按 `runId` 累加到 `agent_run`）；
- 写操作审计（已完成，见 §11.4/§12.7：`ToolAuditMiddleware` + `agent_tool_call`，含客户端真实执行结果回填）；
- 离线评估集 + 工具选择/权限越权/幻觉/答案质量指标（窄范围，已完成，见下文）；
- 成本指标（已随 usage 聚合具备，token 用量已按 `runId` 落库，本阶段未额外扩展）；
- prompt/model/tool catalog 版本关联（未做正式版本注册表，见下文"已知边界"）。

**离线评估集：真实模型 vs 桩模型的取舍（已完成）**

阶段 9/10 已有的 HITL/权限回归测试（`FlattenedWriteToolSuspensionEndToEndTest`、
`AgentPermissionRulesConfigurationTest` 等）全部用桩 HTTP 服务器返回脚本化的固定模型响应——这类测试
验证的是"框架管线接不接得住给定的响应"，测的是代码回归，不是模型质量本身：响应是写死的，模型选没选对
工具、会不会越权，桩模型模式根本观察不到，因为决策权从一开始就不在模型手里。

`ClientToolEvalSuite`（`biz-agent/src/test/kotlin/.../evaluation/`）反过来，调用**真实模型网关**跑一遍
BKArtifacts 下载客户端场景，真正评估工具选择是否正确、破坏性操作是否触发 ASK 确认、有没有在没拿到真实
`taskId` 时凭空编造、会不会把无关请求也当成任务来做。真实模型调用意味着需要网络、真实凭据、有成本，且
存在非确定性，因此这套用例**不作为每次 PR 必须跑通过的自动化 CI 门禁**——公开的 GitHub Actions
`backend.yml` 也没有配置内部模型网关凭据。用 JUnit5 的
`@EnabledIfEnvironmentVariable(named = "AGENT_EVAL_LLM_BASE_URL", ...)` 让整个类在没有配置真实网关时被
直接跳过（`SKIPPED`，不是失败），接入常规 `./gradlew test`/CI 零风险；需要真正跑一遍时（改了系统提示词、
换了模型、调了工具目录），在能访问内部模型网关的环境里配置：

```
AGENT_EVAL_LLM_BASE_URL=https://xxx/v1
AGENT_EVAL_LLM_MODEL_NAME=xxx
# 二选一鉴权方式
AGENT_EVAL_LLM_API_KEY=xxx
# 或
AGENT_EVAL_LLM_BK_APP_CODE=xxx
AGENT_EVAL_LLM_BK_APP_SECRET=xxx
```

再执行 `./gradlew :agent:biz-agent:test --tests "com.tencent.bkrepo.agent.evaluation.*"`。这是一个**人工在
改动前后各跑一遍、对比结果**的流程性门禁，不是自动化门禁；每个用例作为独立的 `DynamicTest` 出现在标准
JUnit 报告里，不需要额外的报告生成器，失败信息里会附带实际调用的工具与最终回复文本方便判断是措辞误报
还是真实退化。

**评估断言的观察边界**：本地客户端工具（`ExternalLocalTool`）真正执行前必定挂起（`callAsync` 恒抛
`ToolSuspendException`，交给客户端本地执行），所以一次 `HarnessAgent.streamEvents(...)` 调用天然只能
推进到"模型决定调用某个工具"或"直接给出文本回复"为止，观察不到"客户端把结果传回来之后模型会怎么做"。
首批用例（`ClientToolEvalCases`，9 个）因此只覆盖用户第一句话触发的"首次决策"：

- 工具选择正确性：查失败任务要调 `list_download_tasks(state=failed)`，查空间要调 `get_disk_space`；
- 破坏性操作必须触发 ASK：`clear_completed_records`/`run_disk_cleanup`/`set_download_path`；
- 防止编造：没给 `taskId` 时要求先 `list_download_tasks` 拿真实 ID，不能直接尝试 `delete_download_tasks`；
- 防止跳过诊断直接下重手：模糊的"卡住了"描述不应直接触发 `restart_download_engine`/`delete_download_tasks`；
- 越界请求应拒绝而非照做：与下载客户端无关的请求不应调用任何工具；
- 不应误委派：客户端本地问题不应触发 `agent_spawn` 委派给制品库领域子 Agent。

覆盖 discovery/transfer 领域工具、多轮工具结果之后的后续决策（例如反幻觉场景"列表返回空后模型是否如实
说明找不到"）需要额外的假实现或完整 AG-UI resume 协议驱动，成本明显更高，留给后续按需补充，属于刻意
接受的窄范围。真实模型存在非确定性，个别用例偶发失败是预期内的，出现失败应先看断言信息里的实际转录，
判断是措辞误报还是真实退化，而不是不加分析地当成构建失败处理。

**已知边界**：没有做 prompt/model/tool catalog 的正式版本注册表——`ClientToolEvalSuite` 只在跑之前把
`baseUrl`/`modelName`/鉴权方式打到日志里，供人工核对"这次评估用的是哪个模型"，没有把评估结果落库或做
历史趋势对比。这套评估集本身也不覆盖"在线持续评测"（对生产真实流量抽样打分）——按窄范围决策，在线评测
留给后续单独讨论。

**验收**

- 可追踪主 Agent 到子 Agent 再到工具（已完成，见阶段 11.4 审计）；
- 可统计每种 Agent 的成本和成功率（已完成，见 usage 聚合）；
- 权限回归用例全部通过（已完成：阶段 9/10 的桩模型 HITL/权限回归测试持续接入常规 `./gradlew test`）；
- 模型或提示词变更前必须人工跑一遍离线评估集并比对结果（已完成：`ClientToolEvalSuite`，流程性门禁而非
  自动化门禁，见上文）。

### 阶段 12：灰度、容量与生产发布

**目标**

- 从内部只读场景安全扩展到写操作；
- 完成容量和故障演练。

**框架使用**

- model fallback（已完成，见下文"模型调用预算与备用模型"；框架原生只支持单级 fallback）；
- graceful shutdown middleware（已完成，见下文"优雅停机"；框架的 `GracefulShutdownMiddleware` 由
  `ReActAgent` 无条件装载，本项目要做的是给它配上有限超时并补齐框架不知道的资源清理）；
- state recovery（框架侧已具备：停机打断会把 AgentState 落库并标记 `shutdownInterrupted`，用户重发时
  自动变成"继续"语义；配合上面的 `require-redis` 保证状态落在 Redis 而不是进程内）。

**自建设计**

- 按用户、项目和 Agent 灰度（未做，当前只有全局开关，没有按维度分流的能力）；
- 只读模式开关（已完成，见下文）；
- Redis 依赖的生产强制校验（已完成，见下文；原计划外补充，属于"滚动发布不丢状态"的前置条件）；
- 优雅停机与发布安全（已完成，见下文；同样是原计划外补充）；
- 单用户和全局模型并发限制（未做；阶段 9 已有委派并发的硬限制与软提醒，但没有覆盖模型调用本身）；
- 模型调用超时/重试预算与备用模型（已完成，见下文）；
- 会话锁续期看门狗（已完成，见下文；原计划外补充，属于"长对话不丢锁"的正确性前置条件）；
- 连续失败熔断（未做；框架无 CircuitBreaker，重试预算耗尽只影响单次调用，模型整体不可用时每个请求
  都会各自把预算走完）；
- 数据保留与删除策略（已完成，见下文"数据保留与清理"）；
- 演练模型不可用、Redis 故障、Mongo 延迟、IAM 超时和任务重复投递（未做）。

**只读模式开关（已完成）**

配置项 `agent.runtime.features.read-only-mode`，默认 `false`。开启后 Agent 的一切写能力立即不可用，
用途是生产应急阀：客户端行为异常、模型出现明显退化、或者刚上线想先只放开查询时，改一个配置重启即可，
不需要回滚版本或摘流量。

生效方式是两层，各有不可替代的作用：

1. **不注册**（`FrontendToolRegistrar`）：客户端写工具（`set_download_path`/`delete_download_tasks`/
   `run_disk_cleanup` 等，按 `LocalToolDefinition.riskLevel` 判定）压根不挂到 toolkit 上，模型看不到。
   这一层的价值是回答自洽——如果只在权限层拦，模型会先承诺"我去帮你改配置"再被拒掉，既浪费一轮调用，
   用户也会看到自相矛盾的过程。
2. **DENY 兜底**（`AgentPermissionRulesConfiguration`）：同一批工具在 PermissionEngine 规则表里由 ASK
   收紧为 DENY。这一层不是冗余：长期记忆的 `memory_save`/`memory_delete` 是框架 `filesystem(spec)` 一次性
   带进来的，没法像客户端工具那样只摘掉写的那几个，只能靠规则表拦；同时它也防住"别处又把写工具挂回
   toolkit"这类改动。

注意只读模式下写工具是 **DENY 而不是 ASK**——不留"用户点确认就能执行"的口子。只读模式的语义是"这个副本
此刻绝对不写"，如果还能被确认放行，它就退化成了"多问一句"，起不到应急阀的作用。

另外追加 `AgentSystemPrompts.READ_ONLY_MODE` 提示词片段（拼在 `ClientAgentPrompt.DEFAULT` 之后，因为后者
仍在描述写工具的用法，需要靠它纠偏）：告诉模型当前处于只读模式、遇到写请求直接说明并给出替代排查建议。
工具已经物理不可见，补这段话纯粹是为了让**回答**别含糊——否则模型只会发现"没有对应工具"，可能反复尝试
别的工具或说得模棱两可。

**边界（刻意的）**：这个开关只约束"模型能做什么"，不冻结用户自己发起的操作。用户直连的记忆管理接口
（`UserAgentMemoryResource` 的删除/清空）与会话删除仍然可用——用户对自己的数据始终有完全控制权，这个
开关防的是 Agent 误操作。另外它是配置项，改动需要重启（或配置中心推送后重建 bean），不是运行时热开关；
真正的秒级热切换需要把开关下沉到每次工具调用时读取，属于更大的改造，窄范围内先不做。

**Redis 依赖的生产强制校验（已完成）**

Agent 有六处存储各自 `ObjectProvider.getIfAvailable()`，拿不到 Redis 就退回进程内实现并只打一行 warn：
Agent 运行态（`AgentStateStore`）、会话归属、挂起中断、恢复幂等、活跃运行、后台任务，加上长期记忆
（无 Redis 时整体关闭）。本地开发需要这种退化，生产不需要，而且危险：单副本能跑通、多副本才暴露问题
（会话飘到另一个副本就丢上下文、确认卡片恢复不了、后台任务查不到），这类故障往往发布很久之后才被用户
投诉出来。

`AgentRedisRequirementValidator` 在启动期集中校验一次：`agent.runtime.state.require-redis=true`（生产应
配置）时，缺原生 Lettuce 客户端或缺 `RedisOperation` 就直接抛异常让 Spring 上下文刷新失败，异常信息里带
上缺的是哪一层、哪些能力会退化、以及想显式接受退化该把哪个配置置为 `false`。默认 `false` 保持本地开发
体验不变，只打告警。

两个设计取舍值得记下来：

- **集中校验而不是散在各 Configuration 里**：各存储的 bean 可能先于校验器构造完成，那几行退化 warn 照旧
  会打，但只要上下文最终起不来就不会有流量进来，fail-fast 的目的已经达到，代价是不用在六处重复同一段
  判断逻辑。
- **只校验"客户端有没有被装配出来"，不做连通性探测（ping）**：这里防的是配置漏配。连通性是另一类问题
  （网络抖动、实例故障），用启动探测去卡会把瞬时抖动放大成"起不来"，交给健康检查与告警更合适。

**优雅停机与发布安全（已完成）**

不做这件事的后果非常具体：会话锁（`ActiveRunStateStore`）只在 run 自己的收尾逻辑里释放，进程被杀时那段
逻辑随进程一起消失，锁只能等 `agent.runtime.active-run-ttl`（默认 11 分钟）自然过期。也就是说**每次滚动
发布，正在对话的用户重发消息都会撞上"上一次运行还在进行中"，最长要等 11 分钟**——用户视角是"这个助手
坏了"，不是"服务在发布"。

第二个坑在框架默认值上。`GracefulShutdownConfig.DEFAULT` 的 `shutdownTimeout` 是 `null`（无限）：
`AgentScopeJvmShutdownHook` 收到 SIGTERM 后会 `awaitTermination(null)` 一直等所有在跑调用结束，而
`GracefulShutdownManager` 的强制中断分支又只在配了有限超时时才会触发（`if (timeout != null)`）。两者叠加
的结果是：**一次卡住的模型调用就能让进程永远不退**，最后被 SIGKILL 硬杀，比有序退出更糟。

实现分三层，对应三个不同的责任人：

1. **`AgentRunShutdownHandler`（自建，挂在 `ContextClosedEvent`）**：先调
   `GracefulShutdownManager.performGracefulShutdown()` 让管理器进入 SHUTTING_DOWN——新的 Agent 调用直接抛
   `AgentShuttingDownException`（K8s 摘流量是异步的，这段窗口里仍会有请求打进来）；再调
   `ActiveRunManager.abortLocalRuns(SERVER_SHUTDOWN)` 中止本副本在跑的 run。顺序不能反：先中止后拒绝的
   话，刚腾出来的会话可能又被新请求占上。挂 `ContextClosedEvent` 而不是 `@PreDestroy`，是因为 Spring 关闭
   时先发这个事件、再走 Lifecycle 停止（Web 容器排空）、最后才销毁 bean——选最早的点，既能在 HTTP 排空
   之前就 complete 掉 SSE emitter（排空不用干等），也确保释放锁时 Redis 相关 bean 还完全可用。
   `abortLocalRuns` 只处理本进程的 handle，不去扫 Redis：其它副本的 run 由它们各自停机时处理，跨副本代劳
   会把还在正常服务的 run 一起杀掉。
2. **`AgentGracefulShutdownConfiguration`（自建）**：把框架单例纳入容器，并把
   `agent.runtime.shutdown-timeout`（默认 15s）配成有限值，同时保留框架默认的
   `PartialReasoningPolicy.SAVE`——被打断的调用会落 AgentState 并标记 `shutdownInterrupted`，用户重发时
   框架的 `GracefulShutdownMiddleware` 把重复的用户输入换成"继续"语义，从断点往下走而不是从头重跑。
3. **Spring Boot 层（配置）**：`server.shutdown: graceful` +
   `spring.lifecycle.timeout-per-shutdown-phase: 5s`，排空在途 HTTP 请求再停 Web 容器。

中止原因用 `AgentRunAbortReason` 枚举替代了原来硬编码的 `"user_stop"` 字符串：同样是 CANCELLED 终态，
用户主动停止是正常操作，`server_shutdown` 则说明这次对话是被发布/扩缩容打断的，排障和统计口径不一样。

**超时预算（默认值下的最坏情况）**：ContextClosed 中止（不等待，可忽略）→ HTTP 排空 ≤5s → bean 销毁
（`HarnessAgent` 实现 AutoCloseable，Spring 推断 `close()` 并调用，框架顺带停掉后台任务仓库的
heartbeat/孤儿清扫线程）→ JVM 钩子等待 ≤ 15s + 框架硬编码的 5s `INTERRUPT_GRACE_PERIOD` = 20s。合计约
25s，留在 K8s 默认 `terminationGracePeriodSeconds=30` 之内。**调大 `shutdown-timeout` 必须同步放大
terminationGracePeriodSeconds**，否则等于白配：进程还在等，Pod 已经被 SIGKILL。

停机窗口内打进来的新 run 请求由 `AgentRunOrchestrator` 前置拦成 **HTTP 503**（`ErrorCodeException` +
`SERVICE_UNAVAILABLE`，文案复用 `system.error` 的"系统繁忙，请稍后再试"），客户端重试即可落到别的副本。
拦在最前面而不是等框架抛异常，是因为框架的 `AgentShuttingDownException` 发生在订阅事件流之后——那时
HTTP 状态码早已发出，客户端只会拿到一个语义模糊的失败流，而且这一趟已经白建了 run 记录、白抢了会话锁。
前置判断与订阅之间仍有竞态（判断通过之后才开始停机），那种情况按普通 run 失败收尾。断线重连接口
（`streamRun`）**不拦**：那正是用户查看被打断的 run 结局的通道。

**已知边界**：SSE 客户端收到的是流被 complete（与用户主动停止同构），需要自行查 run 状态才能知道是
`server_shutdown`，没有专门推一个"服务重启中"的终态事件。真正的滚动发布/故障演练也仍未做——上述
时序是按框架源码与 Spring 生命周期推导并用单测覆盖的，不等于已在集群里验证过。

**数据保留与清理（已完成）**

在此之前只有 `agent_run_event` 带 TTL，其余的运行数据都是**只写不删**：Mongo 侧的 `agent_run`、
`agent_tool_call`、`agent_message`、`agent_session` 没有任何过期机制；Redis 侧的后台任务记录与长期记忆
也没有 key TTL（`WorkspaceTaskRepository` 的孤儿清扫只处理僵死任务，不清历史）。阶段 11 的审计与用量能力
恰好是写入量最大的部分——每次工具调用一行 `agent_tool_call`、每次运行一行 `agent_run`，用得越多长得越快；
记忆则是每个用户一份 `MEMORY.md` 永久驻留。

保留期统一收敛到 `agent.runtime.retention`，任一项配 `0` 表示永不过期：

| 配置项 | 默认 | 语义 | 取这个值的理由 |
| --- | --- | --- | --- |
| `run-event` | 7d | AG-UI 事件流 | 同时决定断线重连能回放多久以前的 run，7 天足够覆盖"昨天那条对话怎么回事" |
| `run` | 90d | run 元数据与用量统计 | 审计/用量口径按季度看足够，且必须 ≥ `run-event` |
| `tool-call` | 90d | 工具调用审计 | 与 `run` 对齐，否则查审计会出现"有 run 没有工具调用"的空洞 |
| `message` | 180d | 用户可见的聊天记录 | 产品侧的历史价值最高，给最长的窗口 |
| `session` | 180d | 会话元数据 | 必须 ≥ `message`，否则消息成为列不出来的孤儿数据 |
| `task` | 7d | 被 promote 出的后台子任务记录（Redis） | 任务本身活不过一次会话，保留期只影响事后能不能查到 |
| `memory` | 180d | 长期记忆（Redis） | 与聊天记录同档，且按"最后一次使用"滚动 |

**Mongo 侧：为什么是"写入时打戳"而不是把 TTL 索引直接建在业务时间字段上**

直接 `@Indexed(expireAfter = "90d")` 挂在 `startedAt` 上更省事——不用加字段，历史文档也立刻生效。但
Spring Data 的 `expireAfter` 只接受编译期常量，保留期就写死在代码里了，想按环境调整只能手工 `collMod` 改
索引，配置中心失去作用。所以沿用 `agent_run_event` 已有的做法：索引统一是 `expireAfter = "0s"` 挂在
`expiresAt` 上（到点即删），具体保留多久由写入时算好的时刻决定（`AgentRetentionPolicy`）。

这个选择有两个代价，都做了处理：

1. **改配置只影响之后新写入的文档**，已有文档保持写入当时算出的过期时刻，不会被追溯修正。这是可接受的：
   保留期是长周期参数，不需要立即对历史生效。
2. **历史上没有 `expiresAt` 字段的文档永远不会被清理**（TTL 只处理字段值是日期的文档）。由
   `AgentRetentionBackfill` 兜底：挂在 `ApplicationReadyEvent` 上，把缺字段的文档统一补成"从现在起再放一个
   完整保留期"。它是幂等的（查询条件就是"缺这个字段"，第一次补完之后再启动即空转），查询走的正是 TTL
   索引（普通单字段索引会把缺失字段当 null 索引），多副本同时跑也只是重复同一个更新，不需要分布式锁。
   之所以不按各表自己的时间字段回推真实创建时间：那需要聚合管道更新、五张表的时间字段名还各不相同
   （`startedAt`/`calledAt`/`createdAt`/`updatedAt`），而且会让升级瞬间就删掉一批已超期的旧数据。
   **保留期配成 0 的集合会跳过补戳**——Spring Data 写入时会略掉值为 `null` 的字段，因此"配了永不过期的新
   文档"和"引入保留期之前的老文档"在库里长得一模一样，不跳过就会把用户要求永久保留的数据补上过期时刻。

`agent_session` 的 `expiresAt` 与其它四张表不同：它在每次 run 开始时随 `touchSession` 一起往后推，语义是
**最后活跃之后再放多久**，只要还在用就不会过期；`agent_message` 则按消息自己的创建时间算，语义是"只保留
最近这么久的聊天记录"。默认两者都是 180 天，因此一个闲置会话的元数据与它最后一条消息大致同时消失。

**Redis 侧：`LettuceStore` 的 key TTL**

框架的 `BaseStore` 接口没有过期概念，所以在自建的 `LettuceStore` 里统一处理：每次写入都把 item hash 与
命名空间索引（ZSET）一起 `PEXPIRE` 到"现在 + 保留期"，Lua 脚本里按 `ttl > 0` 判断，不设过期时传 0，
省掉维护两套脚本。两个实例语义不同：

- **后台任务**（`AgentTaskRepositoryConfiguration`）不开 `refreshTtlOnRead`——事后翻查一条旧任务不应该延长
  它的寿命；
- **长期记忆**（`AgentMemoryFilesystemConfiguration`）开 `refreshTtlOnRead`——`get`/`search` 也续期，于是
  保留期按"最后一次使用"算。否则用户半年前保存、之后一直在读的偏好会被静默清掉，这是最难解释的一类问题。

有一处残留的不一致是**刻意接受**的：命名空间索引整体续期，但索引成员没有各自的过期时间，因此一个仍在活跃
写入的命名空间里会留下少量指向已过期 hash 的陈旧成员。`search` 本来就会跳过缺失的记录（并发删除也会造成
同样的情况），成员只是几十字节的短字符串，量级是"每个有过后台任务的会话一条、每个用户的记忆文件各一条"；
反过来若要精确清理，就得把索引改成按过期时间打分的 ZSET，从而放弃 `ZRANGEBYLEX` 分页、偏离框架
`RedisStore` 的行为，代价明显更大。整个命名空间停止读写一个保留期之后，索引与 hash 会一起消失，不留残余。

**会话删除的口径**（顺带修正）：用户删除会话时硬删消息与 run 记录，此前 `agent_tool_call` 却留了下来——
run 都没了，这些审计行指向不存在的 run，既拼不出完整链路又占着地方。现在一并删除；真正需要长期留痕的是
网关侧 `@LogOperate` 的操作日志，不是这张表。`agent_run_event` 不随会话删除清理，靠 7 天 TTL 到期收口。

**已知边界**：Mongo 的 TTL 后台任务约每 60 秒扫一次，"到点"与"真的消失"之间有分钟级延迟，因此这套机制是
容量控制手段，不能当作精确到秒的合规删除承诺；用户要立即删除自己的数据，走的是会话删除与记忆删除接口。
另外保留期目前是全局配置，没有按项目/用户维度差异化的能力。

**模型调用预算与备用模型（已完成）**

这件事的起因是一处**默认值撞车**，不是新功能需求。框架的 `ExecutionConfig.MODEL_DEFAULTS` 是每次尝试超时
5 分钟、最多 3 次尝试（`OpenAIChatModel` 在 `build()` 时通过 `ensureDefaultExecutionConfig` 自动补上，
所以项目一行没配也一直在吃这套值）；超时是**每次尝试**各自计时（`ModelUtils.applyTimeoutAndRetry` 先
`.timeout()` 再 `.retryWhen()`），最坏一次模型调用就能占 15 分钟。而会话锁是一把 TTL 固定的 Redis 锁
（`RedisActiveRunStateStore`，`agent.runtime.active-run-ttl` 默认 11 分钟），**没有续期看门狗**。

叠起来的后果不是"慢"，是**正确性**：锁在 run 还活着的时候就过期，同一会话上可以并发起第二个 run，两个 run
交替写同一份 AgentState。这类问题只在长尾请求上出现，靠上线后观察是发现不了的。

因此把模型调用的时间预算显式收敛到 `agent.llm`，默认每次尝试 90 秒、含首次共 2 次尝试，最坏
`2 × 90s + 3s` 退避 ≈ 3 分钟；配了备用模型时翻倍（框架把同一份 `ExecutionConfig` 传给备用模型，它有自己
完整的重试预算），约 6 分钟，仍在 11 分钟锁 TTL 之内。重试范围沿用框架的 `RETRYABLE_ERRORS`：超时、IO、
429、5xx 重试，4xx 与鉴权失败立即失败——重试一个参数错误只是把同样的错误延后几秒再说一遍。

`AgentModelBudgetValidator` 在启动期校验这层自洽性，两条检查的性质刻意不同：

- **预算 ≥ 锁 TTL → 直接启动失败**，异常信息里带上算出的预算、锁 TTL，以及可调的三个配置项名。
- **单次超时 > 停机等待窗口 → 只告警**。停机时硬中断在途模型调用是可接受的（框架会落 AgentState 并标记
  `shutdownInterrupted`，用户重发即从断点继续），做成致命错误反而会逼着把停机窗口配成分钟级，拖慢每次发布。

备用模型（`agent.llm.fallback-model-name`）复用同一个网关地址与鉴权，只换模型名，走框架原生的
`fallbackModel(...)`：主模型首个信号是错误时整条流切到备用模型。它**不注册成 Spring bean**——容器里出现
第二个 `Model` 会让现有 `model: Model` 注入点变歧义，而它除了喂给 `HarnessAgent.Builder` 之外没有别的消费方，
所以和 `ExecutionConfig` 一起打包成 `AgentModelResilience` 传递。跨网关容灾需要另一套凭据，不在这里解决。

`AgentModelBudgetValidator` 在启动期把这几个数字算到一起记进日志（预算、锁 TTL、续期间隔、停机窗口），
预算不短于锁 TTL 时补一条告警。它**只告警不阻断启动**：兜住正确性的是下面的续期看门狗，预算超 TTL 只在
"看门狗自己被拖住"时才有意义（那时 TTL 是最后的宽限期，单次调用越长裸奔窗口越大）。刻意**不**检查"单次超时
是否长于停机窗口"——默认值 90s vs 15s 本来就是超的，停机硬中断在途调用是选定并接受的行为，对着刻意选的
默认值每次启动报告警只会训练所有人忽略日志。

**已知边界**：预算算的是**单次模型调用**，不是整个 run。框架只有单级 fallback、没有熔断：模型整体不可用时，
每个请求都会各自把重试预算走完，靠 fallback 兜住而不是快速失败。

**会话锁续期看门狗（已完成）**

上一节压缩单次模型调用的超时预算，只能保证单次调用不越界，管不住**累计**时长。会话锁
（`RedisActiveRunStateStore`）是一把 TTL 固定的 Redis 锁，`RedisLock.tryLock()` 就是一次 `SET NX EX`，
之后没有任何续期；而一个 run 有多轮推理（`maxIters`）与工具调用，总时长取决于模型和工具，不受我们控制。

也就是说**"run 的时长上限"其实被锁 TTL 悄悄限定了**，而且超限的表现不是报错，是锁自己消失：同一会话上
可以并发起第二个 run，两个 run 交替写同一份 AgentState。这是本阶段唯一一处"错的不响"的问题。

`ActiveRunLockHeartbeat` 用一个单线程调度器周期性调用 `ActiveRunManager.renewLocalRunLocks()`，给本副本
在跑的每个 run 续上锁与活跃 run 绑定。几个决定：

- **续期间隔由锁 TTL 推导**（TTL 的三分之一，下限 5 秒），不新开配置项。这两个值必须联动，暴露成两个独立
  配置只会制造"间隔比 TTL 还长"这类无意义的错配。取三分之一是为了容忍连续两次续期失败仍不丢锁。
- **用自己的调度器而不是 `@Scheduled`**：本模块没开 `@EnableScheduling`，而这件事必须一直跑、不该受别处
  调度配置影响。调度器一旦抛出异常就会静默停掉后续所有执行，所以每轮兜住 `Throwable`——包括 Error，否则
  一次偶发 OOM 之后看门狗永久失效且毫无迹象。
- **归属凭证用活跃 run 绑定，不是锁的 token**：`RedisLock` 的随机 token 是私有的，拿不到也就没法做
  compare-and-expire。而活跃 run 绑定（`activeRunKey` 里存的 runId）是本项目自己写进去的，与锁同生共死
  （一起设、一起清），用它判断"这把锁还是不是这个 run 的"信息量等价。先判断再 `EXPIRE` 不是原子的，但两个
  key 一起续、窗口是毫秒级；对比之下不续期的话锁在分钟级之后必然过期，取舍很清楚。
- **发现归属已经不在时，中止本地这个 run**（`AgentRunAbortReason.LOCK_LOST`）。此时会话已被别人接管，继续
  跑只会污染状态。中止走与用户停止相同的收尾路径，其中 `releaseRun` 会先比对活跃 run 绑定，因此不会误删
  接管方的锁。相反，**续期抛异常时不中止**——那大多是 Redis 抖动，下一轮还会再试，不该因此杀掉正在跑的对话。

**已知边界**：看门狗只续本副本 `localHandles` 里的 run，与停机中止、跨副本停止广播的责任划分一致。锁 TTL
仍然是副本非正常死亡（SIGKILL、OOM）时的兜底回收时间，续期不改变这一点——它解决的是"活着却丢锁"，不是
"死了不释放"。

**验收**

- 可一键关闭写工具（已完成：`agent.runtime.features.read-only-mode`，见上文；仍需重启生效，非运行时热开关）；
- 可退化到单 Agent 只读模式（部分完成：只读模式已有；"退化到单 Agent"可通过
  `agent.runtime.topology.coordinator.enabled=false` 关闭子 Agent 达成，但两者尚未合并为一个开关，也未演练）；
- 滚动发布不丢状态（已完成代码侧：`require-redis` 保证状态落在 Redis，优雅停机保证锁被释放、被打断的
  调用可从断点继续，停机窗口内的新请求返回 503 供客户端重试；集群内的滚动发布演练仍未做）；
- 存储容量可控（已完成：五张 Mongo 表与 Redis 侧任务/记忆都有可配置保留期，见上文"数据保留与清理"）；
- 达到明确 SLO、成本和安全门槛（未做：SLO 与门槛尚未定义）。

## 14. 关键方案取舍

### 14.1 层级式多 Agent，而不是固定图

制品库问题通常需要根据前一步观察决定下一步。例如先确认仓库和路径，再决定查权限、传输还是存储。固定图会提前运行无关探针，增加成本和噪音。

因此采用：

- Coordinator 动态委派；
- 有依赖任务递进执行；
- 独立任务才并行；
- 涉及事务的流程交给确定性应用状态机。

### 14.2 不是所有领域都做成 Agent

Agent 适合需要语言理解、证据综合和不确定性推理的任务。简单 CRUD、权限校验和格式转换保持为普通服务或工具。Agent 数量由认知边界决定，不由微服务数量决定。

### 14.3 Operations 独立

读和写的风险、提示词、工具和评估标准不同。独立 Operations Agent 能实现：

- 主 Agent 没有写工具；
- 写工具集中 allowlist；
- 更低温度和更严格输出；
- 独立灰度和一键关闭；
- 更强审计和回归。

但安全边界最终仍在工具网关和 IAM，不在 Agent 名称上。

### 14.4 会话归档不使用 AgentStateStore

`AgentStateStore` 服务于运行恢复，会被压缩并适合过期。用户历史需要排序、分页、长期保存和合规删除。二者生命周期和查询方式不同，必须分开。

### 14.5 第一期不做长期记忆

身份、权限、工具和会话是正确性的基础。长期记忆引入隐私、过期、纠错和跨租户风险，且对制品库实时状态价值有限。应在有真实需求和治理能力后启用。

## 15. 第一版推荐范围

第一版按递进路径交付：

1. Coordinator + Discovery Agent；
2. 加入 Transfer Diagnostics Agent；
3. 完成会话、状态和上下文压缩；
4. 加入 Governance Agent；
5. 完成权限和 HITL 后再加入 Operations Agent；
6. 最后加入 Knowledge Agent、后台任务和长期记忆。

第一版工具仅覆盖：

- 项目/仓库列表；
- 仓库详情；
- 包/版本/制品查询；
- 制品元数据；
- 用户对指定资源的权限解释；
- 传输状态和错误信息读取。

写操作在只读闭环、评估和审计稳定前保持关闭。

## 16. 完成定义

一个可上线的制品库多 Agent 后台至少满足：

- 所有 Agent 有明确职责、模型和工具 allowlist；
- 身份来自受信任认证链并通过 RuntimeContext 传递；
- 每个资源工具都按真实用户走原 IAM；
- 写操作有 ASK、二次鉴权、幂等和审计；
- 主 Agent 按依赖递进委派，不无条件并行；
- AgentState、会话归档、知识和长期记忆相互分离；
- 多副本下同会话互斥、任务可恢复；
- 对外运行请求和事件符合 AG-UI，客户端不依赖 AgentScope 内部事件；
- 主 Agent、子 Agent、模型和工具全链路可观测；
- 有固定评估集验证工具选择、答案质量和越权风险；
- 可以一键关闭写能力并降级为只读模式。

满足以上条件后，多 Agent 才是一个可治理的后台系统，而不是多个模型调用的集合。

## 17. 现有实现的 AG-UI 对齐改造计划

本节只针对调查中发现的现状差距，按依赖顺序迁移。迁移期间允许短期 feature flag，不长期维护两套 wire protocol。

### 17.1 基线升级

**改造**

1. 将所有 AgentScope 模块从 2.0.0 统一升级到 2.0.1；
2. 在统一依赖管理中加入 `agentscope-extensions-agui` 和 `agentscope-agui-spring-boot-starter`；
3. 显式固定 Middleware 顺序，覆盖消息归档、Compaction 和 ToolResultEviction；
4. AgentScope 2.0.1 将 Toolkit 默认执行策略改为并行，必须显式配置为符合任务依赖的执行方式，不能无条件并行探测。

**验收**

- `biz-agent` 编译和已有 smoke test 通过；
- 会话恢复、消息压缩、外部工具挂起和 Permission ASK 回归通过；
- 同一有依赖诊断场景不会提前执行后续探针。

### 17.2 后台 AG-UI 边界

**改造**

1. 运行入口改为官方 `RunAgentInput`，SSE 输出官方 `AguiEvent`；
2. 使用 `AguiRequestProcessor` 和 `AguiAgentAdapter`，删除手写 `AgentEvent` SSE 透传；
3. 使用 `AguiRuntimeContextResolver` 注入受信任的 userId、projectId；`deviceId` / `traceId` 经 `RunAgentInput.forwardedProps` 白名单提取；
4. 保留 session create/list/latest/messages/delete 业务 API，但 create 返回真实标题和服务端时间；
5. runId 使用客户端 AG-UI runId；需要内部跟踪时新增 executionId。

**删除**

- `AgentRunRequest.content` 快捷协议；
- `AgentExternalExecutionResult`；
- `REQUIRE_EXTERNAL_EXECUTION` 对外事件；
- 后台自生成且不返回客户端的第二套 runId；
- 直接序列化 `AgentEvent` 的 `emitAgentEvent()`。

**验收**

- 可用标准 AG-UI 客户端直接发起 run；
- 所有 SSE 事件均属于 AG-UI 事件集合；
- 请求体或 context 伪造身份不影响 RuntimeContext；
- threadId、runId、messageId 可贯穿日志、Mongo 和 trace。

### 17.3 消息与幂等

**改造**

1. USER 消息以 `messages[].id` 作为 canonical messageId；
2. 助手消息以 `TEXT_MESSAGE_START.messageId` 聚合 delta 并归档；
3. 持久化结构化 MessageContent 和 textContent 投影；
4. 增加唯一索引 `(threadId, messageId)` 与唯一 runId；
5. 明确请求重试策略：已完成则返回/回放结果，运行中返回状态，不能重复执行。

**验收**

- UI 乐观消息与历史消息使用同一个 ID；
- SSE 断开重试不产生重复 USER/ASSISTANT 消息；
- 标题只由首条有效 USER 消息生成一次；
- 压缩 AgentState 后 Mongo 原始消息仍完整。

### 17.4 客户端切换到官方 AG-UI

**改造**

1. 引入 `@ag-ui/client`；
2. 由官方 client 维护 thread、run、messages 和事件状态；
3. 使用 `crypto.randomUUID()` 生成 runId/messageId，停止使用短 `Math.random()` ID；
4. 删除手写 fetch SSE parser、`eventMapper` 候选字段兼容和自定义 run while 循环；
5. 保留 Pinia 作为 UI 投影，不再让它定义 wire protocol。

**验收**

- DevTools 中请求体为标准 `RunAgentInput`；
- UI 可正确聚合 messageId、toolCallId 和终态；
- `RUN_ERROR` 不被误判为正常完成；
- 打开助手恢复 latest thread，无历史时创建并绑定新 thread。

### 17.5 Frontend tool 与 HITL

**改造**

1. Electron 本地工具通过 `RunAgentInput.tools[]` 暴露；
2. 服务端对工具定义做 allowlist、schema 和风险校验，禁止客户端扩大工具能力；
3. `RUN_FINISHED.outcome.interrupts[]` 驱动确认或本地执行；
4. 客户端执行 IPC 后以相同 threadId、新 runId、`resume[]` 恢复；
5. ASK 写工具确认后、真正执行前重新 IAM 鉴权并检查幂等。

**验收**

- 只读本地工具完成“调用—中断—执行—resume—最终回答”；
- 写工具覆盖同意、拒绝、编辑参数、超时和权限撤销；
- resume 缺失、重复或引用错误 interruptId 时拒绝；
- 客户端篡改工具 schema、toolName 或参数不能越权。

### 17.6 运行保障与清理

**改造**

1. status、stop、reconnect 和事件回放作为 AG-UI 之外的运行保障 API，不改变 `RunAgentInput` 或 `AguiEvent`；
2. 运行锁继续按 userId + threadId，活跃记录保存 canonical runId（Redis `active-run` + Mongo `agent_run`）；
3. 完成灰度后删除旧协议、旧类型和兼容分支；
4. 更新接口文档、契约测试和端到端测试。

**已实现 API**

约定：`projectId` 一律走 query；对外与 Mongo 字段统一使用 AG-UI `threadId`；`deviceId` / `traceId` 经 `RunAgentInput.forwardedProps` 传递。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/agent/session/create?projectId=` | 创建会话，返回 `{ threadId, title, createdAt }` |
| GET | `/api/agent/session/list?projectId=&pageNumber=&pageSize=` | 会话列表（含 `threadId`） |
| GET | `/api/agent/session/messages?projectId=&threadId=&pageNumber=&pageSize=` | 消息历史 |
| POST | `/api/agent/session/update?projectId=` | 更新标题（body: `{ threadId, title }`） |
| POST | `/api/agent/session/delete?projectId=` | 删除会话（body: `{ threadId }`） |
| POST | `/api/agent/run?projectId=` | AG-UI run SSE（body: 标准 `RunAgentInput`） |
| GET | `/api/agent/run/status?projectId=&threadId=` | 查询 run 状态 |
| POST | `/api/agent/run/stop?projectId=` | 停止 active run（body: `{ threadId, runId? }`） |

**客户端接线（bk-artifacts-ui，`agent-backend`）**

| 能力 | 模块 | 说明 |
| --- | --- | --- |
| stop | `agentRunLoop` → `stopBackendRun` | 暂停按钮 abort SSE 并 POST `/run/stop` |
| status | `backendClient.fetchBackendRunStatus` | 打开会话前查询 thread 活跃 run |
| reconnect | `backendClient.reconnectBackendRun` + `aguiSseStream` | 解析终态 SSE，提取 pending interrupt |
| 恢复策略 | `agentRunRecovery.planThreadRunRecovery` | `wait_running` 轮询后刷新历史；`resume_interrupt` 调 `startAgentRecoveryTurn` |
| abort 误报 | `isBenignRunAbortError` | 用户主动 stop 时不展示 `BodyStreamBuffer was aborted` |

**验收**

- stop 能终止正确的 active run；
- reconnect 不重复消息且终态一致；
- 多副本切换后仍能查询运行状态；
- 真机完成至少一轮普通对话、本地工具、确认写操作和历史恢复后再推送。

**下一步（§17.6 验收）**

1. **stop**：流式输出中点暂停，后台 active run 终止，已输出内容保留、无 abort 误报；
2. **reconnect / 历史恢复**：本地工具或 HITL 中断后关闭小制 → 重开同一会话 → 自动 reconnect 并 resume；
3. **wait_running**：对话中途退出 → 重开同一会话 → 轮询 status 结束后刷新完整回复；
4. **多副本 status**：切换网关实例后 `GET run/status` 仍能返回一致状态；
5. ~~通过后删除旧协议兼容分支，更新契约测试与接口文档~~ **已完成**：`POST /run/reconnect`
   （`UserAgentChatResource`/`AgentChatService`/`AgentChatServiceImpl` 的 `reconnectRun`、
   `AgentRunReconnectRequest`、`LOG_OPERATE_RUN_RECONNECT`）已删除，客户端确认全量走
   `GET /run/stream`；1-4 仍需真机验收后再关闭本节。


