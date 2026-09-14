---
name: storage
description: Guides blob storage, credentials, cache, HashFileLocator, and StorageManager vs StorageService changes. Use when editing common-storage, StorageManager, file references, storage credentials, or download load paths.
---

# 存储

先读 [docs/architecture/storage.md](../../../docs/architecture/storage.md)。

## 边界

- 存储键是 digest（sha256），不是仓库路径
- `StorageService` 只打指定凭据；迁移 / 跨集群 / 归档恢复用 `StorageManager` / `NodeResourceFactoryImpl`
- `LocalNodeResource` 回退：当前凭据 → copy 源 → `oldCredentialsKey` → 归档恢复

## 必须

- 不要改 `HashFileLocator` 的 `/ab/cd/{sha256}` 切分
- `store` 对已存在 digest 返回 0，不要当成写入失败
- 引用未到 0 不要删 blob；`FAKE_SHA256` 不计引用
- `storage.type` 与 `cache.enabled` 决定 `FileStorage` / `CacheStorageService` Bean，不要手写第二套
- DRIVE 仓不要和普通仓共用同一套清理语义（`notAllowRepoTypes`）
- 传统分块临时布局 `{uploadId}/{sequence}.block` + `.sha256` 不要改名

## 文档

主流程变了更新 `docs/architecture/storage.md`。
