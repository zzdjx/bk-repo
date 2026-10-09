# AG-UI 多 Agent 运行时运维指南

本文档面向 Agent 模块（`boot-agent`）的部署、配置中心维护与故障处理。与 [重构方案](./agui-bk-ci-aligned-refactoring-plan.md) 配套。

> **本版相对旧版的最大变化：Agent 模块不再依赖 Redis。** 活跃 run 的运行权、停止信号、HITL 中断现场、
> resume 幂等、会话归属、AgentScope 会话状态，全部落在 MongoDB。旧版文档里的 `## Redis Key` 一节、
> `require-redis` / `state.key-prefix` 配置项、以及"清理 Redis 锁键"这类排障步骤**均已失效**，照做会
>指向一个根本不存在的组件。

## 1. 架构概览

```
客户端 (AG-UI)
    │  POST /run  (SSE)
    │  GET  /run/stream
    │  GET  /run/status · POST /run/stop
    ▼
AgentChatService
    ├─ AgentRunOrchestrator       — 准入（停机/灰度/入参/归属）与提交（抢运行权/启动）
    ├─ AgentRunStreamOrchestrator — 活跃 run 增量 / 终态回放
    ├─ AgentRunLifecycleManager   — run 收尾、中断本副本的 Agent 调用
    └─ ActiveRunManager           — 运行权租约、stop 标志、本副本 handle 注册表

Coordinator（bkrepo-assistant）
    ├─ client               — frontend SchemaOnlyTools（委派时可用）
    ├─ discovery            — 制品检索
    ├─ transfer-diagnostics — 传输诊断（默认关闭）
    └─ governance           — 权限判定解释 / 仓库治理配置查询（默认关闭）

后台常驻
    ├─ ActiveRunLockHeartbeat — 每 TTL/3 续本副本持有的运行权租约
    ├─ AgentOrphanRunSweeper  — 回收副本猝死遗留的 RUNNING run
    └─ AgentMemoryMaintenanceJob — 清理超期的 memory 日志文件

事实源
    MongoDB — 10 张集合，见 §5。没有第二个有状态依赖。
```

**关键原则**

- 身份与权限只信 HTTP 鉴权结果；`projectId` 只信 query 参数，不信 body。
- 所有状态单一事实源是 Mongo，所有过期都靠写入时打 `expiresAt` + TTL 索引，**没有任何外部清理脚本**。
- 写操作需用户确认；resume 有幂等指纹。
- 跨副本协调全部是"租约 + 轮询"，不是推送，所以每个动作都有一个有界的生效延迟，见 §4。

## 2. HTTP API

前缀：`/api/agent`。所有接口需登录；`projectId` 通过 query 传递，校验项目 `READ` 权限。

### 2.1 Run

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/run?projectId=` | 发起 AG-UI run，响应 `text/event-stream` |
| GET | `/run/status?projectId=&threadId=` | 查询 thread 当前/最新 run 状态 |
| POST | `/run/stop?projectId=` | 停止 active run（body: `threadId`, 可选 `runId`） |
| GET | `/run/stream?projectId=&threadId=&runId=&lastEventIndex=` | 衔接活跃 run 或重放终态事件 |

**`/run/stream` 行为**

- `runId` 省略：优先 thread 活跃 run，否则取最新终态 run。
- `lastEventIndex` 省略：从首条事件重放（`-1` 语义）。
- 终态 run：从 Mongo `agent_run_event` 全量/增量回放；无事件时走 synthetic 兼容路径。
- 活跃 run：Mongo 增量 + 本实例 replay sink；跨实例时轮询 Mongo。

**幂等 POST `/run`**

- 同一 `runId` 已终态：不重跑 Agent，直接回放事件。
- 同一 `runId` 仍 RUNNING：返回 429。

### 2.2 Session

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/session/create?projectId=` | 创建 thread |
| GET | `/session/list?projectId=` | 分页列表 |
| GET | `/session/messages?projectId=&threadId=` | 历史消息（Mongo 归档） |
| POST | `/session/update?projectId=` | 更新标题 |
| POST | `/session/delete?projectId=` | 删除会话及关联数据 |

