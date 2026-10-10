---
name: checkpoint
description: Updates docs/progress.md and in-progress requirement status. Use when the user says checkpoint / 存档 / 同步进度 / @checkpoint, or after a multi-file change lands.
---

# Checkpoint

只改文档，不改业务代码。不要创建 `docs/memory/` 或按日日志。

1. 读 `docs/progress.md`。
2. 按**已发生事实**更新：进行中 / 待办 / 卡点 / 决策表。已完成条目不要在 progress 无限堆积，把需求状态改到 `docs/requirements/`。
3. 若某需求状态变了：改 `docs/requirements/NNN-*/` 头信息、task-items，以及 `docs/requirements/README.md`。
4. 架构或主流程变了：提醒（或直接）更新对应 `docs/architecture/*.md`。
5. 回复列出改了哪些文档。
