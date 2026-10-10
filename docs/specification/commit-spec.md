# 代码提交规范

内部工蜂开发按本文。开源 GitHub 贡献者仍看仓库根 [CONTRIBUTING.md](../../CONTRIBUTING.md) 的 `type: 说明 #issue`。

## 代码检查

提交 MR 前自行跑过：

- 后端 Kotlin / Java：detekt
- 前端：ESLint

## commit 格式

```
[--story=<id>|--issue=<id>] type: 说明 [#github-issue]
```

- TAPD 需求分支（`story_*`）用 `--story=<TAPD id>`
- TAPD 缺陷分支（`issue_*`）用 `--issue=<TAPD id>`
- `type` 与说明之间用冒号+空格
- 说明写清改了什么 / 为什么，用中文即可
- 若同时对应 GitHub issue，末尾加 `#编号`

当前 `story_*` 分支上可以只写 `--story=<id> 说明`；合进 master / squash MR 时建议带上 `type:`。

示例：

```text
--story=138501338 优化 configmap
--story=138440985 fix: 预加载执行器满载测试改用独立线程池
fix: PyPI simple 索引过期脏读单飞覆盖写 #4474
feat: 支持本地仓 GLB 三维在线预览 #4628
```

### type

与 [CONTRIBUTING.md](../../CONTRIBUTING.md) 一致，常用：

| type | 说明 |
|------|------|
| feat / feature | 新功能 |
| fix / bug / bugfix | 缺陷修复 |
| refactor / perf | 重构或优化 |
| test | 测试 |
| docs | 文档 |
| chore | 构建脚本、任务、工程配置 |
| format | 仅格式化，无逻辑变化 |
| depend | 依赖增删改 |
| merge | 仅分支合并 |
| del | 删除仍可能有人用的功能或 API |

## Merge Request

个人分支上可以有多笔小提交。提 MR 前建议 rebase 收成可读的几笔，标题与 commit 一样带 TAPD 前缀和 `type:`。

```shell
git checkout story_<tapd-id>
# …开发与多次 commit…

# 如需把最近 N 笔收成规范说明
git rebase -i HEAD~N

git push origin HEAD
```

合入 master 后，本地再跟进：

```shell
git fetch origin
git rebase origin/master
```
