---
title: "文档维护机制"
type: runbook
status: active
tags: [polymarket, audit]
created: 2026-05-17
updated: 2026-05-17
---

# 文档维护机制

> ⏱️ **创建**: 2026-05-16 第十五批
>
> **目的**：把"主决议（历史完整）+ 主题精华子文档（当下事实）+ 索引（导航）"三层文档体系的维护责任明确化，避免主决议无限膨胀、子文档过时无人管。

---

## 一、文档三层结构

```text
docs/polymarket/
│
├── copy-trading-improvement-decision-2026-05-17.md   # 当前主决议 (第十七批起承接)
│   └── 每批落地后追加新节, 仅追加, 不修改旧节
│   └── 第 1-16 批历史时间线通过 `git log` 回溯 (旧主决议已第十八批清理)
│
├── audit-observability-index.md                       # 主题索引 (按主题分类的章节速查)
│   └── 每批末尾若有"主题视角"变化, 同步更新对应表格
│
├── prod-deployment-checklist-w2-t6.md                 # prod 上线 checklist
├── cross-strategy-reusable-infrastructure.md          # 跨策略基础设施清单
├── multi-strategy-architecture-design.md              # 多策略架构
├── follow-trade-entry-exit-strategy.md                # 入/出场策略
├── capital-recycle-exit-design.md                     # 资金回收
├── smart-wallet-follow-strategy-analysis.md           # 策略分析
│
└── audit-observability/                               # 当下事实精华子文档
    ├── w1-data-closure.md                            # W1 闭环数据 (主题视角)
    ├── w2-risk-gates.md                              # W2 风控门控 (主题视角)
    ├── tier-generalization.md                        # Tier 通用化 (主题视角)
    ├── web-bff-interfaces.md                         # 21 web 端点 (主题视角)
    ├── web-frontend-integration.md                   # 前端联调指南
    ├── audit-alert-onboarding.md                     # AUDIT_* 告警 onboarding
    ├── w2-t2-grayscale-sop.md                        # W2-T2 灰度 SOP (第十七批 + 第十八批自动化)
    └── maintenance.md                                # 本文档
```

---

## 二、各文档维护责任

| 文档 | 维护时机 | 维护内容 | 责任人 |
| --- | --- | --- | --- |
| **主决议** `copy-trading-improvement-decision-2026-05-17.md` | 每批落地后 | 追加新节 (节号递增, 不修改旧节) | 落地者 |
| **主题索引** `audit-observability-index.md` | 每批落地后 | 同步"批次时间顺序"+"配置开关速查"+"主题速查"表格 | 落地者 |
| **W1 / W2 / Tier 子文档** | 每月 1 日 | 把上月所有相关变更整合到对应主题, 删过时内容 | 月度复盘人 |
| **web-bff-interfaces.md** | 每次加 web 端点 | 同步端点表 + 按用途分组 | 落地者 |
| **web-frontend-integration.md** | 每次加 web 端点 | 加示例 + dashboard 建议 | 落地者 (或前端先行) |
| **prod-deployment-checklist** | prod 阈值调整时 | 更新阈值表 + 加 "首次部署日志" 章节 | 运维 |
| **cross-strategy-reusable-infrastructure** | 每个新策略上线前 1 周 | 更新 ✅/🟡/⚠️/❌ 四档清单 | 新策略负责人 |
| **maintenance.md** (本文档) | 维护机制改动时 | 新增表格列 / 流程 | 任一维护者 |

---

## 三、月度复盘 checklist

每月 1 日（或月初第一个工作日）跑：

- [ ] 查询主决议近 1 个月新增节数：`grep -c "^## " copy-trading-improvement-decision-2026-05-17.md` 与上月对比
- [ ] 主题索引"批次时间顺序"是否补齐
- [ ] 4 个主题子文档（W1/W2/Tier/web-bff）是否过期 — 查"上次更新"标注，若 > 30 天 → review
- [ ] 检查 `audit-observability/` 下所有文档头部的 ⏱️ 时间戳
- [ ] 跑 audit Job 健康指标 → 若有连续告警，在 W1/W2 子文档加 "已知问题" 段
- [ ] 更新 `web-bff-interfaces.md` 的端点数（第十八批：21 个）
- [ ] Web 端点 hit count 监控（如有）→ 删除"无人使用 90+ 天"的端点
- [ ] 跑跨策略基础设施清单 review — 若新策略已开工，更新 Tier B/C 抽取进度

---

## 四、归档机制

### 主决议归档触发条件

- 主决议节数 > 30，**或**
- 体量 > 2500 行，**或**
- 跨自然年（2026 年底强制归档）

### 归档流程

1. 把当前主决议（如 `copy-trading-improvement-decision-2026-05-17.md`）重命名为 `archive/copy-trading-improvement-decision-YYYY-Q?-archived.md`，**或** 直接物理清理（按第十八批先例：靠 `git log` 保留历史）
2. 把"当前事实快照"摘要写入新主决议 `copy-trading-improvement-decision-YYYY-MM-DD.md` 第一节作为 "Context inherited from archived doc"
3. 子文档不动 (audit-observability/ 始终代表"当下事实")
4. 索引文档 `audit-observability-index.md` 头部加 "archived doc 链接" 提示
5. PR 描述说明归档原因 + 评估的人

### 归档后主决议保留作用

- 法律审计 / 复盘 / 重大变更回溯
- 不允许修改 / 不允许引用作为"当下事实"（用主题子文档替代）
- 可被搜索（git grep / IDE search 等）

---

## 五、新增子文档的判断标准

何时该加 audit-observability/ 下的新文档？

✅ **该加**：
- 新主题持续产生内容（≥ 3 节 / 3 个组件 / 3 个端点）
- 某主题需要快速入门（如新人 onboarding 路径）
- 跨多个批次的决议汇总（如 W3-W6 真正开工后）

❌ **不该加**：
- 临时调试笔记 (用 wiki / 个人笔记)
- 一次性的部署日志 (在 PR description / runbook 里)
- 与现有子文档高度重叠的细分主题

---

## 六、版本协议

子文档头部必须有：

```markdown
> ⏱️ **上次更新**: YYYY-MM-DD 第N批 | 回看主决议第 X 节
```

修改后必须更新 "上次更新" 时间戳和批次号。

---

## 七、与决议过程的关系

| 阶段 | 文档动作 |
| --- | --- |
| 决议提出 | 主决议追加 "本批决议" 节 |
| 决议落地 | 主决议追加 "落地清单" + "自动化审视" |
| 决议生效 (灰度/prod) | 主题子文档更新 (配置开关 / hot path 流程) |
| 决议沉淀 | 月度复盘把内容归并到主题子文档, 主决议旧节可逐渐被遮蔽 |
| 主决议归档 | 见上节 "归档流程" |

---

## 八、跨策略文档体系（天气/BTC 策略开工后）

天气策略和 BTC 5min 策略各自的决议文档建议命名：

- `weather-strategy-decision-YYYY-MM-DD.md`
- `btc5min-strategy-decision-YYYY-MM-DD.md`

主题子文档保持 `audit-observability/` 跨策略共享。每个子文档头部加"覆盖策略"标注，例如：

```markdown
> 覆盖策略: 跟单 + 天气 + BTC 5min (跨策略通用层)
```

跟单专属内容用 H3 段落隔离：

```markdown
### 跟单策略 W2-T6 ⚠️ leader 专属
...
```

---

<!-- kb-backlink -->
> 本文归属：[Polymarket 知识地图](../../MOC-Polymarket.md) ｜ [知识库首页](../../知识库首页.md)
