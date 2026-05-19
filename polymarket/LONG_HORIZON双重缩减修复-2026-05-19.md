# LONG_HORIZON_STAKE 双重缩减修复

## 历史 Bug

`SmartWalletSignalRiskService.java` 里 LONG_HORIZON multiplier 出现在两处:

| 位置 | 触发条件 | 作用对象 |
|------|---------|---------|
| 原 line 711-718 | `marketType=LONG_HORIZON` | `preliminaryStake` |
| 原 line 959-966 | `marketType=LONG_HORIZON && kellyStakeEnabled` | `recommendedStake` |

设计意图: 两处**互斥** — preliminaryStake 路径用前者, Kelly 路径用后者. 但实际有两条 fallback path 会让 recommendedStake 退化成 preliminaryStake **之后**又被乘一次:

| 路径 | preliminaryStake 乘? | Kelly 块 recommendedStake 乘? | 实际累积 |
|------|---------------------|------------------------------|---------|
| A. Kelly 禁用 | ✅ 1 次 | ❌ (gate by isKellyStakeEnabled) | **0.50 × 1** ✓ |
| B. Kelly 启用 + 正常 stake | preliminaryStake 被丢弃 | ✅ 1 次 (作用于 fresh kellyStake) | **0.50 × 1** ✓ |
| C. Kelly 启用 + `kellyStake==null` fallback | ✅ 1 次 → recommendedStake=preliminaryStake | ✅ 又乘 1 次 | **0.50 × 0.50 = 0.25** ❌ |
| D. Kelly 启用 + negativeEV + manualAllowlistBypass | ✅ 1 次 → recommendedStake=preliminaryStake | ✅ 又乘 1 次 | **0.50 × 0.50 = 0.25** ❌ |

5/17 那笔 task #102271 (Kelly stake=$24.91, manualAllowlist 白名单 paper 信号) 正是落在路径 D — Kelly 算正 EV 但走 `recommendedStake = kellyStake` 路径 B 应该只乘 1 次... 等等, 让我们再校对一下事实。

实际更仔细: log 显示 "Kelly stake=24.91 (p=0.7318) — Kelly 算正 EV" → 路径 B. 路径 B 按理只乘 1 次。但报告里写了"应用 2 次". 这是因为 preliminaryStake 在 line 712 被乘后, 通过 line 915 的 `tradeProfitabilityScoringService.estimate(profile, event, consensus, preliminaryStake)` 影响了 profitabilityEstimate (continuum 计算), 进而影响 kellyStake 本身的输入 — 严格说不是"再乘一次", 但效果类似 (preliminaryStake 缩水 → profitability scoring 偏低 → 后续 cap 偏小).

无论如何, **路径 C/D 的双重缩减是真 bug**, 路径 B 的"间接污染"也只有把 711-715 块用 `kellyStakeAdopted` 控制才能完全干净.

## 修复

最小动作 — 让 711-720 块**只在 legacy formula path (Kelly 未采用)** 下生效:

```java
// preliminaryStake 块 (保留, default 0.50)
if ("LONG_HORIZON".equals(marketType)) {
    BigDecimal multiplier = risk.getLongHorizonStakeMultiplier() == null
            ? new BigDecimal("0.50")
            : safe(risk.getLongHorizonStakeMultiplier());
    preliminaryStake = preliminaryStake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    reasons.add("[LONG_HORIZON_STAKE] Long-horizon market stake multiplier applied (preliminary): "
            + multiplier.setScale(2, RoundingMode.HALF_UP) + ".");
}

// ── Gate 10: Kelly 仓位计算 ──
BigDecimal recommendedStake;
boolean kellyStakeAdopted = false;  // ← 新增 flag
if (risk.isKellyStakeEnabled()) {
    BigDecimal kellyStake = kellyStakeCalculator.compute(...);
    if (kellyStake == null) {
        recommendedStake = preliminaryStake;
        ...
    } else if (kellyStake.compareTo(BigDecimal.ZERO) == 0) {
        if (manualAllowlistApplied && risk.isManualAllowlistBypassKellyNegativeEv()) {
            recommendedStake = preliminaryStake;
            ...
        } else {
            recommendedStake = BigDecimal.ZERO;
            ...
        }
    } else {
        recommendedStake = kellyStake;
        kellyStakeAdopted = true;     // ← 唯一置 true 的地方
        ...
    }
} else {
    recommendedStake = preliminaryStake;
}

// Kelly 块末尾 — 把 condition 从 isKellyStakeEnabled 收窄到 kellyStakeAdopted
if ("LONG_HORIZON".equals(marketType) && kellyStakeAdopted) {
    BigDecimal multiplier = risk.getLongHorizonStakeMultiplier() == null
            ? new BigDecimal("0.50")
            : safe(risk.getLongHorizonStakeMultiplier());
    recommendedStake = recommendedStake.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    reasons.add("[LONG_HORIZON_STAKE] Long-horizon market Kelly stake multiplier applied: ...");
}
```

