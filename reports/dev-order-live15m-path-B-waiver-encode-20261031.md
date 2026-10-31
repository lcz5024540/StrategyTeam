# 开发口令 · LIVE-15m 路径 B 豁免密封编码/出包

- **口令 CST**：2026-10-31 19:18 Asia/Shanghai
- **角色**：策略产品文书 → 量化开发
- **产品戳**：`/workspace/reports/product-stamp-live15m-path-B-waiver-encode-20261031.md`
- **规格**：`/workspace/reports/spec-product-live-waiver-seal-B-20261031.md`
- **用户原话（BrainBot 转述·选 B）**：立刻切 LIVE；`0.1.7-live-73fe4c1b` 不能直接切；须可校验产品书面豁免密封；LIVE 对齐 PAPER-15m（dbfee173+confirm-v1）能开单、逻辑一致
- **硬声明**：≠ merge 自决 ≠装机 ≠启 LIVE ≠停 PAPER-15m ≠动 PAPER-4H ≠ SendToAgent；禁止 fixture/`synthetic` 改字冒充；禁止单测用假 admission `synthetic:false` 冒充正式件
- **回执**：交审/出包回执 → BrainBot（可抄产品）；**勿直接联系用户 / 勿 SendToAgent**

## 基线

| 项 | 值 |
|---|---|
| 分支基线 tip | `support_htx_python` @ `73fe4c1b9b5c4a42401d062e9d2b2835c093c95a` |
| 基线冻包 | `0.1.7-live-73fe4c1b` · SHA `ca57bbf7d9c2ac585c5227ee2c3b8ae4d877806150400d0f2a29db90fa9b95be` · **明示不能装机** |
| PAPER 对齐参照 | 包 `0.1.6-paper-db-dbfee173` + continuation **`confirm-v1`** |
| 新 PR | 可基于上述 tip 开 branch；实现下列 1–4 后交审 |

## 编码优先级

- Cursor CLI @ 默认 Mac machineId **`273d756f-7358-4fc6-8f5a-f87680cb4852`**
- 模型优先其他、耗尽回退 Cursor 默认

---

## 目标（必须全部完成方可交审）

### 1) ProductLiveWaiver 密封读写 + 校验 + runtime 接入 + 单测

按规格 `htx-product-live-waiver-g2` 实现（建议模块：`product_live_waiver.py` 或扩 `live_approval.py`；签发 CLI 新建，**不要**改 `seal_live.py` 去写假 `synthetic:false` admission）：

- 必填字段、`LiveRiskPolicy.validate`（per_trade 0.005 / total_open 0.015 / max_active_positions=**3**）、`waiver_hash` canonical（空字段清零再哈希，仿 `LegacyLiveWaiver`）
- `waived_gates` 至少含 `PAPER_EXECUTION_14D`、`SIGMA_D_ORDERPX`
- **主方案**：ProductLiveWaiver **替代**正式 admission 的 PAPER≥14d 闸（非「waiver+精简 approval」）
- `runtime_guard` / `_validate_live_approval`：读 `HTX_TREND_PRODUCT_LIVE_WAIVER_PATH`；绑定 package_sha256 / source_commit / config / spec / account / instance_key / strategy_version / timeframe_profile 与 env·context 一致；失败 fail-closed；与正式三件套双挂时推荐 fail-closed
- **单测**：
  - 有效 waiver → 过
  - 改字 / 过期 / 包 SHA 错 / strategy 不符 / risk 非 3 仓 → fail
  - **禁止** fixture 冒充 `synthetic:false` admission；正式 G2 路径测试不得被本旁路破坏
- **与 REAL_PAPER≥14d**：旁路 ≠ 伪造证据；正式 G2 满窗路径须仍保留

### 2) LIVE-15m 行为对齐 PAPER-15m（催更硬需求 · 交审不过则退回）

用户要求：**能正常开单且逻辑一致**。PR#54 / path-A **仅 exit 侧 confirm-v1 不够**。

#### 2.1 必须审计并补齐的缺口

对照冻包内代码（A 基线）：

