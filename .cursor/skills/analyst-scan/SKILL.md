---
name: analyst-scan
description: Guides artifact scan tasks, scanners, reports, quality gates, and analysis-executor. Use when editing analyst, analysis-executor, ScanService, subtasks, or scanStatus metadata.
---

# 扫描

先读 [docs/architecture/analyst.md](../../../docs/architecture/analyst.md) 和 `docs/analyst/overview.md`。

## 边界

- analyst 编排；analysis-executor 跑扫描器
- 状态只能 `StateMachine.sendEvent()`
- Generic 上传门禁在 `metadata-analyst` 的 `AnalystMetadataCustomizer`，其他仓走包版本钩子

## 必须

- 不要在 analyst 进程跑重扫描器，也不要绕过状态机改库
- `SubScanTaskDao.updateStatus` 保留 `lastModifiedDate` 乐观锁
- Executor Bean 名是 `"${Scanner.TYPE}Executor"`
- 不要对 `FAKE_SHA256` 未完成分块节点开扫
- 不要拆掉 `globalScan` 的 Redis 锁
- 扫描临时 token ≠ auth 下载 token
- 新代码用 `ScanTaskStatus`，不要用已废弃的 `ScanStatus`

## 文档

接口变更同步 `docs/apidoc/scanner/`。
