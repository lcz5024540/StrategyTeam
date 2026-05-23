---
name: git-commit-convention
description: "本项目的 Git 提交规范：commit message 格式（Conventional Commits）、提交粒度、分支策略、提交前检查。当用户要求「提交代码」「commit」「写提交信息」「合并分支」「建分支」，或需要保证提交历史清晰可追溯时使用。"
---

# Git 提交规范 (Git Commit Convention)

清晰的提交历史让 CoinStrategy 项目可追溯、可回滚、可审计 —— 这对交易系统尤其重要：出问题时要能快速定位「哪次提交引入的」。

## 何时使用

- 用户要求提交代码、写 commit message。
- 创建分支、合并分支、准备 PR。
- 整理提交历史。

## Commit Message 格式

采用 **Conventional Commits**：

```
<type>(<scope>): <subject>

<body>

<footer>
```

### type（必填）

| type | 含义 |
| --- | --- |
| `feat` | 新功能 |
| `fix` | 修复 bug |
| `refactor` | 重构（不改外部行为） |
| `perf` | 性能优化 |
| `docs` | 文档变更 |
| `test` | 新增/修改测试 |
| `style` | 格式调整（不影响逻辑） |
| `build` | 构建/依赖（pom.xml、package.json） |
| `chore` | 杂项（配置、脚本） |
| `revert` | 回滚某次提交 |

### scope（建议填）

用模块名，让人一眼看出影响范围：
`core` `job` `web` `frontend` `common-api` `common-client` `common-model` `funding-rate` `polymarket` `sql` `deploy`。

### subject（必填）

- 一句话说清「做了什么」，祈使句、动词开头。
- 不超过约 50 字，结尾不加句号。
- 中文项目可用中文，团队内保持统一。

### body（重要改动建议填）

说明**为什么**这样改、关键决策、影响范围。每行不要太长。

### footer（按需）

- 关联 issue：`Closes #123`。
- 破坏性变更：`BREAKING CHANGE: <说明>`（改了 `common-api` 公共接口时务必标注）。

### 示例

```
feat(polymarket): 增加每日自动白名单地址更新

基于活跃高 PnL 地址查找结果，每日定时刷新跟单白名单。
白名单上限 50 个，按近 7 日 PnL 排序。

Closes #88
```

```
fix(core): 修复资金费率套利金额用 double 导致的精度丢失

改为 BigDecimal 计算，比较用 compareTo。影响单笔下单数量计算。
```

## 提交粒度

- **一个提交只做一件事**。修 bug 顺手做的重构，拆成单独提交。
- 提交应是**可独立编译、可回滚**的完整单元，不提交半成品。
- 不把无关文件混进一次提交（用 `git add <具体文件>`，不要无脑 `git add -A`）。
- 大功能拆成多个小提交，每个提交讲清一个进展。

## 分支策略

- `main` / `master`：稳定可部署，不直接在上面开发。
- 功能分支：`feature/<简述>`，如 `feature/polymarket-weather-arbitrage`。
- 修复分支：`fix/<简述>`。
- 分支基于最新主分支创建，及时合并避免长期偏离。
- 复杂/实验性改动可结合 superpowers 的 `using-git-worktrees` 技能隔离开发。

## 提交前检查清单

- [ ] 代码能编译、测试通过（`test-driven-development`）。
- [ ] 没有把密钥、私钥、`.env`、凭证、大二进制文件加入提交（参考 `trading-security-audit`）。
- [ ] 只暂存了本次相关的文件。
- [ ] commit message 符合格式，type/scope/subject 准确。
- [ ] 改了 `common-api` 公共接口的，footer 标注了 `BREAKING CHANGE`。
- [ ] 一个提交只做一件事。

## 重要原则

- **永远新建提交，不随意 `--amend`** 已有提交（会改写历史）。
- **不强推 `main`/`master`**；任何强制/破坏性操作（`reset --hard`、`push -f`）需用户明确确认。
- 提交信息如实描述：`add` 是全新功能，`update`/`refactor` 是改现有功能，`fix` 是修 bug。

## 配套技能

- 提交前测试用 `test-driven-development`。
- 防止误提交敏感文件用 `trading-security-audit`。
- 收尾开发分支用 superpowers 的 `finishing-a-development-branch`。