### 2.3 Memory

用户对自己长期记忆的管理入口，不受 `read-only-mode` 影响（那个开关只收紧 Agent 自己的写能力）。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/memory/files?projectId=` | 列出记忆文件 |
| GET | `/memory/content?projectId=` | 读取单个记忆文件 |
| POST | `/memory/delete?projectId=` | 删除单个记忆文件 |
| POST | `/memory/clear?projectId=` | 清空全部记忆 |

### 2.4 审计日志类型

| 常量 | 场景 |
|------|------|
| `AGENT_RUN` | POST `/run` |
| `AGENT_RUN_STATUS` | GET `/run/status` |
| `AGENT_RUN_STOP` | POST `/run/stop` |
| `AGENT_RUN_STREAM` | GET `/run/stream` |
| `AGENT_SESSION_*` | 会话 CRUD |

## 3. 配置

前缀 `agent.*`。完整带注释模板见 `boot-agent/src/main/resources/application-agent.yml`，下表只列运维需要
决策的项与默认值。**未列出的旧 key 一律不再绑定**，见 §3.2。

### 3.1 配置项与默认值

```yaml
agent:
  llm:
    base-url:              # 必填（生产）
    api-key:               # API Key 模式
    bk-app-code:           # 蓝鲸网关模式
    bk-app-secret:
    model-name:            # 必填（生产）
    reasoning-effort:      # 可选：no_think | low | high
    stream: true
    # 韧性。request-timeout 是「每次尝试」的超时，不是整个 run 的
    request-timeout: 90s
    max-attempts: 2        # 含首次；只对超时/429/5xx/IO 重试
    initial-backoff: 2s
    max-backoff: 10s
    fallback-model-name:   # 非空时启用备用模型（同网关同鉴权，仅换模型名）
    circuit-breaker:
      enabled: true
      failure-threshold: 5 # 整条调用链（重试、fallback 都试过）连续失败达阈值即熔断
      cooldown: 30s
    concurrency:           # 进程内尽力而为限流，不跨副本；<=0 表示该维度不限制
      max-global: 0
      max-per-user: 0

  memory:
    context-window-size: 128000
    compaction-enabled: true
    trigger-messages: 0
    keep-messages: 0
    reserved: 20000
    flush-before-compact: false
    offload-before-compact: false
    tool-result-eviction-enabled: true

  runtime:
    name: bkrepo-assistant
    sys-prompt:            # 留空时使用代码内置 AgentSystemPrompts
    max-iters: 10
    workspace: /data/workspace/agent
    sse-timeout: 10m
    max-message-length: 32768
    max-thread-id-length: 128
    session-ttl: 30d
    active-run-ttl: 11m    # 运行权租约时长，见 §4
    reconnect-poll-interval: 500ms
    reconnect-timeout: 10m
    shutdown-timeout: 15s  # 必须是有限值，且与 K8s terminationGracePeriodSeconds 对齐
    state:
      ttl-days: 30         # AgentScope 会话状态在 agent_state 的保留天数
    retention:             # 见 §5.3
      run-event: 7d
      run: 90d
      tool-call: 90d
      message: 180d
      session: 180d
      task: 7d
      memory: 180d
      overrides: []        # 按 project-ids / user-ids 维度覆盖，OR 关系，命中第一条生效
    memory:
      daily-ledger-retention: 90d  # 0 或负值表示不清理
      maintenance-interval: 24h
    features:
      frontend-tools-enabled: true
      read-only-mode: false        # 生产应急阀，见下
    gray:
      enabled: false               # 灰度总闸，见下
      allowed-project-ids: []
      allowed-user-ids: []
    topology:
      coordinator:
        enabled: true
        max-delegations: 8
        max-parallel-delegations: 1
        task-list-enabled: true
      agents:
        client: { enabled: true, max-steps: 8 }
        discovery: { enabled: true, max-steps: 8 }
        transfer-diagnostics: { enabled: false, max-steps: 8 }
        governance: { enabled: false, max-steps: 8 }
