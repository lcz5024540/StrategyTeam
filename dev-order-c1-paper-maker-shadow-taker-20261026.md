# 开发口令 · C1：PAPER maker + P-SHADOW-TAKER（仅编码→交审→出包）

- **日期**：2026-10-26 ~07:23 Asia/Shanghai
- **产品**：策略产品
- **授权**：BrainBot 用户确认卡 t569s1989 = **C1**（切 PAPER maker + VOID/RESET 钟 + 强制 P-SHADOW-TAKER）
- **对齐**：钟影响条 SHA `0b0eb2bdc950b0527071194bedeee4412ecb1600f6e39f2435d3bb15b0e0e318`；微结构一盖+二盖（offset=0 / T_wait=1×15m / N_amend=0 / 部分成=缺失 / Δ_fill θ=25pp∧φ=0.50）；B 设计钉 `f7404184…`
- **性质**：仅编码 → 交审 → 合入 → **PAPER-15m 出包**；**≠运维切机**（运维另单）；**≠过闸 ≠LIVE ≠admission**

---

## 0. 一句话

`可以编码：主路径 PAPER maker（冻结表）+ 同信号影子 taker 只记账（P-SHADOW-TAKER）；交审合入后出 PAPER-15m 包；≠装机≠过闸≠LIVE`

---

## 1. 编码机

- Cursor CLI @ 默认 Mac `273d756f-7358-4fc6-8f5a-f87680cb4852`
- 模型优先其它可用；耗尽回退 Cursor 默认
- 基线：`support_htx_python` tip（含已合入 MAINLINE-AB / 包源 `5c5a11d8…` 血缘）

---

## 2. 必须交付

### 2.1 主路径 maker = 冻结表（启用时）

| 旋钮 | 冻结 / 产品钉 | 备注 |
|------|----------------|------|
| `HTX_TREND_MAKER_ENABLED` | 运维切机时 **true**（代码默认仍可 false；包须支持开） | 本单不要求改 PAPER 默认；运维 env 开 |
| `HTX_TREND_MAKER_OFFSET_BPS` | **0** | 现默认 2 → 须可配到 0；C1 运维将钉 0 |
| `HTX_TREND_MAKER_MAX_WAIT_BARS` | **1** | 现默认 4 → 须可配 1 |
| 改价次数 | **0**（不追价） | 须显式禁用 amend 循环；或 `AMEND` 上限=0 |
| `HTX_TREND_MAKER_PARTIAL_ACCEPT` | **false**（部分成=缺失） | 已默认 false，保持 |
| `HTX_TREND_MAKER_TIMEOUT_ACTION` | **CANCEL**（超时≠转吃单） | 现默认 TAKER **禁止**用于 C1；超时=配对缺失 |
| post-only | **ON**（若交易所支持）；触发吃单→撤/拒=缺失 | 禁挂失败改吃单 |
| 定价锚 | 最优同向价；无盘口→微结构不可得=缺失 | 与冻结表一致 |

**PR#51 Notes 遗留：** wait/amend/cancel 须接到可运行的 **单 bar、零改价** 最小闭环（足冻结表）；不得在启用 maker 后静默走「超时转 taker」。

### 2.2 P-SHADOW-TAKER（强制）

- 主路径 = **真实 maker 发单** 时：同信号另记 **影子 taker** 意图价/假设成本（**只记账、不发单**）
- 产出可审计 pair 记录（建议 evidence / DB 列清晰区分 `leg=maker_live` vs `leg=taker_shadow`）
- **无 P-SHADOW-TAKER → 不得主张封存 B 配对**（行研硬约束；测试须覆盖「maker on ⇒ shadow taker 记录存在」）
- 旧 `maker_shadow_pair`（主 taker 时影子 maker）可保留，但 **C1 主场景是反向**：主 maker + 影子 taker

### 2.3 样本 / 钟（代码侧只协助，不擅自切钟）

- 支持运维写入 / 读取 `maker_cut_ts` 或等价 window_id（文档化即可）
- **禁止**代码自动混算切前 taker 段与切后 maker 段进同一主闸

---

## 3. 交付物

1. PR 标题含 `【C1-MAKER-SHADOW-TAKER 交审】`
2. pytest 绿；覆盖：maker 默认 off；启用后 LIMIT+冻结旋钮；超时 CANCEL；**P-SHADOW-TAKER 有记录**；部分成不补挂
3. Pass + merge 后出 **PAPER-15m** 候选 tar + SHA256，version 建议 `0.1.6-paper-db-<shortsha>` 或后续 patch 号
4. 交审回执：策略产品 + BrainBot；出包后喊运维按运维口令热更

---

## 4. 硬禁

- 禁止 LIVE / paper-4h / admission / LiveApproval / 仓286 / 43.139 N*
- 禁止放宽 ATR/硬止损/仓位上限
- 禁止宣称 σ_d 过闸或 B 合规配对通过（σ_d 仍 ≥200 ORDERPX）
- 禁止在无运维口令下自行装机

---

## 5. 成功标准

- 合入 + 出包 SHA 回报
- 编码 ≠ 热更 ≠ 过闸；热更与 VOID/RESET 见运维口令
