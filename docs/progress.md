# 项目进展快照

> 最后更新: 2026-09-14
>
> 只记当前迭代。完成后改需求状态，不要在本文件无限追加历史，也不要写 `docs/memory/`。

## 当前进行中

- 无（P0–P2 文档工作流已落地，等下一个业务需求）

## 待办

- 下一个真实需求：跨服务先 `@new-design`，否则 `@new-requirement` 从 `001` 起号
- 领域 Skill 跟着线上事故再加厚

## 已完成（本迭代）

- [x] AI 文档工作流 P0：docs 工作区、根目录 workflow、6 篇架构文档
- [x] P1：需求三件套 + 领域 Skill + frontend/gateway 规则
- [x] P2：`docs/superpowers/`、`@new-design`、`@postmortem`、仓库根 `AGENTS.md`

## 卡点 / 待决策

- 无

## 近期决策记录

| 日期 | 决策 | 原因 |
|------|------|------|
| 2026-09-14 | 用户向 apidoc/install 与开发工作流文档并存，不互相替换 | 开源仓库仍要给人看的接口和部署说明 |
| 2026-09-14 | 分层 Kotlin 规则留在 `src/backend/.cursor/rules/`，根目录只放 workflow | 避免污染前端会话 |
| 2026-09-14 | progress 只保留当前迭代 | 避免学成过期总账 |
| 2026-09-14 | 领域知识写成 Skill，正文仍以 architecture 为准 | Skill 只放触发条件和禁止事项，避免两份长文漂移 |
| 2026-09-14 | 跨服务 / 存储语义 / 鉴权边界必须先 superpowers 再拆需求 | 避免 AI 直接在代码里试探 |
| 2026-09-14 | 不使用 `docs/memory/` 按日日志 | 进度只留在 progress；结论留在需求 / 架构 / 复盘 |
