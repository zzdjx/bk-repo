# Drive Admin 缩略图回填

[toc]

## 回填缺失的作品缩略图

- API: POST /drive/admin/thumbnail/backfill
- API 名称: drive_admin_thumbnail_backfill
- 功能说明:
  - 中文: 平台管理员扫描名为配置值（默认 `imate-artifact`）的 DRIVE 仓库，找出缺少缩略图双字段的作品并补生成 sidecar。`force=true` 时覆盖已有 sidecar（可用于按新清晰度重生成 HTML 缩略图）。同步接口，到 limit 或墙钟后返回计数。
  - English: platform admin backfills missing IMATE artifact thumbnails; `force=true` overwrites existing sidecars
- 鉴权: 已登录的平台管理员（`user.admin=true`）。路径不含 `projectId/repoName`，仓库级过滤器不生效，Handler 内显式 `checkAdmin`。
- 请求体
  ```json
  {
    "projectId": "demo",
    "limit": 20,
    "concurrency": 1,
    "maxDurationSeconds": 60,
    "dryRun": false,
    "force": false,
    "artifactTypes": ["html"]
  }
  ```
- 请求字段说明

  | 字段 | 类型 | 是否必须 | 默认值 | 说明 | Description |
  | --- | --- | --- | --- | --- | --- |
  | projectId | string | 否 | 无 | 指定则只处理该项目下的目标仓；缺省扫描全部匹配仓 | optional project scope |
  | limit | int | 是 | 无 | 本次最多尝试生成的条数，失败也占额度，必须 `>0` | max attempts |
  | concurrency | int | 否 | 1 | 并发生成数，必须 `>0`；与上传共用按类型 Semaphore，回填等待许可 | worker count |
  | maxDurationSeconds | int | 否 | 60 | 墙钟秒数，范围 `1..300`。剩余时间不超过单条生成超时（默认 5s）时不再开新任务 | wall clock |
  | dryRun | boolean | 否 | false | 只扫描不生成；`attempted` 为将被处理的条数（含墙钟闸门），`succeeded`/`failed` 为 0 | scan only |
  | force | boolean | 否 | false | 覆盖已有 sidecar 并回写源节点缩略图字段；仍不改源节点 `lastModifiedBy` / `lastModifiedDate` | regenerate existing |
  | artifactTypes | string[] | 否 | 无 | 只处理这些作品类型（如 `html`）；缺省处理全部可生成类型；空数组非法 | type filter |
  | repoName | - | - | - | 不在请求中传递。仓库名来自配置 `drive.thumbnail.backfillRepoName`，默认 `imate-artifact`，且必须 `type=DRIVE` | configured repo name |

- 行为说明
  - 集群单飞：Mongo 锁键 `drive:thumbnail:backfill`，TTL = `maxDurationSeconds + 5` 秒。已有任务在跑时返回 409。进程崩溃后等 TTL 过期。
  - 扫描顺序：仓按 `projectId, repoName`，仓内按 `ino` 升序。默认只处理缺 `IMATE_ARTIFACT_THUMBNAIL` 或 `IMATE_ARTIFACT_THUMBNAIL_INO` 的作品；`force=true` 时已有缩略图也会重生成并覆盖同路径 sidecar。`drive.thumbnail.skipTypes` 与 `3d` 不入队。
  - 成功写入同目录 sidecar `.{name}.thumb.jpg` 以及源节点双字段。源作品**只改缩略图元数据**，不改 `lastModifiedBy` / `lastModifiedDate`，列表排序与更新人不变。失败打日志，计入 `failed`，不中断本轮。
  - `drive.thumbnail.enabled=false` 时接口 400。指定 `projectId` 但目标仓不存在或不是 DRIVE 时 404。全量模式下没有匹配仓则 200 且计数全 0。
  - 回填占用上传侧同类型 Semaphore，建议离峰调用。同类上传在回填期间可能 busy 失败。

- 返回
  ```json
  {
    "code": 0,
    "message": null,
    "data": {
      "attempted": 20,
      "succeeded": 17,
      "failed": 3
    },
    "traceId": "xxx"
  }
  ```

  | 字段 | 类型 | 说明 | Description |
  | --- | --- | --- | --- |
  | attempted | int | 尝试条数（含失败） | attempts including failures |
  | succeeded | int | 成功写入缩略图的条数 | generated |
  | failed | int | 失败条数；明细只写服务端日志 | failed |

- 错误码
  - 403：非平台管理员
  - 400：参数非法、limit 缺失、thumbnail 未启用
  - 404：指定项目下目标仓不存在或类型不是 DRIVE
  - 409：已有回填任务持锁
