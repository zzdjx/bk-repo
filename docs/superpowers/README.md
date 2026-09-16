# 大改动设计（superpowers）

单服务、边界清晰的需求走 `docs/requirements/`。下面两类先写本目录，**再**拆需求、再写代码：

- 跨 2 个及以上微服务
- 改存储语义（digest 键、引用计数、凭据迁移、HashFileLocator、清理任务）
- 改鉴权模型或网关与服务的职责边界

| 目录 | 用途 |
|------|------|
| [specs/](specs/) | 方案：为什么改、改什么、不改什么、关键决策 |
| [plans/](plans/) | 落地计划：阶段、拆需求、验证、回滚 |

模板：[`specs/_template.md`](specs/_template.md)、[`plans/_template.md`](plans/_template.md)。用 `@new-design` 生成，文件名 `YYYY-MM-DD-<kebab>.md`。

## 规格列表

| 日期 | 文档 | 状态 |
|------|------|------|
| — | 暂无 | — |

## 计划列表

| 日期 | 文档 | 状态 |
|------|------|------|
| — | 暂无 | — |
