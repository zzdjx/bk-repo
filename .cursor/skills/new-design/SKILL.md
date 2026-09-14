---
name: new-design
description: Creates a reviewable spec and/or implementation plan under docs/superpowers before coding cross-service or storage-semantic changes. Use when the user says 写方案 / 设计计划 / @new-design, or the change spans services, storage semantics, or auth/gateway boundaries.
---

# 大改动先写方案

只写文档，不写业务代码。

## 何时必须走这里

满足任一条件则**先**写 superpowers，再拆 `docs/requirements/`，再改代码：

- 跨 2 个及以上微服务
- 改存储语义（digest 键、引用、凭据迁移、HashFileLocator、清理）
- 改鉴权模型，或网关与服务的职责边界

单服务、边界已在 architecture 里写清的需求：用 `new-requirement`，不要强制 superpowers。

## 步骤

1. 读 `docs/superpowers/README.md` 和相关 `docs/architecture/*.md`。
2. 缺规格时：复制 `docs/superpowers/specs/_template.md` → `specs/YYYY-MM-DD-<kebab>.md`。未知决策留空，不要编造。
3. 规格已审、要落地时：复制 `plans/_template.md` → `plans/YYYY-MM-DD-<kebab>.md`，写阶段、验证、回滚，并建议拆哪些需求编号。
4. 更新 `docs/superpowers/README.md` 列表。
5. 用户要求开工时，再用 `new-requirement` 按 plan 拆号；本 skill 不创建需求目录。
6. 回复给出文件路径、还缺哪些决策。
