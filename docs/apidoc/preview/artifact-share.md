# 作品分享接口

[toc]

作品分享权限是独立于仓库 `READ` 的访问域：`/mine`、`/accessible`、打开/短链/短码以及后续预览/下载临时 token 均以分享可见性为准，不校验仓库读权限。访问记录会写入服务日志（userId、shareId、visibility、projectId、repoName）。

名称搜索对 `artifactName` 做转义后的大小写不敏感包含匹配（MongoDB `$regex`）。数据量大时可能扫描，不返回总数。

## 创建或更新作品分享

- API: POST /preview/api/artifact/share
- API 名称: artifact_share_upsert
- 功能说明:
  - 中文: 创建或更新当前用户作为作品创建者的分享配置
  - English: create or update artifact share owned by current user
- 鉴权: 需要登录；调用方必须是作品节点创建者
- 请求体
  ```json
  {
    "projectId": "demo",
    "repoName": "drive-local",
    "resourceId": 123,
    "visibility": "CUSTOM",
    "userIds": ["bob"],
    "orgIds": ["display-dept-1"]
  }
  ```
- 请求字段说明

  | 字段 | 类型 | 是否必须 | 默认值 | 说明 | Description |
  | --- | --- | --- | --- | --- | --- |
  | projectId | string | 是 | 无 | 项目 ID | project id |
  | repoName | string | 是 | 无 | 仓库名 | repo name |
  | resourceId | long | 是 | 无 | Drive inode | drive inode |
  | visibility | string | 是 | 无 | `PUBLIC` 或 `CUSTOM` | visibility |
  | userIds | list | 否 | [] | 指定用户；`PUBLIC` 时忽略并清空 | authorized user ids |
  | orgIds | list | 否 | [] | 指定组织展示 ID；`PUBLIC` 时忽略并清空 | authorized org display ids |

- `CUSTOM` 必须至少指定一个用户或部门。同一 `projectId + repoName + shareKind + resourceType + resourceId` 仅允许一条记录。
- 更新已有分享时保留当前作品名；新建时从节点元数据读取。
- `featured` 是独立于 `visibility` 的平台精选标记，用户分享接口不写入；更新时保留已有值。
- 作品类型 `type` 来自节点元数据 `IMATE_ARTIFACT_TYPE`；缺失时按 `fullPath` 扩展名回退。取值与搜索接口一致：`image` / `pdf` / `html` / `code` / `table` / `slides` / `markdown` / `video` / `audio` / `3d`。

## 重命名作品分享

- API: PUT /preview/api/artifact/share/{shareId}/name
- API 名称: artifact_share_rename
- 功能说明:
  - 中文: 修改当前用户创建的分享展示名称。不重新签发访问 token
  - English: rename an owned share display name without reissuing tokens
- 鉴权: 需要登录；仅创建者可改名。路径不含 projectId/repoName，在服务内校验创建者
- 请求体
  ```json
  {
    "artifactName": "新站点名称"
  }
  ```
- 请求字段说明

  | 字段 | 类型 | 是否必须 | 默认值 | 说明 | Description |
  | --- | --- | --- | --- | --- | --- |
  | artifactName | string | 是 | 无 | 去首尾空白后不能为空 | display name |

- 分享不存在返回未找到；非创建者返回无权限。

## 我创建的作品分享列表

- API: GET /preview/api/artifact/share/mine
- API 名称: artifact_share_list_mine
- 功能说明:
  - 中文: 查询当前用户创建的分享，返回完整可编辑信息（不含 token）
  - English: list shares created by current user
- 鉴权: 需要登录；不校验仓库 READ
- 请求参数

  | 字段 | 类型 | 是否必须 | 默认值 | 说明 | Description |
  | --- | --- | --- | --- | --- | --- |
  | keyword | string | 否 | 无 | 按作品名包含匹配，空表示不筛选 | artifact name contains |
  | cursor | string | 否 | 无 | 不透明游标；非法值返回 400 | opaque cursor |
  | limit | int | 否 | 100 | 页大小，最大 500 | page size |

