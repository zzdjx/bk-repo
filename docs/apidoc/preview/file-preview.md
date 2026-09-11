# 文件在线预览

[toc]

## 预览文件内容

- API: GET /preview/api/file/onlinePreview/{projectId}/{repoName}/**
- API 名称: file_online_preview
- 功能说明:
  - 中文: 按文件类型返回可预览内容。Office 文档默认转 PDF；**ppt / pptx / pptm / ppsx** 默认走浏览器动画播放。路径含 `projectId/repoName` 时，OOXML（pptx/pptm/ppsx）若含内嵌音视频，会返回**去掉音视频载荷**的瘦包：只按 ZIP 中央目录索引，再读取幻灯片 XML/图片等非媒体条目，**不会把整包或视频读进堆**。内嵌音视频用同一接口的 `part` 参数按 HTTP Range 流式加载。没有内嵌音视频时原样流出。无路径变量的远程预览不瘦包、不支持 `part`，前端用完整 ZIP 播包内音视频。二进制 `.ppt` 不是 ZIP，原样流出给播放器，不转 PDF。本地仓库 **`.glb`** 按原样 Range 流出（不进 convert 仓）；远程 URL 预览与非 `.glb` 的 ONLINE3D 后缀（如 `.gltf` / `.stl`）返回不支持。超过 `preview.online3d.maxFileSize`（默认 300MB，`<=0` 关闭）时拒绝。
  - English: Stream preview bytes. PowerPoint files play in the browser. On the repo path endpoint, OOXML packages with embedded audio/video are returned as a slim ZIP. The server indexes the central directory and copies only non-media local records; video/audio payloads stay in storage and are not loaded into heap. Play `<video>`/`<audio>` via `part=ppt/media/...` and HTTP Range. Packages without embedded media are streamed as-is. The remote URL endpoint does not slim the package and rejects `part`; the client keeps in-package media. Binary `.ppt` is streamed as-is (not ZIP, not converted to PDF). Local `.glb` is streamed as-is with HTTP Range (no convert cache). Remote URL preview and non-`.glb` ONLINE3D suffixes (`.gltf`, `.stl`, …) are unsupported. Files larger than `preview.online3d.maxFileSize` (default 300MB; `<=0` disables the cap) are rejected.
- 鉴权: 路径含 `projectId/repoName` 的接口需要节点 `DOWNLOAD`。无路径变量的 `GET /preview/api/file/onlinePreview` 只校验登录，且只按 `extraParam.url` 下载远程文件；其中的 `projectId`/`repoName`/`artifactUri` 会被忽略，不会读仓库节点。分享预览 token（`Authorization: Temporary` 或 `?token=`）仅允许本接口与 `getPreviewInfo`。`<video src>` 与 `<model-viewer src>` 不会带 Authorization 头，分享场景必须把 `token` 留在 query 里。
- 请求参数

  | 字段 | 类型 | 是否必须 | 默认值 | 说明 | Description |
  | --- | --- | --- | --- | --- | --- |
  | extraParam | string | 否 | 无 | 远程预览 JSON（Base64），必须含 `url`；其中的 `projectId`/`repoName`/`artifactUri` 会被忽略。仓库内预览可空 | remote extra param; remote endpoint requires `url` |
  | convert | string | 否 | 无 | 对 ppt/pptx/pptm/ppsx 传 `pdf` 时强制 LibreOffice 转 PDF（前端播放失败回退） | force PDF conversion |
  | part | string | 否 | 无 | 仅路径含 `projectId/repoName` 的仓库预览可用。OOXML 的 `ppt/media/` 下单个音视频，例如 `ppt/media/media1.mp4`。禁止 `..`。远程 `GET /preview/api/file/onlinePreview`（无路径变量）会拒绝 `part`；该场景前端保留包内音视频 | stream one embedded media entry on the repo path endpoint only; the remote URL endpoint rejects `part` |
  | token | string | 否 | 无 | 分享预览临时 token | share preview token |

- 响应: 二进制流。瘦 pptx 的 Content-Type 为 `application/vnd.openxmlformats-officedocument.presentationml.presentation`；`part` 按媒体后缀返回 `video/mp4` 等；`.ppt` 为原文件类型；`convert=pdf` 为 `application/pdf`；本地 `.glb` 为 `model/gltf-binary`。`part` 与 `.glb` 请求支持 `206 Partial Content`。

## 预览元数据

- API: GET /preview/api/file/getPreviewInfo/{projectId}/{repoName}/**
- API 名称: file_get_preview_info
- 功能说明:
  - 中文: 返回文件名、类型、后缀、展示模板和水印。ppt/pptx/pptm/ppsx 的 `fileTemplate` 为 `pptx`。可预览的本地 `.glb` 的 `fileTemplate` 为 `online3D`，并带上三维体积字段：`warnFileSize` / `maxFileSize`（MB，对应 `preview.online3d.warnFileSize` 默认 50、`preview.online3d.maxFileSize` 默认 300，`<=0` 表示该项关闭）以及源文件 `size`（byte）。其它 ONLINE3D 后缀的 `fileTemplate` 为 `fileNotSupported`。
  - English: Preview metadata. Live PowerPoint files use `fileTemplate=pptx`. Previewable local `.glb` uses `fileTemplate=online3D` and includes `warnFileSize` / `maxFileSize` (MB; defaults 50 / 300; `<=0` disables that check) plus source `size` in bytes. Other ONLINE3D suffixes use `fileTemplate=fileNotSupported`.
- 鉴权: 路径含 `projectId/repoName` 的接口需要节点 `READ`。无路径变量的 `GET /preview/api/file/getPreviewInfo` 只校验登录，同样只认远程 URL，忽略仓库坐标。分享 token 同样允许。
- 响应: JSON。既有 `fileName` / `fileType` / `suffix` / `fileTemplate` / `watermark`。仅当 `fileTemplate` 为 `online3D`（本地可预览 `.glb`）时带 `warnFileSize`、`maxFileSize`（MB，`<=0` 关闭对应检查）和 `size`（源文件 byte）；其它模板这三项为 `null`。

## 三维预览配置

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `preview.online3d.warnFileSize` | 50 | 前端加载提示阈值（MB）。`<=0` 关闭告警。不要复用转换链路的 `preview.maxFileSize`。 |
| `preview.online3d.maxFileSize` | 300 | 后端硬拒绝阈值（MB）。`<=0` 关闭硬顶。 |
