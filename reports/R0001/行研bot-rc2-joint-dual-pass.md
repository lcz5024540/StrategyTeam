# 行研裁定：rc.2 联调 ≠ 双过

| 日期 | 2026-10-12 |
| --- | --- |
| 源 run | `R0002-SG-RC2`（validation + conservative） |
| rc.2 包 content_hash | `a173f27d…`（stats-gates） |

## 裁定结论

1. **不得**将 rc.2 工程联调记为「统计门禁双过」。
2. **Bootstrap** 在本源 run 上 slice `ci_lower` 多数 **&lt; 0.8** → 数值门禁未过（与 permutation 阻塞无关）。
3. **Permutation** 在 rc.2 上 draw 1/1000 即因 200 次重采样耗尽失败 → 无 `p` 值，无法判 p&lt;0.05。

## 锁定的 permutation 策略（rc.3 必须遵守）

- 按 **品种** 完成 round-trip 笔数必须与观测一致，且 **总笔数** 一致。
- 每 draw 最多 **200** 次重采样；耗尽则 **硬失败**（不得静默接受不匹配 null）。
- **禁止** 仅匹配总笔数、禁止放宽为「近似匹配」。

## rc.3 工程修复说明（非数值过闸）

- **根因**：eligible bar 数量充足（约 2970−200 量级/品种），瓶颈是 rc.2 在持仓时 **丢弃** 逾期的 `forced_entry` 队列项，导致可行 null _schedule 趋近于 0。
- **修复**：推迟至 FLAT 后执行；分层抽样 eligible bars。
- **复测**：策略测试 bot 用 `0.1.1-rc.3` 重跑 `permutation --seed 99`（1000×）；通过仅表示 **CLI 可跑通**，不表示 bootstrap/permutation 数值过闸。

## 参考

- 联调记录：`/workspace/reports/0.1.1-rc.2-stats-gates/joint-test.md`（若已同步）
- rc.3 产物：`/opt/cursor/artifacts/htx-trend-0.1.1-rc.3-stats-gates/HANDOFF.md`
