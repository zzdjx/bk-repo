---
name: generic-upload
description: Guides Generic upload, download, block/separate/chunked, overwrite, and temporary access changes. Use when editing generic controllers/services, GenericLocalRepository, share/temp URLs, or X-BKREPO upload headers.
---

# Generic 上传下载

先读 [docs/architecture/generic.md](../../../docs/architecture/generic.md) 和 [storage.md](../../../docs/architecture/storage.md)。

## 动手前

声明读了架构文。确认改的是四种模式里的哪一种，不要混状态机。

| 模式 | 入口 |
|------|------|
| 简单 PUT | `GenericController` → `UploadService` → `GenericLocalRepository` → `StorageManager` |
| `/block/` | `createBlockId` → `storeBlock` → `mergeBlock` → `createNode` |
| separate | 每块按自身 sha256 + `TBlockNode`；完成时基节点 `FAKE_SHA256` |
| chunked | `append` / `finishAppend` |

## 必须

- 简单上传走 `StorageManager.storeArtifactFile`，不要先建节点再存文件
- 保留 `onUploadBefore()` 的校验 / 覆盖 / pipeline 规则
- 下载：`DownloadRedirectManager` 失败必须回落拉流
- 加载走 `NodeResourceFactoryImpl`，不要对业务下载直接 `StorageService.load`
- 覆盖看 `X-BKREPO-OVERWRITE`；分离分块进行中（`UPLOADID_KEY`）禁止覆盖
- 临时访问失败拒绝，不要匿名放行

## 文档

接口变更同步 `docs/apidoc/generic/`、`docs/apidoc-user/generic/`。