- 响应 data

  | 字段 | 类型 | 说明 | Description |
  | --- | --- | --- | --- |
  | records | list | 分享完整信息 | share details |
  | nextCursor | string | 下一页游标，无更多时为 null | next cursor |
  | limit | int | 本次页大小 | page size |

- `mine` 完整信息含 `thumbnailIno`、`thumbnailFullPath`（来自源节点元数据，缺一则均为 null），不含 token
- 排序：`lastModifiedDate DESC, id DESC`。游标不含快照语义，翻页期间被更新的记录可能重复或遗漏。

## 我有权限访问的作品列表

- API: GET /preview/api/artifact/share/accessible
- API 名称: artifact_share_list_accessible
- 功能说明:
  - 中文: 查询当前用户可访问的作品摘要。权限为公开、创建者、指定用户、指定组织（含祖先部门）的并集
  - English: list artifacts accessible to current user
- 鉴权: 需要登录；以分享权限为准，不校验仓库 READ
- 请求参数: 与 `/mine` 相同，另可选 `channel`、`featured`
  - 缺省：公开 + 创建者本人 + 指定用户 + 指定组织（含祖先部门）的并集
  - `ALL`：公开 + 指定用户 + 指定组织
  - `PUBLIC`：社区公开
  - `CUSTOM`：指定分享（点名用户或部门命中）
  - `featured=true`：只返回 `featured=true` 的记录，与 `channel` 独立叠加；精选不授予额外权限
  - 非法 `channel` 返回 400
- 响应 records 仅含摘要：`shareId`、`shareKind`、`projectId`、`repoName`、`resourceId`、`artifactName`、`type`、`fullPath`、`downloadToken`、`createdBy`、`agentId`、`featured`、`lastModifiedDate`、`thumbnailIno`、`thumbnailFullPath`
- `downloadToken` 供调用方拼接 Drive 临时下载：`GET /web/fs-server/drive/temporary/download/{projectId}/{repoName}{fullPath}?token={downloadToken}`；记录尚未签发 token 时为 null
- `downloadToken` 绑定作品**父目录**仅当父目录路径段数大于 `preview.artifact-share.download-token-min-parent-depth`（默认 4）。例如 `/artifact/{agent}/default/{name}/v1/file` 会绑 `v1` 目录以便同目录 sidecar 下载；`/docs/a.png` 这类浅路径仍只绑原文件。根路径文件始终绑原文件。`thumbnailIno` / `thumbnailFullPath` 来自源节点元数据 `IMATE_ARTIFACT_THUMBNAIL_INO` 与 `IMATE_ARTIFACT_THUMBNAIL`，缺一则均为 null
- 历史分享的 downloadToken 可能仍绑定在单个文件或过浅的父目录上，用下方 admin 重签接口按当前深度规则重签（不改 previewToken）。原先的 `preview.artifact-share.refresh-download-token-parent-scope` 启动开关已废弃移除，由该接口替代
- 功能上线前创建的分享可能没有 `thumbnailIno` / `thumbnailFullPath`。源节点已有缩略图 sidecar 后，用下方 admin 回填接口把双字段写回分享记录

## Admin 回填历史分享缩略图

- API: POST /preview/api/artifact/share/admin/thumbnail/backfill
- API 名称: artifact_share_admin_thumbnail_backfill
- 功能说明:
  - 中文: 平台管理员扫描作品分享，从源 Drive 节点元数据 `IMATE_ARTIFACT_THUMBNAIL_INO` / `IMATE_ARTIFACT_THUMBNAIL` 回填。默认只处理缺字段；`force=true` 时覆盖已有字段。不生成 sidecar，不改 `lastModifiedDate`。同步接口，到 limit 或墙钟后返回计数
  - English: platform admin copies Drive node thumbnail metadata onto artifact share records; `force=true` overwrites existing fields
