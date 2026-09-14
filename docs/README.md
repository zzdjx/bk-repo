# 项目文档索引

本目录同时放两类文档，不要混用：

| 类型 | 给谁看 | 目录 |
|------|--------|------|
| 用户与部署文档 | 使用者、实施 | [install/](install/)、[apidoc-user/](apidoc-user/)、[apidoc/](apidoc/)、[repository/](repository/) |
| 开发工作流文档 | 开发者、AI 助手 | 本页下列目录 |

开发工作流采用「不丢上下文」约定：改代码前先读进度和对应架构，做完同步文档。

## 工作流目录

| 目录 | 用途 | 更新方式 |
|------|------|----------|
| [progress.md](progress.md) | 当前迭代快照：在做什么、下一步、卡点、近期决策 | 任务推进时更新；只保留当前迭代 |
| [requirements/](requirements/) | 需求：描述、方案、任务拆解 | 新需求时创建，开发中持续更新 |
| [architecture/](architecture/) | 系统设计与架构决策，面向人和 AI | 代码变更后对照更新 |
| [features/](features/) | 已实现功能的设计与使用要点 | 功能完成后编写 |
| [postmortem/](postmortem/) | 踩坑与改进 | 问题修复或里程碑后编写 |
| [superpowers/](superpowers/) | 跨服务 / 存储语义 / 鉴权边界的规格与落地计划 | 写代码前创建 |

## 改代码前先读

1. [progress.md](progress.md) — 当前迭代是不是这件事
2. 对应 [architecture/](architecture/) 文档 — 模块职责和禁止事项
3. 跨服务或改存储/鉴权边界：先读或写 [superpowers/](superpowers/)
4. 若有进行中需求，读 `requirements/NNN-*/` 三件套
5. 对外接口变更必须同步 [apidoc/](apidoc/) 或 [apidoc-user/](apidoc-user/)

编码规范不在本目录：后端见 `src/backend/.cursor/rules/`，仓库级工作流见 `.cursor/rules/workflow.mdc`。

## 工作流操作

| 操作 | 触发 | 说明 |
|------|------|------|
| **new-design** | `@new-design` 或「写方案」 | 跨服务等大改动先写 spec/plan |
| **new-requirement** | `@new-requirement` 或「新建需求」 | 按模板分配编号并更新索引 |
| **checkpoint** | `@checkpoint` 或「存档进度」 | 更新 progress 与需求状态 |
| **recap** | `@recap` 或「恢复上下文」 | 读 progress + 进行中需求 |
| **postmortem** | `@postmortem` 或「复盘」 | 事故或里程碑复盘 |

领域 Skill（上传、存储、鉴权、分发、扫描）见 `.cursor/skills/README.md`。
