# BK-REPO Cursor 规则

| 文件 | 范围 | 说明 |
|------|------|------|
| `workflow.mdc` | 整个仓库 | 先读 docs 再改代码，做完同步文档 |
| `frontend.mdc` | `src/frontend/**/*.{js,vue,scss}` | 前端入口约定 |
| `gateway.mdc` | `src/gateway/**/*.{conf,lua}` | 网关路由与鉴权边界 |

后端分层编码规范仍在 [`src/backend/.cursor/rules/`](../../src/backend/.cursor/rules/README.md)，不要把 `backend.mdc` 复制到本目录 always-apply。

工作流与领域 Skill 在 [`.cursor/skills/`](../skills/README.md)。各类 Agent 先读仓库根 [`AGENTS.md`](../../AGENTS.md)。