- 鉴权: 已登录的平台管理员（`user.admin=true`）。路径不含 `projectId/repoName`，仓库级过滤器不生效，由 `@Principal(ADMIN)` 鉴权
- 请求体
  ```json
  {
    "projectId": "demo",
    "repoName": "imate-artifact",
    "limit": 100,
    "maxDurationSeconds": 60,
    "dryRun": false,
    "force": false
  }
  ```
- 请求字段说明

  | 字段 | 类型 | 是否必须 | 默认值 | 说明 | Description |
  | --- | --- | --- | --- | --- | --- |
  | projectId | string | 否 | 无 | 指定则只处理该项目；缺省扫描全部 | optional project scope |
  | repoName | string | 否 | 无 | 指定则只处理该仓库；可与 projectId 组合 | optional repo scope |
  | limit | int | 是 | 无 | 本次最多处理的分享数，失败与跳过也占额度，必须 `>0` | max attempts |
  | maxDurationSeconds | int | 否 | 60 | 墙钟秒数，范围 `1..300` | wall clock |
  | dryRun | boolean | 否 | false | 只扫描不写库；`succeeded` 表示将写入的条数 | scan only |
  | force | boolean | 否 | false | 覆盖已有 `thumbnailIno` / `thumbnailFullPath`；仍不改 `lastModifiedBy` / `lastModifiedDate` | overwrite existing |

- 行为说明
  - 默认只处理 `shareKind=MATERIAL` 且 `resourceType=DRIVE_NODE`，并且缺 `thumbnailIno`（空/`<=0`）或缺 `thumbnailFullPath` 的记录。`force=true` 时已有双字段的记录也会入队并覆盖写回。按 `_id` 升序取前缀，取满 limit 或到墙钟即停
  - 源节点已有缩略图双字段则写回分享记录，不改 `lastModifiedBy` / `lastModifiedDate`、不重签 token。节点不存在或写库异常计入 `failed` 并打日志，不中断本轮
  - 源节点仍无缩略图双字段计入 `skipped`。需要先走 `POST /drive/admin/thumbnail/backfill` 生成 sidecar
  - 不生成文件。默认可反复执行，已有双字段的记录不会再入队；源 sidecar 重生成后用 `force=true` 同步分享记录

- 返回
  ```json
  {
    "code": 0,
    "message": null,
    "data": {
      "attempted": 100,
      "succeeded": 80,
      "skipped": 15,
      "failed": 5
    },
    "traceId": "xxx"
  }
  ```

  | 字段 | 类型 | 说明 | Description |
  | --- | --- | --- | --- |
  | attempted | int | 尝试条数（含失败与跳过） | attempts including failures and skips |
  | succeeded | int | 成功写回分享记录的条数；dry-run 时为将写入的条数 | copied |
  | skipped | int | 源节点仍无缩略图，跳过的条数 | node still missing thumbnail |
  | failed | int | 失败条数；明细只写服务端日志 | failed |

- 错误码
  - 403：非平台管理员
  - 400：参数非法、limit 缺失或 `<=0`、maxDurationSeconds 越界

## Admin 重签历史分享 downloadToken

- API: POST /preview/api/artifact/share/admin/download-token/refresh
- API 名称: artifact_share_admin_download_token_refresh
- 功能说明:
  - 中文: 平台管理员按当前规则重签历史分享的 downloadToken：父目录路径段数大于 `download-token-min-parent-depth`（默认 4）时绑父目录，否则绑原文件。旧 token 同时吊销。只写 `downloadToken` 一个字段，不改 `previewToken`、`lastModifiedDate` 与缩略图字段。同步接口，到 limit 或墙钟后返回计数与游标
  - English: platform admin re-issues share downloadToken; parent directory only when its depth exceeds the configured threshold
