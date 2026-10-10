---
name: auth-permission
description: Guides authentication, RBAC, IAM, temporary tokens, and PermissionManager checks. Use when editing auth service, @Permission, route auth, temporary download tokens, or gateway auth lua.
---

# 鉴权

先读 [docs/architecture/auth.md](../../../docs/architecture/auth.md) 和 `src/backend/.cursor/rules/route-auth.mdc`。

## 边界

- auth 是身份和授权源；业务侧只经 `PermissionManager` / Feign
- 网关识别用户态并改写 URI，不替代项目/仓库/节点校验
- `PermissionService` 同时只有一个实现（local / devops / bkiamv3）

## 必须

- 路径无 `projectId/repoName` 时，在 Handler/Service 显式校验
- 写 `WRITE`、读至少 `READ`；失败抛拒绝，不要静默跳过
- STRICT 仓（尤其 Generic）要路径级校验，不要改短路
- 临时 token `permits` 用 `decrementPermits`（`findAndModify`）；中心/边缘实现不要混
- 不要无条件再注册一个 `PermissionService`
- 不要改名内置角色常量（`AUTH_BUILTIN_ADMIN`、`PROJECT_MANAGE_ID` 等）

## 文档

权限模型或错误码同步 `docs/apidoc/auth/`。
