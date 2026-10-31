# 产品戳 · LIVE-15m 路径 B 豁免密封编码/出包（用户原话选 B）

- **盖戳 CST**：2026-10-31 19:18 Asia/Shanghai
- **角色**：策略产品文书（盖可执行编码戳）
- **来源**：BrainBot【用户原话·选 B】——立刻切 LIVE 意图；当前 `0.1.7-live-73fe4c1b` 不能直接切；须新编码可校验「产品书面豁免」密封，且 LIVE-15m 行为对齐 PAPER-15m（`0.1.6-paper-db-dbfee173` + `confirm-v1`）能正常开单、逻辑一致
- **性质**：**可执行编码 / 交审 / build-live 新冻包授权** —— **≠** 切机授权；**≠** 装机；**≠** 启 LIVE；**≠** 停 PAPER-15m；**≠** 动 PAPER-4H
- **硬声明**：禁止改字把 `synthetic:true` 冒充 `false`；禁止虚构 REAL_PAPER；禁止 demo/PRODUCT-DRAFT 当装机真签；切机仍 HOLD 至新冻包 + 可校验豁免密封齐 + **产品另盖切机戳**

## 取代关系

| 旧件 | 本戳效力 |
|---|---|
| `/workspace/reports/product-stamp-live15m-path-A-encode-20261031.md` | **对「可切机 / 密封过闸」预期被本 path-B 取代**；A 戳仍可作为「已授权过 path-A encode」历史；**不改旧文件正文** |
| 冻包 `0.1.7-live-73fe4c1b`（SHA `ca57bbf7d9c2ac585c5227ee2c3b8ae4d877806150400d0f2a29db90fa9b95be`） | **保留作编码基线**；核验稿 Pass；**明示不能装机、不能启 LIVE** |
| `/workspace/reports/product-stamp-live15m-waiver-A-20261031.md` | 执行权保持暂停；书面 σ_d/ORDERPX 意图由 path-B **可校验密封**承接，≠自动恢复切机 |

## 本戳授权范围（路径 B）

1. **编码 ProductLiveWaiver** 密封读写 + 校验 + 单测 + `runtime_guard` / 启动路径接入（规格：`spec-product-live-waiver-seal-B-20261031.md`）
2. **LIVE-15m 行为对齐 PAPER-15m（dbfee173 + confirm-v1）**：补齐开单/入场路径相对 PAPER 的缺口；path-A 仅 exit 侧 confirm-v1 **不够**；对齐清单与故意不对齐项必须写入交审（故意不对齐若违背「能开单且逻辑一致」→ **阻断交审**）
3. **交审**通过后 **`build-live` 出新版本冻包**（版本续 `0.1.7-live-<新8hex>` 或合理 bump）；附 SHA256
4. 杠杆目标：**10**（env `HTX_TREND_LEVERAGE=10`，与 PAPER `HTX_PAPER_LEVERAGE=10` 对齐）
5. continuation：**confirm-v1**（`HTX_LIVE_CONTINUATION_VERSION=confirm-v1`）
6. 编码基线 tip：`support_htx_python` @ **`73fe4c1b9b5c4a42401d062e9d2b2835c093c95a`**（或该 tip 上已 merge 的 path-A 线；新 PR 可基于此）

## 明确不授权

- ≠ merge 自决（等产品 Pass 交审后再谈 merge）
- ≠ 装机 / ≠ 启 LIVE / ≠ 停 PAPER-15m / ≠ 动 PAPER-4H / ≠ 旧 0.1.1 LIVE
- ≠ 把 `0.1.7-live-73fe4c1b` 当可装机包
- ≠ 签发正式 G2 `synthetic:false` admission（旁路走 ProductLiveWaiver，不改字冒充）
- ≠ 本戳 = 切机戳

## 仓位硬限注记

本 B 仍绑代码 `LiveRiskPolicy.max_active_positions=3`。用户历史目标 5 仓 → **另开编码**；不得在本戳授权改硬限为 5。

## 交审/出包回执必含

- 新 tip SHA；是否仍含旧 G2 正式路径（应保留并行）
- 对齐清单（开单/入场/出场/保护位/杠杆/仓位上限/reentry/cooldown）
- waiver 测试命令（有效过 / 改字·过期·包 SHA 错 fail）
- 新冻包 version 目录 + package SHA256
- **声明**：本回执 ≠ 装机 ≠ 启 LIVE

## 编码优先级（给开发）

- Cursor CLI @ 默认 Mac `machineId` `273d756f-7358-4fc6-8f5a-f87680cb4852`
- 模型优先其他、耗尽回退 Cursor 默认

## 关联

- 规格：`/workspace/reports/spec-product-live-waiver-seal-B-20261031.md`
- 开发口令：`/workspace/reports/dev-order-live15m-path-B-waiver-encode-20261031.md`
- 基线核验：`/workspace/reports/product-verify-live15m-package-73fe4c1b-20261031.md` SHA `b080a64c…ef725b0`