- 鉴权: 已登录的平台管理员（`user.admin=true`）。路径不含 `projectId/repoName`，仓库级过滤器不生效，由 `@Principal(ADMIN)` 鉴权
- 请求体
  ```json
  {
    "projectId": "demo",
    "repoName": "imate-artifact",
    "afterShareId": null,
    "limit": 200,
    "maxDurationSeconds": 60,
    "dryRun": false
  }
  ```
- 请求字段说明

  | 字段 | 类型 | 是否必须 | 默认值 | 说明 | Description |
  | --- | --- | --- | --- | --- | --- |
  | projectId | string | 否 | 无 | 指定则只处理该项目；缺省扫描全部 | optional project scope |
  | repoName | string | 否 | 无 | 指定则只处理该仓库；可与 projectId 组合 | optional repo scope |
  | afterShareId | string | 否 | 无 | 从该 shareId 之后继续扫描，取上一轮响应的 `lastShareId`；缺省从头开始 | scan cursor |
  | limit | int | 是 | 无 | 本次最多处理的分享数，失败与跳过也占额度，必须 `>0` | max attempts |
  | maxDurationSeconds | int | 否 | 60 | 墙钟秒数，范围 `1..300` | wall clock |
  | dryRun | boolean | 否 | false | 只扫描不写库、不签发 token；`succeeded` 表示将重签的条数 | scan only |

- 行为说明
  - 按 `_id` 升序扫描全部分享记录（不限 `shareKind` / `resourceType`），取满 limit 或到墙钟即停
  - **每条记录都会重签**，无法从记录内容判断 token 是否已迁移，因此本接口不具备幂等跳过能力。必须用响应里的 `lastShareId` 作为下一轮 `afterShareId` 推进，否则每次都会重复处理同一批
  - 新 token 先落库成功，再吊销被替换的旧 token；写库失败会回收刚签发的新 token 并保留旧 token，该条计入 `failed`，可安全重跑
  - 写回是 CAS：仅当 `downloadToken` 仍是扫描时读到的值才替换。若扫描期间用户重新分享（upsert）已换过 token，则放弃写回、回收本次签发的 token，计入 `skipped`，不会覆盖用户侧的新 token
  - 签发失败时保留原 token，计入 `skipped` 并打日志，不中断本轮
  - 不触碰 `thumbnailIno` / `thumbnailFullPath`，缩略图缺失请用上面的回填接口处理

- 返回
  ```json
  {
    "code": 0,
    "message": null,
    "data": {
      "attempted": 200,
      "succeeded": 195,
      "skipped": 3,
      "failed": 2,
      "lastShareId": "6f2c1a9e4b7d48f0a1c3e5d7b9f02468"
    },
    "traceId": "xxx"
  }
  ```

  | 字段 | 类型 | 说明 | Description |
  | --- | --- | --- | --- |
  | attempted | int | 尝试条数（含失败与跳过） | attempts including failures and skips |
  | succeeded | int | 成功重签并写回的条数；dry-run 时为将重签的条数 | re-issued |
  | skipped | int | 签发失败或并发已改动该记录，保留原 token 的条数 | kept existing token |
  | failed | int | 写库失败的条数；明细只写服务端日志 | failed |
  | lastShareId | string | 本轮最后处理的 shareId，作为下一轮 `afterShareId`；为 null 表示已扫到末尾 | next cursor |

- 错误码
  - 403：非平台管理员
  - 400：参数非法、limit 缺失或 `<=0`、maxDurationSeconds 越界

## 撤销分享

- API: DELETE /preview/api/artifact/share/{shareId}
- API 名称: artifact_share_revoke
- 功能说明:
  - 中文: 真实删除分享记录并废止临时 token。记录不存在时仍返回成功；非创建者返回无权限
  - English: hard-delete share; missing record is success
- 鉴权: 需要登录；仅创建者可删除

## 打开分享

- API: GET /preview/api/artifact/share/{shareId}/open
- API 名称: artifact_share_open
- 功能说明:
  - 中文: 校验当前用户是否在分享可见范围内后返回预览/下载 URL
  - English: open share after share-permission check
