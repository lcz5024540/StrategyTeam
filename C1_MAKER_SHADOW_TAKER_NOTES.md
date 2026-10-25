# C1-MAKER-SHADOW-TAKER 编码交审笔记

> **性质**：仅编码 → 交审；**禁止 merge、出包、装机、热更、LIVE、admission**。  
> **声明**：编码 ≠ 过闸 ≠ 出包；σ_d 仍 ≥ 200 ORDERPX，B 合规配对结论仍欠权威 fills。

## 绑定核验

| 文档 | 路径 | 状态 |
|------|------|------|
| 开发口令 C1 | `/Users/jenniferni/jenifer/build/c1-maker-shadow-20261026-specs/dev-order-c1-paper-maker-shadow-taker-20261026.md` | SHA256 `1ca193cab70dd709f373d30eefc037b992856c599f8f6086ee0bac458e4ac8b6` ✅ |
| 开发口令副本 | `/Users/jenniferni/jenifer/build/c1-maker-shadow-20261026/dev-order-c1-paper-maker-shadow-taker-20261026.md` | SHA256 `1ca193cab70dd709f373d30eefc037b992856c599f8f6086ee0bac458e4ac8b6` ✅ |
| 授权 | BrainBot 用户确认卡 t569s1989 = C1 | ✅ |
| 基线 | `origin/support_htx_python` @ `5c5a11d8173ed9a018357d210bcb37646445e99a`（MAINLINE-AB #51） | ✅ |

- 分支：`feature/c1-maker-shadow-taker-20261026`
- 提交后 tip SHA：`60acff7a87a49c5381e16e892904f86490a2c369`
- PR 号/URL：_提交后回填_

## 冻结表旋钮

| 配置名 | 代码默认 | C1 可配值 | 说明 |
|--------|----------|-----------|------|
| `HTX_TREND_MAKER_ENABLED` | `false` | `true` | 默认仍为 taker；C1 运维 env 开 |
| `HTX_TREND_MAKER_OFFSET_BPS` | `2` | `0` | 0 时 LIMIT 价 = 最优同向价锚 |
| `HTX_TREND_MAKER_MAX_WAIT_BARS` | `4` | `1` | 单 bar 等待 |
| `HTX_TREND_MAKER_MAX_AMENDS` | `None`（无显式限制，兼容旧行为） | `0` | 0 时禁用追价循环 |
| `HTX_TREND_MAKER_PARTIAL_ACCEPT` | `false` | `false` | 部分成 = 缺失；不补挂 |
| `HTX_TREND_MAKER_TIMEOUT_ACTION` | `TAKER` | `CANCEL` | 超时 ≠ 转吃单；C1 运维钉 CANCEL |

- post-only：`SwapOrderRequest.time_in_force="post_only"`（maker 启用时）。触发吃单 → 撤/拒 = 缺失；禁止挂失败改吃单。
- 定价锚：最优同向价。LONG→best bid；SHORT→best ask。无盘口→`maker_reference_price` 返回 `None`，微结构不可得=缺失，不静默用 mid。

## 单 bar 零改价最小闭环

落点：`htx-trend-python/src/htx_trend/maker_execution.py` 新增纯函数 `evaluate_maker_lifecycle`。

```
place LIMIT @ 最优同向价 (offset=0)
  → wait ≤ max_wait_bars (=1)
  → amend 仅当 max_amends > 0 且阈值突破（C1 钉 0，永不追价）
  → 耗尽后 timeout_action:
       CANCEL → CANCELLED / PARTIAL_CANCELLED（绝不转 taker/market）
       TAKER  → TAKER / PARTIAL_TAKER（仅兼容旧行为，C1 禁用）
```

- `partial_accept=False` 时任何部分成直接取消剩余，不补挂。
- 禁止在启用 maker 后静默走「超时转 taker」。

## P-SHADOW-TAKER

主路径 = 真实 maker LIMIT 发单；同信号另记影子 taker 意图价/假设成本，**只记账、不发单**。

- Evidence key：`p_shadow_taker`
- 记录结构显式 `leg` 字段：
  - `leg=maker_live`：真实 LIMIT 单价格、maker fee、成本 bps
  - `leg=taker_shadow`：假设 taker 价格（LONG 取 best ask，SHORT 取 best bid）、taker fee、成本 bps
- 保留旧 `maker_shadow_pair` evidence（主 taker 时影子 maker），用于向后兼容；C1 主场景使用新 key。

落点：`htx_trend-python/src/htx_trend/execution_service.py` 在 `submit()` 中 maker 启用且 `evidence_store` 存在时写入 `p_shadow_taker`。

## 钟（代码侧只协助）

代码侧不擅自切钟。运维维度建议通过 `maker_cut_ts`（或等价 `window_id`）标记 maker 路径启用时刻；后续分析必须避免把切前 taker 段与切后 maker 段混进同一主闸。代码中不自动混算。

## 硬禁确认

- [x] 未做 LIVE / admission / LiveApproval / 仓286 / 43.139 N* 变更
- [x] 未触碰 `htx-trend-paper-4h` unit 或其目录
- [x] 未放宽 ATR / 硬止损 / 仓位与保证金上限
- [x] 未改入场信号逻辑；成本假设 12bps + funding 口径不变
- [x] 未改 `research/lob_prob`、collector/nginx/前端部署
- [x] 未执行生产迁移 / 未连现网 DB
- [x] 未自行装机 / SSH 热更
- [x] 未 force push / 未 merge
- [x] 未宣称 σ_d 过闸或 B 合规配对通过

## pytest 汇总

| 套件 | passed | failed | skipped |
|------|--------|--------|--------|
| `htx-trend-python` | 548 | 0 | 0 |

运行命令：

```bash
export ALL_PROXY=socks5h://127.0.0.1:7897 HTTPS_PROXY=socks5h://127.0.0.1:7897 HTTP_PROXY=socks5h://127.0.0.1:7897
PY=/Users/jenniferni/jenifer/venv-research/bin/python
cd /Users/jenniferni/jenifer/build/c1-maker-shadow-20261026/htx-trend-python
PYTHONPATH=src $PY -m pytest -q
```

测试覆盖：
1. maker 默认 off（行为与现 taker 一致）
2. 启用后 LIMIT + 冻结旋钮可从 env 读入（offset=0 / wait=1 / amends=0 / partial=false / timeout=CANCEL）
3. 超时 CANCEL：启用 maker + CANCEL ⇒ 超时不转 taker/market
4. P-SHADOW-TAKER 有记录（maker on ⇒ `p_shadow_taker` evidence 存在，含 `leg` 区分）
5. 部分成不补挂（partial_accept=false）
6. 既有 MAINLINE-B / A2 套件保持通过

## 改动文件列表

```
htx-trend-python/src/htx_trend/maker_execution.py
htx-trend-python/src/htx_trend/execution_service.py
htx-trend-python/tests/test_maker_execution.py
htx-trend-python/tests/test_execution_service.py
C1_MAKER_SHADOW_TAKER_NOTES.md（本文件）
dev-order-c1-paper-maker-shadow-taker-20261026.md（口令副本，随 PR 提交）
```

## 声明

- 本 PR 仅完成编码与测试，**不合入、不出包、不热更**。
- ORDERPX σ_d 仍 ≥ 200；B 合规配对结论仍需权威 fills 后方可主张。
- 装机、切钟、VOID/RESET 等运维操作须另单审批执行。
