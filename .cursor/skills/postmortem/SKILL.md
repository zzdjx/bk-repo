---
name: postmortem
description: Writes a postmortem under docs/postmortem from a template and updates the index. Use when the user says 复盘 / postmortem / @postmortem, after an incident, or after a milestone even without an outage.
---

# 复盘

只写文档，不写业务代码。不要编造时间线、损失或未确认的根因。

## 步骤

1. 读 `docs/postmortem/README.md` 和 `_template.md`。
2. 新建 `docs/postmortem/YYYY-MM-DD-<kebab>.md`，填概述、时间线、根因、改进措施、经验沉淀。
3. 类型写清：`事故` 或 `里程碑`。未知项标明「待确认」。
4. 在 `docs/postmortem/README.md` 表格追加一行。
5. 经验沉淀里逐项建议：改哪条 Rule、哪篇 architecture、哪个 Skill。用户同意后再改那些文件。
6. 回复列出文档路径和待确认事实。不要写 `docs/memory/`。