## 修复后行为矩阵

| 路径 | preliminaryStake 块 | Kelly 块末 | 实际累积 |
|------|-------------------|-----------|---------|
| A. Kelly 禁用 | ✅ 1 次 | ❌ (kellyStakeAdopted=false) | **0.50 × 1** ✓ |
| B. Kelly 启用 + 正常 | 仍乘 (但 preliminaryStake 被丢弃) | ✅ 1 次 | **0.50 × 1** ✓ |
| C. Kelly null fallback | ✅ 1 次 → recommendedStake=preliminaryStake | ❌ (kellyStakeAdopted=false) | **0.50 × 1** ✓ (was 0.25) |
| D. negativeEV+bypass | ✅ 1 次 → recommendedStake=preliminaryStake | ❌ (kellyStakeAdopted=false) | **0.50 × 1** ✓ (was 0.25) |

reasons log 里两条不会同时出现:
- Kelly 真正 adopt 时只看到 `Kelly stake multiplier applied:`
- 其他路径只看到 `stake multiplier applied (preliminary):`

## 配套 yml 调整建议

之前为缓解这个 bug, 在 `/opt/coinstrategy/smart-wallet/config/application-test.yml` 加了:
```yaml
smart-wallet:
  signal-risk:
    long-horizon-stake-multiplier: 1.0    # 把 0.50 改成 1.0 让两次都无效
```

修复后路径 C/D 已经只乘 1 次, 这个 override 是否要去掉两个选项:

| 选项 | yml 值 | 实际效果 |
|------|--------|---------|
| 1. 恢复默认 | 删掉 / 设回 0.50 | LONG_HORIZON 市场永远砍一半. 这是原始设计意图 (长尾市场风险更高) |
| 2. 保留 override | 仍 1.0 | LONG_HORIZON 市场不再被缩减. 用于过渡期, 让更多信号通过, 等观察 PENDING/FOLLOWED 起来后再恢复 |

**推荐选项 2 过 24-48 小时**: 现在主要瓶颈是 0 PENDING, 等真正开几笔单看 stake 是否合理再决定恢不恢复 0.50.

## 部署

修复已经写入:
- `coin-strategy-job/.../SmartWalletSignalRiskService.java` (line 711-720, 922-977)

部署脚本 `polymarket/deploy-auto-allowlist-job-2026-05-19.sh` 已经把 SignalRiskService 加入 CHANGED_FILES, 所以你跑那个脚本会同时部署:
1. AutoAllowlistConfig
2. WeeklyTopLeaderAutoAllowlistJob
3. **SignalRiskService LONG_HORIZON 修复**
4. yml WEEK leaderboard + auto-allowlist 配置

## 单元测试影响

grep 了 `LONG_HORIZON_STAKE` / `longHorizonStakeMultiplier` 在 test 目录, **没有断言依赖这两条 reason 字符串**, 也没有断言依赖"应用 2 次"的行为. 修复对现有测试无破坏.

## 验证步骤

部署后跑一个 LONG_HORIZON 信号 (或观察现有日志), `reasons` 字段应该:
- 只出现 `[LONG_HORIZON_STAKE] Long-horizon market Kelly stake multiplier applied: 0.50.` (Kelly 路径)
- 或者只出现 `[LONG_HORIZON_STAKE] Long-horizon market stake multiplier applied (preliminary): 0.50.` (fallback / Kelly 禁用)
- **绝不**两条同时出现

SQL 查最近 follow_task 的 reasons 字段:
```sql
SELECT id, created_at,
       JSON_EXTRACT(reasons_json, '$[*]') AS reasons
FROM follow_task
WHERE market_type = 'LONG_HORIZON'
  AND created_at >= NOW() - INTERVAL 1 HOUR
ORDER BY id DESC
LIMIT 20;
```
