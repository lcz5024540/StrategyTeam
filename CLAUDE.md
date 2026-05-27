# CLAUDE.md · CoinStrategy

> **本项目遵循 [`../Agent.md`](../Agent.md) 作为顶层 harness 规范**（ray-agent v0.5）。本文件只列 CoinStrategy 专属内容：栈、目录、与上层规范的关系。

---

## 1. 项目定位

- 量化/策略系统（Quant trading + Polymarket 跟单 + funding-rate 套利 + Top50 / Rate-momentum 策略等）。
- Domain（按 `../Agent.md` v0.5 §1）：**finance + engineering** 双 domain。
- 上游仓库：`https://github.com/lcz5024540/CoinStrategy.git`。

## 2. 技术栈

- **后端**：Java 17 + Maven 多模块（5 个：parent / common / core / job / web）+ Spring。
- **前端**：React 19 + Vite 8 + TypeScript 6 + Zustand + TanStack Query + Echarts。
- **持久化**：见 `sql/` 目录。
- **部署**：见 `deploy/linux/` + `scripts/`。
- **CI/CD**：**当前 缺**（无 `.github/workflows/`）——Phase 1 待补，目标是标准 GitHub Actions 公共 runner + Docker Hub/GHCR 推镜像。

## 3. 上层规范与项目专属的关系

```
┌─────────────────────────────────────────────────────┐
│ ../Agent.md（ray-agent v0.5，顶层 harness）          │
│  - 工作流（spec → plan → impl → ship → close）       │
│  - 7 个 engineering workflow skill                   │
│  - 6 个 finance workflow skill                       │
│  - 1 个 karpathy-guidelines behavioral skill         │
│  - memory bank（activeContext/systemPatterns/...）   │
└─────────────────────────────────────────────────────┘
                          ↑ 引用 / 继承
┌─────────────────────────────────────────────────────┐
│ 本仓库 CoinStrategy（被规范者）                       │
│  - 业务代码 + 既有产物                                │
│  - .claude/skills/*（项目专属 skill 已有 8 个）       │
│  - docs/（项目专属文档，与 ray-agent/docs/ 不冲突）   │
│  - PRODUCT_DESIGN.md / quant-trading-architecture.md │
│  - docs/polymarket/audit-observability/*             │
│  - docs/problem/*（事故 / 排查记录）                  │
└─────────────────────────────────────────────────────┘
```

**冲突解决规则**：
- 上层 ray-agent 规范 vs 本项目专属 skill 冲突 → **本项目专属优先**（最具体的赢）；
- 本项目跨任务通用行为 → 走 ray-agent `karpathy-guidelines`（四原则：先想清楚 / 简化 / 外科手术 / 目标驱动）；
- 本项目新增任意决策 / spec / plan 文件 → **格式优先用 ray-agent 模板**（写入本项目 `docs/` 下，但格式遵循 `../docs/skills/<write-adr|write-spec|start-exec-plan>/templates/`）。

## 4. 项目专属 skill 清单（`.claude/skills/`）

按本仓库内部惯例，下列 skill 已存在并优先于 ray-agent 通用 skill 使用：

| skill | 用途 |
|---|---|
| `coding-standards` | 本项目 Java / TS 编码规范 |
| `context-tracking` | 上下文追踪 |
| `db-migration` | 数据库变更工作流 |
| `doc-writing` | 项目文档撰写 |
| `git-commit-convention` | 提交规范 |
| `learning-strategy` | 学习策略 |
| `obsidian-knowledge-base` | 知识库管理 |
| `trading-security-audit` | 交易安全审计 |
| `troubleshooting` | 问题排查 |
| `superpowers/*` | 子 skill 集（详见其 README） |

**与 ray-agent skill 的接力规则**：
- 本项目"写 commit message" → 用 `.claude/skills/git-commit-convention/`（**不**用 ray-agent，因为本项目专属）。
- 本项目"写 ADR / spec / plan" → 用 ray-agent 模板（本项目无对应专属 skill）。
- 本项目"做 finance 任务"（model / pitch / equity / earnings / KYC） → 用 ray-agent finance-* skill。
- 本项目"调用 sec 数据 / treasury / FRED" → 用 ray-agent `k-dense-scientific`（meta-index）。

## 5. 既有产物 → SDLC 阶段映射

| SDLC 阶段 | 本项目已有产物（CoinStrategy 内） |
|---|---|
| 产品设计 | `PRODUCT_DESIGN.md`、`RATE_MOMENTUM_STRATEGY.md`、`TOP50_STRATEGY.md` |
| 架构 / 决策 | `quant-trading-architecture.md`、`docs/polymarket/*-decision-*.md` |
| 任务拆解 / 排期 | `docs/polymarket/problem/排查修复任务拆解-*.md` |
| Pre-deploy 检查 | `docs/polymarket/prod-deployment-checklist-w2-t6.md` |
| 上线 runbook | `docs/polymarket/deployment-runbook-2026-05-17.md` |
| 运行 / 监控 | `docs/polymarket/audit-observability/`（10+ 文件） |
| 事故 / 排查 | `docs/polymarket/problem/`（多份） |
| 测试 | `coin-strategy-web/src/test/` |

**已对齐 ray-agent 规范**：spec / architecture / plan 三类产物**实物充足**但**格式未完全对齐 §5.2 必含小节**——Phase 1 Step 2 会做 gap audit。

**确认缺口**：
- **CI/CD**：本项目无 `.github/workflows/`。Phase 1 Step 3 补 `setup-cicd` skill 并实际接入。
- **PR / code review**：无系统化沉淀。Phase 1 Step 3 补 `review-pr` skill。

## 6. 文档目录的边界

- `CoinStrategy/docs/`：**本项目专属**文档（含 polymarket / audit-observability / problem / 知识库）。
- `../docs/`（即 `ray-agent/docs/`）：**跨项目共享**规范层（skill / memory / 通用 references）。
- 这两套**不合并、不互相覆盖**。新建文档先问"是项目专属还是跨项目共享"：
  - 专属 → CoinStrategy/docs/
  - 共享 → ray-agent/docs/

## 7. Karpathy 四原则 · 强制在本项目执行

按 `../docs/skills/karpathy-guidelines/SKILL.md`，本项目任何代码 / 文档改动都遵守：

1. **Think Before Coding** — 不假设、不藏疑、亮权衡。
2. **Simplicity First** — 最少代码、不投机抽象。
3. **Surgical Changes** — 只动你必须动的；只清你自己造的乱。
4. **Goal-Driven Execution** — 先定可验证成功标准。

历史上本项目 `docs/problem/` 有不少"顺手优化引入新 bug"的记录——P-3 surgical changes 必须强化。

## 8. 变更日志

| 日期 | 变更 |
|---|---|
| 2026-05-26 | 初版桥接文件，对接 ray-agent v0.5 |
