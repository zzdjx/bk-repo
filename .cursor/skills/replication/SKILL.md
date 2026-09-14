---
name: replication
description: Guides cross-cluster replication, replica tasks, federation, event sync, and FDTP blob transfer. Use when editing replication service, ReplicaTask, LocalDataManager, Replicator, or cluster nodes.
---

# 分发

先读 [docs/architecture/replication.md](../../../docs/architecture/replication.md)。

## 入口

按 `ReplicaType` 找执行器：`SCHEDULED` / `REAL_TIME` / `RUN_ONCE` / `EDGE_PULL` / `FEDERATION`。读本地数据只走 `LocalDataManager`。

## 必须

- 不要直接扫 repository 的 Mongo 集合
- 不要跨线程共享可变 `ReplicaContext`
- 事件去重键 `messageId + taskKey` 不要改
- 联邦 tracking 不要和 RUN_ONCE 混用
- CENTER 与 STANDALONE 集群凭据形态不同，不要混
- FDTP token 与 `ServiceAuthManager` 对齐
- 包类型路径跟对应 `*PackageNodeMapper`
- 改 `ConflictStrategy` / `ErrorStrategy` 默认值要写文档

## 文档

接口变更同步 `docs/apidoc/replication/`。
