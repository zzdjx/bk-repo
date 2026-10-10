# AI 助手入口

本文件给 Cursor / Claude / 其他 Agent 用。人读的产品说明见 [README.md](README.md)，部署和接口见 [docs/install](docs/install/)、[docs/apidoc-user](docs/apidoc-user/)。

## 先读

1. [docs/README.md](docs/README.md) — 文档地图
2. [docs/progress.md](docs/progress.md) — 当前迭代
3. 对应 [docs/architecture/](docs/architecture/) — 改哪个模块读哪篇
4. 进行中需求：`docs/requirements/NNN-*/`

工作流规则：[.cursor/rules/workflow.mdc](.cursor/rules/workflow.mdc)（先声明读了什么再改代码）。

## 编码规范落点

| 范围 | 位置 |
|------|------|
| 后端 Kotlin 分层 | `src/backend/.cursor/rules/` |
| 前端 | `.cursor/rules/frontend.mdc` |
| 网关 | `.cursor/rules/gateway.mdc` |
| 无路径变量时的鉴权 | `src/backend/.cursor/rules/route-auth.mdc` |

## 工作流 Skill

| 触发 | Skill |
|------|--------|
| 跨服务 / 改存储语义 / 改鉴权边界 | `@new-design` → `docs/superpowers/` |
| 单服务需求 | `@new-requirement` |
| 存档进度 | `@checkpoint` |
| 恢复上下文 | `@recap` |
| 事故或里程碑复盘 | `@postmortem` |

领域 Skill：`.cursor/skills/`（generic-upload、storage、auth-permission、replication、analyst-scan）。

## 不要

- 不要用新实现推翻 architecture 里已写的边界
- 不要把 blob 存进 repository，或把资源级鉴权只放在网关
- 不要在 `docs/progress.md` 堆已完成历史
