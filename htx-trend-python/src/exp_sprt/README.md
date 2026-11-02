# EXP-SPRT Phase-0（探索）

独立研究包，不进入 LIVE / PAPER worker 触发链路。P3 主谓词已冻结为 **REJECTION = R1**、**BREAKOUT = B2**。R2 / B1 只作敏感旁注，R3 / B3 / R4 只作探索旁注。缺簿且谓词依赖簿、流断、去重冷却记为删失，不进 M2 分母。`band` / RR 拒绝是非确认，不是假触发。

`break_level` 不单列字段。空突破 `S := z_lo`（`zone_support[0].low`），多突破 `S := z_hi`（`zone_resistance[0].high`），写在 `evidence_ptr.break_level_mapping_sentence`。

默认证据根：`/workspace/evidence/exp-sprt-phase0-20261102/{plans,zone_events,arm_a,meta}/`。可用 `--evidence-root` 改写。

N₀ 设计值 L1≥400、L2≥200。本目录只描述，不构成正式窗口结论。

## 运行

在 `htx-trend-python/` 下：

```bash
python3 -m pytest tests/test_exp_sprt_phase0.py
python3 -m exp_sprt --evidence-root /workspace/evidence/exp-sprt-phase0-20261102
```

样例 `data_origin=fixture_replay`。只读订阅路径是 `exp_sprt.market.ReadOnlyHtxShadowFeed`：复用 `HtxMarketData` 的已完成 K 与盘口，成交走公开 trade 接口；空成交列表保持为空，不补打印。
