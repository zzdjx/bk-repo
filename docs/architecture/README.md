# 架构文档索引

记录 bk-repo 系统设计，面向开发人员和 AI 助手。改相关代码时先对照对应文档。

## 文档列表

| 文档 | 说明 | 最后更新 |
|------|------|----------|
| [repository.md](repository.md) | 元数据中心：项目 / 仓库 / 节点。HTTP 在 `repository` 服务，核心实现在 `common-metadata` | 2026-09-14 |
| [generic.md](generic.md) | Generic 上传下载、分块、临时访问 | 2026-09-14 |
| [storage.md](storage.md) | 存储抽象：`StorageService` / `StorageManager`、凭据与引用计数 | 2026-09-14 |
| [auth.md](auth.md) | 认证授权：本地 / 蓝盾 / 权限中心，以及业务侧 `PermissionManager` | 2026-09-14 |
| [replication.md](replication.md) | 跨集群分发：任务类型、执行器、冲突策略 | 2026-09-14 |
| [analyst.md](analyst.md) | 制品扫描：analyst 调度 + analysis-executor 执行 | 2026-09-14 |

## 编写约定

- 包含：模块定位、目录、主流程、关键类、禁止事项
- 用 Mermaid 描述主路径
- 重大变更写进文末「迭代记录」
- 对外接口变更仍同步 `docs/apidoc*`，不要只改本目录