| 项 | PAPER（dbfee173 族） | LIVE（73fe4c1b）现状 | B 要求 |
|---|---|---|---|
| 出场 confirm-v1 | `HTX_PAPER_CONTINUATION_VERSION` → Paper PositionManager | `HTX_LIVE_CONTINUATION_VERSION` → `LivePositionManager` 已接 | 保持 + 回归 |
| **入场/开单路径** | `PaperTrendWorker` → `runtime.scan`（可带 `previous_exit`） | `LiveTrendWorker` + `HtxTrendEntryExecutor`；无旁路则 G2 拒 | **Waiver 旁路下须能正常开单**；审计 entry 闸门/信号/sizing/杠杆切换与 PAPER 同逻辑族并补缺口 |
| linked `reentry_gate` | `runtime.py` 对 candidates 调 `reentry_gate`；由 worker 传入 `previous_exit`（linked-v1 时有业务意义） | **未接线** | 目标 **confirm-v1**：清单写明「confirm-v1 下 reentry 非 PAPER 生效条件」；**若**交付物宣称 linked 对齐却不接 → **阻断** |
| cooldown | `HTX_PAPER_COMPLETED_TRADE_COOLDOWN` | 无 | 现网 confirm-v1 若 cooldown=false → 可标故意不对齐（行为等同）；若现网为 true → 须补或阻断 |
| 杠杆 | `HTX_PAPER_LEVERAGE=10` | `HTX_TREND_LEVERAGE` 默认 **5** | 文档/示例/**交审**明确 **`HTX_TREND_LEVERAGE=10`** |
| 最大仓 | PAPER 默认可 5 | `LiveRiskPolicy` **=3** | 本 B 绑 3；清单标故意不对齐；改 5 另开编码 |

#### 2.2 对齐清单（交审稿必附表；缺表 = 不过）

交审必须逐行填：**已对齐 / 已补齐 / 故意不对齐（理由） / 未对齐（阻断）**。

最低行：

1. 开单主路径（无仓时信号→下单）
2. 出场/保护位 confirm-v1
3. 杠杆 10
4. 活跃仓上限（3 vs PAPER 5 → 故意不对齐）
5. reentry_gate（confirm-v1 范围说明）
6. cooldown（对照现网 PAPER 实际 env）
7. ProductLiveWaiver 启 LIVE 校验枝

**阻断**：把「仅 exit confirm-v1」写成全量对齐；或开单路径仍无法在 waiver 下提交。

### 3) build-live 新冻包 + 交审稿 + 硬边界

- 版本号续 **`0.1.7-live-<新8hex>`**（新 tip 短 SHA）或合理 bump；**不要**覆盖/假装 `0.1.7-live-73fe4c1b` 可装机
- `build-live` → verify → 回执 SHA256
- 硬边界：**≠ merge 自决**（等产品 Pass）**≠装机 ≠启 LIVE ≠动 PAPER-4H ≠停 PAPER-15m**
- 更新 `LIVE_PACKAGE.md`：ProductLiveWaiver 用法、对齐范围、A 包不可装机声明

### 4) 交审回执格式（必须含）

```text
tip SHA: <full>
基线: 73fe4c1b… / 旧包 ca57bbf7…（不可装机）
新冻包: 0.1.7-live-<8hex>  SHA256=<...>
对齐清单: <表或链接>
waiver 测试命令:
  <pytest 或模块命令：有效过 / 改字 fail / 过期 fail / 包SHA错 fail>
是否仍含旧 G2 正式路径: 是/否（期望：是，并行保留）
声明: ≠装机 ≠启LIVE ≠merge自决
```

---

## 不做

- 不 merge、不装机、不启 LIVE、不停 PAPER-15m、不动 4H
- 不把 admission `synthetic:true` 改字成 `false`
- 不虚构 REAL_PAPER≥14d evidence
- 不把 demo / PRODUCT-DRAFT 当真签
- 不联系其他 agent / 不直接催用户切机

## 验收（产品交审员）

- [ ] 规格字段与校验器 + 单测命令可复现
- [ ] 开单路径对齐清单无「未对齐」阻断项
- [ ] 新冻包四一致材料齐全
- [ ] 回执声明硬边界
- [ ] 旧 G2 路径仍在；旁路不破坏正式满窗路径