```

**两个应急阀，都不需要重启**（判断点读的是 live 配置 bean，Consul 推送后即时生效；本地开发不接配置中心
时改 yml 仍需重启）：

- `features.read-only-mode: true` —— Agent 的写能力整体停用：客户端写工具不再注册给模型，记忆的
  `memory_save` / `memory_delete` 从 ASK 收紧为 DENY。用户自己的 `/memory/*` 接口不受影响。
- `gray.enabled: true` —— 灰度总闸，只回答"这个项目/用户能不能进"，不区分能用哪些子 Agent 或工具。
  项目名单与用户名单是 OR 关系，任一命中即放行。

### 3.2 配置中心迁移清单（旧 key 已不再绑定）

| 已删除（勿再使用） | 新 key |
|-------------------|--------|
| `agent.model.*` | `agent.llm.*` |
| `agent.compaction.*` | `agent.memory.*` |
| `agent.tool-result-eviction.*` | `agent.memory.tool-result-eviction-enabled` |
| `agent.model.context-window-size` | `agent.memory.context-window-size` |
| `agent.name` / `agent.workspace` / `agent.max-iters` 等 | `agent.runtime.*` |
| `agent.run-lock-ttl` | `agent.runtime.active-run-ttl` |
| `agent.local-tools-enabled` | `agent.runtime.features.frontend-tools-enabled` |
| `agent.runtime.run-event-ttl` | `agent.runtime.retention.run-event` |
| `agent.state.key-prefix` / `agent.runtime.state.key-prefix` | 已废弃；状态落 Mongo 不再有 key 前缀 |
| `agent.runtime.state.require-redis` | 已废弃；不再有 Redis 依赖可供降级判断 |
| `agent.session-ttl` | 已废弃；session 归属以 Mongo 为准 |

## 4. 活跃 run 的跨副本协调

这一节取代旧版的「Redis Key」。没有 Redis 之后，同一 thread 的互斥、停止信号、崩溃回收全部建立在
`agent_active_run` 这一个文档上，运维需要理解的是它的**租约模型**和由此带来的**三个有界延迟**。

### 4.1 运行权租约

`agent_active_run` 每行用 `scopeKey = {userId}:{threadId}` 唯一标识，承载三件事：谁持有这个会话的运行权、
当前绑定的 `runId`、以及是否被请求停止。旧版 Redis 里这是三个独立 key，合成一个文档不只是换存储——三者
从此只能一起被原子地改写，不会再出现"锁还在但活跃 runId 已经指向别人"这类中间态。

- `tryAcquire` 靠唯一索引做"插入即占位、撞键即失败"，等价于 Redis 的 `SET NX`。
- 文档带 `expiresAt` + TTL 索引，租约时长就是 `active-run-ttl`（默认 11m）。
- `ActiveRunLockHeartbeat` 每 **TTL/3** 续一次期。副本活着就一定续得上；副本没了，最多一个 TTL 之后这行
  就不再是"活的"，下一次 `tryAcquire` 直接覆盖它。

**`active-run-ttl` 必须大于 `sse-timeout`，并覆盖 reconnect 窗口**，否则长对话会在跑着的时候被判成孤儿。

### 4.2 停止信号是轮询，不是推送

`POST /run/stop` 只在文档上打一个停止标志。拥有该 run 的副本在 `AgentRunEventPipeline` 的事件边界上
**每 1s 最多查一次**这个标志，发现后强制收尾 assistant 消息并把 run 收成 `CANCELLED`。

运维含义：stop 的生效延迟上界约 1s **加上**当前这一步的剩余时间。如果 Agent 正卡在一次 90s 的模型调用上，
停止要等这次调用返回才会被观察到——这不是故障。同副本的请求还会额外走一次本地 handle 中断，更快。

还有一个顺序兜底：如果停止请求比 handle 注册先到，`registerHandle` 会立刻回查标志并自行中断，不会漏掉。

### 4.3 崩溃回收与 `OwnerReplicaLost`

副本猝死时，它持有的 run 在 Mongo 里还是 `RUNNING`，没人会去改。`AgentOrphanRunSweeper` 每
`active-run-ttl` 扫一轮：先按"启动超过一个 TTL"做便宜预筛，再逐条回查运行权文档是否仍绑定该 `runId`，
**两者都不满足才**收成 `FAILED` 并打上 `errorCode = OwnerReplicaLost`。

- **为什么是 FAILED 不是 CANCELLED**：CANCELLED 的语义是"有人主动停了它"。副本猝死没有人做过这个决定，
  混进 CANCELLED 会让"用户取消率"凭空升高，掩盖真正该被发现的副本异常退出。
- **每个副本都在扫，不加分布式锁**：收尾走的 `finishRun` 带 `status = RUNNING` 条件，并发时只有一个改得
  成功，其余返回 0 行当作已被处理。
- 清扫只改 run 记录，不碰运行权文档——那行已经过期，TTL 会回收。

**`OwnerReplicaLost` 的出现率是副本稳定性的直接指标**，见 §6。

## 5. MongoDB

### 5.1 集合（10 张）

其中 9 张由本模块的 `T*` 文档类声明，`agent_state` 由框架的 `MongoAgentStateStore` 管理（我们只配
`collectionName` 与 `ttlDays`，索引由它自己建）。

| 集合 | 用途 |
|------|------|
| `agent_session` | 会话元数据与归属（threadId 唯一） |
| `agent_message` | 用户/助手消息归档 |
| `agent_run` | run 元数据（status、triggerType、errorCode） |
| `agent_run_event` | AG-UI 事件事实源，**写入量最大的集合** |
| `agent_tool_call` | 工具调用审计 |
| `agent_active_run` | 运行权租约 / 活跃 runId / 停止标志，见 §4 |
| `agent_pending_interrupt` | HITL 确认卡片现场 |
| `agent_resume_mark` | resume 幂等指纹 |
| `agent_state` | AgentScope 会话状态（由框架 `MongoAgentStateStore` 管理） |
| `agent_kv_store` | 后台子任务记录与长期记忆文件 |

### 5.2 索引（代码声明，首次启动自动建）

所有集合都有一条 `{ expiresAt: 1 }` TTL 索引，不再逐条列出。

| 集合 | 索引 |
|------|------|
| `agent_run_event` | `{runId, eventIndex}` unique · `{userId, projectId, threadId, runId, eventIndex}` · `{threadId, createdAt}` |
| `agent_run` | `{runId}` unique · `{threadId, startedAt:-1}` · `{userId, projectId, startedAt:-1}` · `{status, startedAt}` |
| `agent_tool_call` | `{runId, toolCallId}` unique · `{toolCallId, resultState}` · `{threadId, calledAt:-1}` · `{userId, projectId, calledAt:-1}` |
| `agent_message` | `{threadId, messageId}` unique · `{threadId, createdAt}` · `{runId}` |
| `agent_session` | `{threadId}` unique · `{userId, projectId, updatedAt:-1}` |
| `agent_active_run` | `{scopeKey}` unique |
| `agent_pending_interrupt` | `{threadId}` unique |
| `agent_resume_mark` | `{markKey}` unique |
| `agent_kv_store` | `{scope, namespace, key}` unique |

`agent_run` 上的 `{status, startedAt}` 是孤儿清扫的预筛索引——等值字段在前、范围字段在后，顺序不能调换，
否则 `findStaleRunning` 会退化成全表扫，而它每 `active-run-ttl` 就要跑一次。

### 5.3 数据生命周期

保留期**只在写入时生效**：改配置只影响之后新写入的数据，不会追溯已有文档。任一项配 `0` 表示永不过期。

| 项 | 默认 | 说明 |
|----|------|------|
| `run-event` | 7d | 同时决定断线重连能回放多久以前的 run |
| `run` | 90d | run 元数据与用量统计 |
| `tool-call` | 90d | 工具调用审计 |
| `message` | 180d | 用户可见的聊天记录 |
| `session` | 180d | 按最后活跃时间滚动续期 |
| `task` | 7d | 被 promote 出的后台子任务 |
| `memory` | 180d | 长期记忆，按最后一次使用滚动续期 |
| `state.ttl-days` | 30 | AgentScope 会话状态 |

**两条配置约束，配错了会产生查不出来的脏数据：**

- `run-event` 不应大于 `run`：事件流不能比 run 元数据活得久。
- `session` 不应小于 `message`：会话行先过期会让消息变成列不出来的孤儿。

`overrides` 按 `project-ids` / `user-ids` 维度覆盖全局默认，两者是 OR 关系，列表内第一个命中的生效，留空
的项沿用全局值。**只对 run-event / run / tool-call / message / session 生效**——task 和 memory 的 TTL 由
store 统一持有，不参与维度化。

删除 session 会级联清理 thread 关联的 run / message（业务层 `AgentSessionService`），不依赖 TTL。

## 6. 告警与可观测性

| 指标 / 日志 | 说明 | 建议阈值 |
|-------------|------|----------|
| `OwnerReplicaLost` 计数 | 副本猝死遗留的 run，见 §4.3 | **非零即应排查副本异常退出**；发布期短暂升高正常 |
| 熔断器打开 | 模型调用链连续失败达阈值 | 打开即告警；关注 cooldown 后探测是否恢复 |
| 并发拒绝数 | `max-global` / `max-per-user` 拒掉的请求 | 仅在显式打开限流时有效；持续非零说明容量不足 |
| run 429 率 | 同 thread 并发 run 或 runId 冲突 | 突增排查客户端重复提交 |
| `stream_timeout` / `reconnect_timeout` | SSE 衔接超时 | 按 P95 调整 `reconnect-timeout` |
| Mongo `agent_run_event` 写入延迟 | 事件持久化，写入量最大 | P99 > 1s 告警 |
| run 终态不一致 | `agent_run.status` vs 末条 terminal event | 应为 0 |
| 心跳续期失败 | 租约续不上，run 会被误判成孤儿 | 非零即告警 |
| 模型网关 5xx / 超时 | LLM 调用 | 按现有网关 SLA |
| delegation 数 / run | Coordinator 拓扑 | 超过 `max-delegations` 应被截断 |

日志关键字：`HarnessAgent ready`、`live stream run`、`replay terminal run`、`coordinator live toolkit`、
`agent orphan run sweeper started`、`lost its owner replica`、`agent run lock heartbeat started`。

## 7. 故障恢复

### 7.1 SSE 断线

1. 客户端记录已收最大 `eventIndex`。
2. 调用 `GET /run/stream?projectId=&threadId=&runId=&lastEventIndex=`。
3. 若 run 已终态，收到完整回放后结束；若仍活跃，继续增量直到 terminal 或 timeout。

### 7.2 Run 卡住（RUNNING 不结束）

1. `GET /run/status` 确认 runId 与状态。
2. `POST /run/stop` 请求停止。
3. **先等够生效延迟再判断失败**：停止标志是轮询消费的，上界约 1s 加上当前这一步的剩余时间（§4.2）。
   Agent 卡在一次长模型调用上时，要等调用返回或超时。
4. 若副本已经崩了，**不需要任何人工干预**：`AgentOrphanRunSweeper` 会在最多一个 `active-run-ttl` 之后把它
   收成 `FAILED` + `OwnerReplicaLost`，运行权文档由 TTL 回收。旧版文档里"运维清理锁键"那一步已经不存在，
   也没有任何可供手工清理的外部键。
5. 真要手工介入时，唯一的位置是 `agent_active_run` 中对应 `scopeKey` 的那一行——删掉它会立刻放开该会话的
   运行权。**仅在确认无真实活跃 run 时操作**，否则会放进第二个并发 run。

### 7.3 HITL / Interrupt

- pending interrupt 存在 `agent_pending_interrupt`（现场）+ `agent_message`（归档）。
- **确认卡片有过期时间**。一组卡片整组过期后，`/run/status` 的 `hasPendingInterrupt` 会回 `false`，前端不应
  再渲染卡片；此时 resume 会被拒，用户需要重新发起。这两处判断走同一个仓储门面，不会再出现"界面上还有卡片、
  点下去却说没有未决中断"的分歧。
- resume 通过 POST `/run` 携带 AG-UI resume payload；幂等键（`agent_resume_mark`）防重复提交。
- **AgentScope 2.0.4 要求 resume 数组覆盖全部未决中断**，少一个会直接报 `AGUI_INTERRUPT_CONTRACT_ERROR`，
  且这个校验先于过期校验执行。客户端只回传部分卡片时看到的就是这个错，不是过期。
- SUSPENDED run 回放时 synthetic 路径会附带 pending interrupts。

### 7.4 Mongo 事件缺失

- 正常 run 都应有 `agent_run_event` 记录。
- 无事件时 stream 走 synthetic（RunStarted + RunFinished/RunError），**不会**重跑 Agent。
- 若大量缺失：检查 `AgentRunEventService` 写入错误与 Mongo 容量。

### 7.5 Mongo 不可用

没有降级路径，这是设计选择。Mongo 是 bk-repo 全服务的硬依赖，Agent 模块把全部状态收敛到它之后，不再保留
"拿不到存储就用内存兜底"的分支——那个分支在旧版 Redis 形态下存在，代价是副本间状态静默分裂。现在 Mongo 挂
了就是 Agent 整体不可用，与其余 bk-repo 服务同生共死。

## 8. 回滚策略

1. **功能回滚（推荐）**：配置中心关闭子 Agent（`topology.agents.*.enabled=false`），仅保留 Coordinator。
2. **应急收紧**：`features.read-only-mode=true` 停用全部写能力；`features.frontend-tools-enabled=false`
   缩小攻击面；`gray.enabled=true` 把流量收回白名单。三者都不需要重启。
3. **版本回滚**：回滚 `boot-agent` 镜像/包。**注意本版引入了新集合**（`agent_active_run`、
   `agent_pending_interrupt`、`agent_resume_mark`、`agent_state`、`agent_kv_store`、`agent_tool_call`），
   回滚到依赖 Redis 的旧版本时，这些集合里的在途状态**不会**被旧版本读取，等于所有活跃 run 失去归属——
   必须在无活跃 run 的窗口期操作，或接受这些 run 被旧版本当作不存在。
4. **配置回滚**：§3.2 中的旧 key **不再绑定**；必须恢复为新前缀等效值，不能只回滚 jar 而不改配置。

## 9. 多 Agent 拓扑（当前默认）

| Agent | ID | 默认 | 职责 |
|-------|-----|------|------|
| Coordinator | `bkrepo-assistant` | 启用 | 理解、路由、委派、汇总；仅 domain tools |
| Client | `client` | 启用 | frontend SchemaOnlyTools |
| Discovery | `discovery` | 启用 | 制品检索 |
| Transfer Diagnostics | `transfer-diagnostics` | **关闭** | 传输任务诊断 |
| Governance | `governance` | **关闭** | 权限判定解释（`explain_repository_permission`）、仓库治理配置查询 |

启用 `transfer-diagnostics` 前请完成 §11.4 评估集与权限验收。启用 `governance` 前同样建议先跑一轮真实
IAM 判定结果的抽样核对，避免 `explain_repository_permission` 的解释与实际生效权限出现偏差。

> **`governance` 的已知限制**：`get_repository_governance_info` 目前是占位实现，权限校验走的是真实 IAM，
> 但配额与保留策略字段恒为 `null`，返回体带 `"note": "repository governance info pending backend
> integration"`。agent 服务尚未接入仓库服务对应的 client。启用该 Agent 前需知悉这个工具问不出实际数据。

---

**相关文档**

- [AG-UI 对齐重构方案](./agui-bk-ci-aligned-refactoring-plan.md)
- [制品库多 Agent 开发指南](./artifact-repository-multi-agent-development-guide.md)
