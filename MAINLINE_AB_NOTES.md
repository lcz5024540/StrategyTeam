# MAINLINE-AB 编码交审笔记

> **本单仅编码，不装机、不过闸、不 admission、不 merge。**  
> 绑定口令/封存页 SHA256 见下表；任何修改若与封存正文冲突须重新请批。

## 绑定文档

| 文档 | 路径 | 整页 SHA256 | 设计钉（正文） |
|------|------|-------------|----------------|
| 开发口令 | `/Users/jenniferni/jenifer/build/mainline-ab-20261025-specs/dev-order-mainline-ab-coding-20261025.md` | `8a2d53581001c71a8fa3b728d97bc0b1a200c244842564f6fe6b71e4e1c3c33e` | - |
| 主线 A 封存 | `/Users/jenniferni/jenifer/build/mainline-ab-20261025-specs/MAINLINE-A-timeout-prereg-v1.0-sealed.md` | `368a09de0f164ee22f8e75604cac0eaa377e1a2db14c9c59b8597e538a222737` | `1d7a3119796e9b2273bbacceb0fbadabf20137b80533c452d880548a01c5acec` |
| 主线 B 封存 | `/Users/jenniferni/jenifer/build/mainline-ab-20261025-specs/MAINLINE-B-maker-vs-taker-prereg-v1.0-sealed.md` | `6d156c0d721528f76f5f2150cbb69e9c415b98507b8ad40d0fddea6118fac65f` | `f74041843ef3ba42beb147b457e2cb5065827e0eb899d494e0fd1224f671dcf2` |

- 基线 tip：`8e09ba7c2880f052bd4bf8d7cb3fdcac4bc0b15a`（`origin/support_htx_python`）
- 当前分支：`feature/mainline-ab-20261025`

## 主线 A（A2 超时强制退出）实现要点

- 主规格：**A2**；`T_max = 16` 根 15m = 4h；原始 A1 **REJECTED**；A1′ 仅敏感性开关、默认关闭。
- 配置名：
  - `HTX_TREND_A2_TIMEOUT_BARS`：默认 `16`（按 15m bar 计数）。`0`/`none`/`null` 关闭 A2。
  - `HTX_TREND_A1_PRIME_MIN_HOLD_BARS`：默认 `0`（关闭）。
- 触发条件：持仓达 `T_max` 且**未**触发盈利保护 / 趋势续持（`trailing_armed_at is None`）⇒ `FULL_CLOSE`，reason = `a2-thesis-timeout`。
- 硬止损优先：初始止损 / invalidation 穿越在 A2 之前返回 `INVALIDATED` / `FULL_CLOSE`，永不延迟。
- A1′ 行为：在 `min_hold_bars` 内仅延迟「弱保护/非硬」出场（flat loss、profit protection breach、partial close、funding rate exit），硬止损与 A2 超时仍生效。
- 落点：
  - `htx-trend-python/src/htx_trend/exits.py`：`ExitInput` 新增 `bars_held` / `timeout_bars` / `min_hold_bars`；`decide_v4` 在硬止损后插入 A2 超时与 A1′ 抑制逻辑。
  - `htx-trend-python/src/htx_trend/position_manager.py`：`ExitConfig.from_env()` 读取配置；`_manage()` 由 `created_at`/`entry_filled_at` 计算 `bars_held` 并传入 `ExitInput`。
- 入场信号 / sizing / ATR 倍数 / 保证金上限**未改**。

## 主线 B（maker vs taker · 可配置）实现要点

