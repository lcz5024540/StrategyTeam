# CoinStrategy 技能库 (Skills)

本目录存放 CoinStrategy 项目的 AI 协作技能。每个技能是一个独立目录，内含 `SKILL.md`（带 `name` / `description` 元数据），AI 助手会按需自动调用。

## 目录结构

```
skills/
├── superpowers/                # 官方开源插件（14 个工程方法论技能）
├── coding-standards/           # 规范编码
├── doc-writing/                # 输出可读性强的文档
├── context-tracking/           # 上下文 / 进度记录
├── learning-strategy/          # 学习交易策略并落地为实施计划
├── obsidian-knowledge-base/    # 把散落 md 整理成 Obsidian 风格知识库
├── trading-security-audit/     # 交易系统代码安全审查
├── troubleshooting/            # 故障排查与复盘
├── git-commit-convention/      # Git 提交规范
└── db-migration/               # 数据库变更管理
```

> 这些技能已软链到 `.claude/skills/`，会被 Claude Code 自动加载。

## 一、superpowers（官方插件）

来源：[github.com/obra/superpowers](https://github.com/obra/superpowers)（v5.1.0），一套强制执行软件工程最佳实践的技能库。包含 14 个技能：

| 技能 | 作用 |
| --- | --- |
| `brainstorming` | 编码前澄清需求、探讨设计 |
| `writing-plans` | 把设计拆成 2~5 分钟粒度的原子任务 |
| `executing-plans` | 按计划执行 |
| `test-driven-development` | 红-绿-重构 的 TDD 流程 |
| `systematic-debugging` | 系统化调试 |
| `requesting-code-review` / `receiving-code-review` | 发起 / 接收代码评审 |
| `subagent-driven-development` | 子代理驱动开发 |
| `dispatching-parallel-agents` | 并行派发子代理 |
| `using-git-worktrees` | 用 Git worktree 隔离开发 |
| `verification-before-completion` | 完成前验证 |
| `finishing-a-development-branch` | 收尾开发分支 |
| `using-superpowers` / `writing-skills` | 使用与编写技能 |

> 如需让 Claude Code 全局加载，可在 Claude Code 内执行
> `/plugin marketplace add obra/superpowers-marketplace` 再 `/plugin install superpowers@superpowers-marketplace`，
> 或把本目录下的技能软链到 `.claude/skills/`。

## 二、项目自建技能

| 技能 | 何时触发 | 一句话说明 |
| --- | --- | --- |
| `coding-standards` | 写/改代码、代码评审 | Java + 前端编码规范，重点是分层纪律与金额精度 |
| `doc-writing` | 写文档、出方案、写 README | 文档结构、图表、命名与存放规范 |
| `context-tracking` | 记进度、写工作日志 | 记录做了/没做什么、决策与待办 |
| `learning-strategy` | 研究/落地交易策略 | 策略调研 → 映射架构 → 实施计划 |
| `obsidian-knowledge-base` | 整理文档、建知识库 | frontmatter / tags / 双链 / MOC 索引 |
| `trading-security-audit` | 安全审查、改密钥/下单逻辑 | 密钥、私钥、资金、订单安全清单 |
| `troubleshooting` | 排查问题、出复盘 | 系统化排查方法 + 报告模板 |
| `git-commit-convention` | 提交代码、建分支 | Conventional Commits + 提交粒度 |
| `db-migration` | 改表结构、写迁移 SQL | 命名、向后兼容、回滚、审查 |

## 技能之间如何配合

一个典型的「开发新功能」流程串起多个技能：

```
brainstorming → writing-plans          想清楚、拆任务
  → learning-strategy                  （若是新策略）调研并落地
  → coding-standards + test-driven-development   按规范写代码 + 写测试
  → trading-security-audit             涉及资金/密钥时安全审查
  → git-commit-convention              规范提交
  → context-tracking                   全程记录进度
  → doc-writing                        沉淀设计/说明文档
出问题时 → troubleshooting + systematic-debugging
```

## 维护说明

- 新增技能：建一个 `<技能名>/SKILL.md`，首部写 YAML 元数据（`name` + `description`），`description` 要写清「何时触发」。
- 修改技能：直接编辑对应 `SKILL.md`。
- `superpowers/` 为外部插件，升级时整体替换，不要手改其内容。
- 所有技能已通过 `.claude/skills/` 软链加载；新增技能后在该目录补一个软链即可。