- 鉴权: 需要登录；公开分享任意已登录用户可打开；CUSTOM 需为创建者、指定用户，或所属组织命中指定组织（含祖先部门）。不校验仓库 READ。打开接口不返回指定用户/组织列表。

## 短链打开

- API: GET /preview/api/artifact/share/a/{shareId}
- API 名称: artifact_share_open_redirect
- 功能说明:
  - 中文: 与打开分享使用相同的分享权限校验。原生 PDF 在当前短链上直接返回 `application/pdf`，其它类型返回内嵌预览 HTML。网关把 `/a/{shareId}` 反代到本接口，不 302，地址栏保持短链。`shareId` 为记录主键（32 位 UUID，并兼容历史 ObjectId）。PDF 标题读打开时节点上的 `IMATE_ARTIFACT_NAME`，不用分享记录里的 `artifactName`；规则与文件预览的本地 PDF 相同
  - English: same share-permission check as open. Native PDF is returned as `application/pdf` on this short URL; other types return HTML that embeds the preview. The short URL stays in the address bar. The PDF title comes from the node metadata `IMATE_ARTIFACT_NAME` at open time, not the share record `artifactName`
- 鉴权: 与打开分享相同；不校验仓库 READ。浏览器访问 `/a/{shareId}` 未登录时，网关回落前端页并由 `getLoginUrl` 跳转企业登录，`c_url` 为该短链；登录后继续打开。独立部署弹出前端登录框
- 响应:
  - 有权限且不是原生 PDF: HTTP 200，`text/html`；iframe 加载 UTF-8 百分号编码后的预览地址
  - 有权限且为原生 PDF: HTTP 200，`application/pdf`，支持 Range。有节点 `IMATE_ARTIFACT_NAME` 时，先改 PDF 标题再按 Range 输出，不回写仓库
  - 当前用户不在分享可见范围: HTTP 403，`text/html` 无权限页（含分享创建者，按钮刷新当前短链）
  - 分享不存在、已撤销，或底层文件不存在: HTTP 404，`text/html`
  - 其它错误（如 token 签发失败）仍返回 JSON，与打开分享一致
  - 以上 HTML 响应均带 `Cache-Control: no-store`
- 兼容: 旧链接 `/a/{shareId}` 永久保留，与新链同时 200，不跳转。此路径不识别 8 位短码

## 短码打开

- API: GET /preview/api/artifact/share/share/{shortShareId}
- API 名称: artifact_share_open_by_short_id
- 功能说明:
  - 中文: 与打开分享使用相同的分享权限校验，通过后返回内嵌预览 HTML。网关把 `/share/{shortShareId}` 反代到本接口，不 302，地址栏保持短链。`shortShareId` 必须恰好 8 位 Base62（`0-9A-Za-z`，大小写敏感）
  - English: same share-permission check as open, then 200 HTML for `/share/{shortShareId}`
- 鉴权: 与短链打开相同。浏览器访问 `/share/{shortShareId}` 未登录时，网关回落前端页并由 `getLoginUrl` 跳转企业登录，`c_url` 为该短链
- 响应: 与短链打开相同（200 预览 HTML / 403 HTML / 404 HTML）；格式非法或短码不存在时为 404 HTML
- `sharePath` 返回 `/share/{shortShareId}`。`shareId` 仍为 32 位 UUID；管理接口（open/rename/revoke）只接受 `shareId`
- 新建分享立即写入短码；已有记录在列表、打开、返回 `sharePath` 时惰性补齐，生成后不再更换
- 网关：与现有 `/a/{shareId}` 同机增加 `/share/{shortShareId}` → `/preview/api/artifact/share/share/{shortShareId}`。若短链会打到仓库 UI nginx，`/share/` 规则必须比 `^/s/` 更精确，避免被旧仓库分享跳转误匹配
