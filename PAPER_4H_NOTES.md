# PAPER-4H 交审短笺

仅编码。未装机、未热更、未写现网库、未改 LIVE / 15m PAPER 运行语义。

## 基线

- 分支 `feature/paper-4h-20261023`，基线 tip `004125c9f89b3b1dca8808332e7f882af78d1b06`
- 策略 `HTX_TREND_4H_V2` ↔ `SWING_4H`（entry=4hour / trend=1day）
- 参数为 JavaEntryConfig 默认（Donchian lookback=20、ATR=14）。不对齐 LIVE R0 坏掉的 4hour env
- 实例名 `htx-trend-paper-4h`

## 改动

- `paper_db_persist` 按 state `identity.strategy` / `identity.instance` 回填。允许集合只有 15m primary 与 4h 这一对。`strategy_name` 跟随 identity。共享 `account_label='paper'`。模块常量 `STRATEGY` / `ACCOUNT` 仍是 15m 默认。
- `release/package.py --paper` 仍绑定 `htx-trend-paper-primary`。`--paper-4h` 读取 `strategy_card.paper-4h.example.json`，config 与 MANIFEST bindings 为 `HTX_TREND_4H_V2` / `SWING_4H` / `htx-trend-paper-4h`。
- `deployment/paper-4h/` 仅为 unit、env、INSERT 草稿，标注未装机。

## 明确没做

未改 collector、开仓因子、admission 密封件、出场默认参数，未做 Donchian 长窗 ablation，未出运行时包，未 SSH，未 push。
