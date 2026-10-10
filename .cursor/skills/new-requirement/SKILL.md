---
name: new-requirement
description: Creates a numbered bk-repo requirement package from the three-file template and updates the requirements index. Use when starting a new feature, filing a design, or the user says 新建需求 / new requirement / @new-requirement.
---

# 新建需求

## 步骤

1. 列出 `docs/requirements/` 下已有 `NNN-*` 目录，取最大编号 + 1（三位，从 `001` 起）。不要占用 `_template`。
2. 目录名：`docs/requirements/NNN-<kebab-english-or-pinyin>/`
3. 从 `docs/requirements/_template/` 复制 `requirements.md`、`solution.md`、`task-items.md`，填头信息和已知范围。未知项留空，不要编造 Issue / 分支 / 验收结果。
4. 在 `docs/requirements/README.md` 表格追加一行（状态默认「规划中」）。
5. 若用户已指定当前就做：把该需求写入 `docs/progress.md`「当前进行中」。
6. 回复里给出目录路径、编号、还缺哪些信息。

跨 2 个及以上微服务、改存储语义、或改鉴权/网关边界时：停下来改走 `new-design`，先写 `docs/superpowers/`，再回来拆号。

## 不要

- 不要回溯历史需求去补编号
- 不要在这一步写业务代码
- 不要把进行中方案写进 `docs/features/`