- **PAPER 默认路径仍为 taker**；maker 路径默认关闭，不可作为默认路径。
- 新增模块：`htx-trend-python/src/htx_trend/maker_execution.py`（纯函数 + 配置 + shadow/pair logging）。
- 配置旋钮（env 名）：
  - `HTX_TREND_MAKER_ENABLED`：默认 `false`。
  - `HTX_TREND_MAKER_OFFSET_BPS`：默认 `2`（相对 mid 的挂单价偏移）。
  - `HTX_TREND_MAKER_MAX_WAIT_BARS`：默认 `4`。
  - `HTX_TREND_MAKER_AMEND_THRESHOLD_BPS`：默认 `1`。
  - `HTX_TREND_MAKER_PARTIAL_ACCEPT`：默认 `false`。
  - `HTX_TREND_MAKER_TIMEOUT_ACTION`：默认 `TAKER`（超时后转吃单）；可选 `CANCEL`。
  - `HTX_TREND_MAKER_FEE_RATE`：默认 `0.0002`（仅用于 shadow 成本日志）。
- 落点：
  - `htx-trend-python/src/htx_trend/execution_service.py`：`EntryExecutionConfig` 增加 `maker_config`；`_order_request()` 在 maker 启用且 mid 有效时返回 LIMIT 单并计算 maker 限价；`submit()` 记录 `maker_shadow_pair` evidence（taker vs maker 成本差）。
  - `htx-trend-python/src/htx_trend/paper.py` 已支持 LIMIT 单按 K 线 high/low 成交；maker 路径复用该能力。
- **编码 ≠ 过闸**：B 仍欠 `ORDERPX σ_d≥200`（权威 fills）方可主张合规配对结论；未成/超时按封存要求视为配对缺失，不得当零成本赢家。

## 硬禁确认

- [x] 未做 LIVE 变更 / admission / LiveApproval / 仓286 补写 / 43.139 N* 装机
- [x] 未触碰 `htx-trend-paper-4h` unit 或其目录
- [x] 未 SSH 装机热更写库；本单仅编码 + commit + 普通 push
- [x] 未放宽 ATR / 硬止损 / 仓位与保证金上限
- [x] 未将原始 A1（阻断初始止损 / 硬止损可延迟）实现为默认路径
- [x] 未宣称 B 合规配对通过；注释与 NOTES 写明仍欠 `ORDERPX σ_d≥200`
- [x] 未改入场信号逻辑；成本假设 12bps + funding 口径不变
- [x] 未改 `research/lob_prob`、collector/nginx/前端部署
- [x] 未执行生产迁移 / 未连现网 DB
- [x] 未 force push / 未创建 PR / 未 merge

## pytest 汇总

| 套件 | passed | failed | skipped |
|------|--------|--------|---------|
| `htx-trend-python` | 533 | 0 | 0 |
| `research` | 161 | 0 | 1 |

- 运行命令：
  ```bash
  export ALL_PROXY=socks5h://127.0.0.1:7897 HTTPS_PROXY=socks5h://127.0.0.1:7897 HTTP_PROXY=socks5h://127.0.0.1:7897
  PY=/Users/jenniferni/jenifer/venv-research/bin/python
  cd /Users/jenniferni/jenifer/build/mainline-ab-20261025/htx-trend-python
  PYTHONPATH=src $PY -m pytest -q
  cd /Users/jenniferni/jenifer/build/mainline-ab-20261025/research
  PYTHONPATH=src $PY -m pytest -q
  ```

## 改动文件列表

```
htx-trend-python/src/htx_trend/exits.py
htx-trend-python/src/htx_trend/position_manager.py
htx-trend-python/src/htx_trend/maker_execution.py          (新增)
htx-trend-python/src/htx_trend/execution_service.py
htx-trend-python/tests/test_exits.py
htx-trend-python/tests/test_position_manager.py
htx-trend-python/tests/test_maker_execution.py             (新增)
htx-trend-python/tests/test_execution_service.py
MAINLINE_AB_NOTES.md                                     (本文件)
```

## 声明

- **编码 ≠ 过闸 ≠ 装机**：A 宣称 pass 前仍须三选一（拉长 N / 抬 δ / 停 A）；B 仍欠 `ORDERPX σ_d≥200`。
- 本单仅完成代码实现与测试，所有统计闸、产品闸、运维热更闸仍需后续独立审批。

## tip SHA

- `271689530d77388389ac609737a22e2164a8e23e`
